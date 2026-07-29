// ABOUTME: Google Gemini provider implementation for Gemini models
// ABOUTME: Handles API communication with Google's Gemini API using the LLMProvider interface

package org.nlogo.extensions.llm.providers

import org.nlogo.extensions.llm.models.{ChatMessage, ChatRequest, ChatResponse}
import org.nlogo.extensions.llm.config.ConfigStore
import sttp.client4._
import sttp.model.Uri
import ujson._
import scala.concurrent.ExecutionContext

/**
 * Google Gemini provider implementation for Gemini models
 *
 * Gemini has unique requirements:
 * - API key is passed as a query parameter, not in headers
 * - Model name is included in the URL path
 * - Uses "contents" array with "parts" for message formatting
 * - Maps "assistant" role to "model" role
 */
class GeminiProvider(implicit ec: ExecutionContext) extends BaseHttpProvider {

  override def providerName: String = "gemini"

  override def defaultModel: String = ModelRegistry.defaultModel("gemini")

  override protected def defaultBaseUrl: String = ConfigStore.DEFAULT_GEMINI_BASE_URL

  override protected def baseUrlConfigKey: String = ConfigStore.GEMINI_BASE_URL

  override protected def apiKeyConfigKey: String = ConfigStore.GEMINI_API_KEY

  override protected def defaultMaxTokens: String = "2048"

  override protected def requiresApiKey: Boolean = true

  /**
   * Override sendChatRequest to handle Gemini's unique URL construction
   *
   * Gemini requires:
   * - Model name in the URL path
   * - API key as a query parameter (not in headers)
   */
  override protected def sendChatRequest(request: ChatRequest): scala.concurrent.Future[ChatResponse] = {
    val apiKey = configStore.get(apiKeyConfigKey)
      .orElse(configStore.get(ConfigStore.API_KEY))
      .getOrElse(throw new IllegalStateException("API key not configured"))

    val baseUrl = configStore.get(baseUrlConfigKey).getOrElse(defaultBaseUrl)
    val apiUrl = uri"$baseUrl/models/${request.model}:generateContent?key=$apiKey"

    val headers = Map("Content-Type" -> "application/json")
    val requestBody = createProviderRequest(request).toString()

    val httpRequest = basicRequest
      .headers(headers)
      .body(requestBody)
      .post(apiUrl)

    executeWithRetry(httpRequest, request.model)
  }

  /**
   * Build Gemini API URL - not used as we override sendChatRequest
   */
  override protected def buildApiUrl(baseUrl: String): Uri = {
    // Not used - Gemini needs model name which isn't available here
    throw new UnsupportedOperationException("Use sendChatRequest override instead")
  }

  /**
   * Build headers for Gemini - not used as we override sendChatRequest
   */
  override protected def buildHeaders(apiKey: Option[String]): Map[String, String] = {
    // Not used - we override sendChatRequest
    throw new UnsupportedOperationException("Use sendChatRequest override instead")
  }

  /**
   * Convert ChatRequest to Gemini's request format
   *
   * Gemini expects:
   * - "contents" array with "role" and "parts"
   * - Role mapping: "assistant" → "model", "user" → "user"
   * - "generationConfig" for optional parameters (temperature, maxOutputTokens)
   */
  override protected def createProviderRequest(request: ChatRequest): ujson.Value = {
    // Convert messages to Gemini's format
    val contents = ujson.Arr(
      request.messages.map { msg =>
        val role = if (msg.role == "assistant") "model" else "user"
        ujson.Obj(
          "role" -> role,
          "parts" -> ujson.Arr(
            ujson.Obj("text" -> msg.content)
          )
        )
      }*
    )

    val baseRequest = ujson.Obj(
      "contents" -> contents
    )

    // Add generation config if parameters are specified
    val generationConfig = ujson.Obj()
    var hasConfig = false
    val isThinking = request.thinkingConfig.exists(_.enabled)

    request.temperature.foreach { temp =>
      generationConfig("temperature") = temp
      hasConfig = true
    }

    request.maxTokens.foreach { maxTokens =>
      generationConfig("maxOutputTokens") = maxTokens
      hasConfig = true
    }

    if (isThinking) {
      val thinkingObj = ujson.Obj()
      // Map reasoning effort or set budget
      request.thinkingConfig.flatMap(_.budgetTokens).foreach { budget =>
        thinkingObj("thinkingBudget") = budget
      }
      request.thinkingConfig.flatMap(_.reasoningEffort).foreach { effort =>
        thinkingObj("thinkingLevel") = effort.toUpperCase
      }
      generationConfig("thinkingConfig") = thinkingObj
      hasConfig = true
    }

    if (hasConfig) {
      baseRequest("generationConfig") = generationConfig
    }

    baseRequest
  }

  /**
   * Parse Gemini's response format into ChatResponse
   */
  override protected def parseProviderResponse(responseBody: String, model: String): ChatResponse = {
    try {
      val parsed = ujson.read(responseBody)
      val candidates = parsed("candidates").arr
      if (candidates.isEmpty) {
        val feedback = scala.util.Try(parsed("promptFeedback")).toOption
          .map(f => s" promptFeedback: $f").getOrElse("")
        throw new RuntimeException(s"Gemini returned empty candidates array.$feedback")
      }
      val candidate = candidates.head
      val finishReason = scala.util.Try(candidate("finishReason").str).getOrElse {
        System.err.println(s"WARNING: Could not extract finishReason from Gemini candidate: $candidate")
        "unknown"
      }

      val parts = try {
        candidate("content")("parts").arr.toSeq
      } catch {
        case ex: Exception =>
          System.err.println(s"WARNING: Could not extract content parts from Gemini response: ${ex.getMessage}")
          Seq.empty
      }

      // Separate thinking parts from regular text parts
      val thinkingParts = parts.filter(p => scala.util.Try(p("thought").bool).getOrElse(false))
      val textParts = parts.filter(p => !scala.util.Try(p("thought").bool).getOrElse(false))

      val text = if (textParts.nonEmpty) {
        textParts.flatMap { p =>
          scala.util.Try(p("text").str).toOption.orElse {
            System.err.println(s"WARNING: Could not extract text from Gemini text part: $p")
            None
          }
        }.mkString
      } else if (parts.nonEmpty) {
        parts.flatMap { p =>
          scala.util.Try(p("text").str).toOption.orElse {
            System.err.println(s"WARNING: Could not extract text from Gemini part: $p")
            None
          }
        }.mkString
      } else {
        s"[No content - $finishReason]"
      }

      val thinking = if (thinkingParts.nonEmpty) {
        val texts = thinkingParts.flatMap { p =>
          scala.util.Try(p("text").str).toOption.orElse {
            System.err.println(s"WARNING: Could not extract text from Gemini thinking part: $p")
            None
          }
        }
        Some(texts.mkString("\n")).filter(_.nonEmpty)
      } else None

      val choices = Array(
        org.nlogo.extensions.llm.models.Choice(
          index = 0,
          message = ChatMessage("assistant", text),
          finishReason = finishReason
        )
      )

      ChatResponse(s"gemini-${System.currentTimeMillis()}", System.currentTimeMillis() / 1000, model, choices, thinking = thinking)
    } catch {
      case e: RuntimeException => throw new RuntimeException(e.getMessage, e)
      case e: Exception =>
        throw new RuntimeException(s"Failed to parse Gemini response: ${e.getMessage}\nResponse: $responseBody", e)
    }
  }
}
