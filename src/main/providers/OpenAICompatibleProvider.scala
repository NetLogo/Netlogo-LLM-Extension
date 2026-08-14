// ABOUTME: Abstract base for providers using OpenAI-compatible Chat Completions API
// ABOUTME: Shared by OpenAI, OpenRouter, and Together AI — subclasses override hooks for headers, reasoning, and thinking
package org.nlogo.extensions.llm.providers

import org.nlogo.extensions.llm.models.{ChatMessage, ChatRequest, ChatResponse, Choice, EnumFormat, JsonObjectFormat, JsonSchemaFormat, ResponseFormat}
import org.nlogo.extensions.llm.config.ConfigStore
import sttp.client4._
import sttp.model.Uri
import ujson._
import scala.concurrent.ExecutionContext

/**
 * Abstract base for providers that use the OpenAI Chat Completions wire format.
 *
 * Handles the standard /chat/completions request/response. Subclasses override
 * three hooks for provider-specific behavior:
 *
 * - extraHeaders: additional HTTP headers (e.g. OpenRouter's HTTP-Referer)
 * - applyReasoningFields: how thinking/reasoning config maps to the request body
 * - extractThinking: how to extract thinking text from the response
 */
abstract class OpenAICompatibleProvider(implicit ec: ExecutionContext) extends BaseHttpProvider {

  /** Additional headers to merge into every request. Override in subclasses. */
  protected def extraHeaders: Map[String, String] = Map.empty

  /**
   * Apply reasoning/thinking fields to the request body.
   * Default implementation uses OpenAI's reasoning_effort at the top level.
   * OpenRouter overrides this to use the { reasoning: { effort: ... } } object.
   */
  protected def applyReasoningFields(baseObj: ujson.Obj, request: ChatRequest): Unit = {
    request.thinkingConfig.flatMap(_.reasoningEffort).foreach { effort =>
      baseObj("reasoning_effort") = effort
    }
  }

  /**
   * Extract thinking/reasoning text from a response message object.
   * Default returns None (OpenAI hides reasoning tokens).
   * OpenRouter overrides to read the "reasoning" field.
   */
  protected def extractThinking(message: ujson.Value): Option[String] = None

  override protected def buildApiUrl(baseUrl: String): Uri =
    uri"$baseUrl/chat/completions"

  override protected def buildHeaders(apiKey: Option[String]): Map[String, String] =
    Map(
      "Authorization" -> s"Bearer ${apiKey.get}",
      "Content-Type" -> "application/json"
    ) ++ extraHeaders

  override protected def createProviderRequest(request: ChatRequest): ujson.Value = {
    val isReasoning = request.thinkingConfig.exists(_.enabled)

    val messages = ujson.Arr(
      request.messages.map { msg =>
        // For reasoning models, convert system role to developer role
        val role = if (isReasoning && msg.role == "system") "developer" else msg.role
        ujson.Obj(
          "role" -> role,
          "content" -> msg.content
        )
      }*
    )

    val baseRequest = ujson.Obj(
      "model" -> request.model,
      "messages" -> messages
    )

    if (isReasoning) {
      // Reasoning models: use max_completion_tokens, no temperature
      request.maxTokens.foreach { maxTokens =>
        baseRequest("max_completion_tokens") = maxTokens
      }
      // Apply provider-specific reasoning fields
      applyReasoningFields(baseRequest, request)
    } else {
      // Standard models: use max_tokens and temperature
      request.maxTokens.foreach { maxTokens =>
        baseRequest("max_tokens") = maxTokens
      }
      request.temperature.foreach { temp =>
        baseRequest("temperature") = temp
      }
    }

    applyResponseFormat(baseRequest, request)

    baseRequest
  }

  /**
   * Add the OpenAI `response_format` field.
   *
   * A schema is normalized to strict mode first: OpenAI rejects a schema that
   * omits `additionalProperties: false` or leaves any property out of
   * `required`, and applying the same normalization for every provider keeps one
   * modeler-written schema working across all of them.
   */
  protected def applyResponseFormat(baseObj: ujson.Obj, request: ChatRequest): Unit =
    request.responseFormat.foreach {
      case JsonSchemaFormat(schema, name) =>
        baseObj("response_format") = jsonSchemaField(name, ResponseFormat.strictSchema(schema))

      case enumFormat: EnumFormat =>
        baseObj("response_format") =
          jsonSchemaField(ResponseFormat.DefaultSchemaName, enumFormat.schema)

      case JsonObjectFormat =>
        baseObj("response_format") = ujson.Obj("type" -> "json_object")
    }

  private def jsonSchemaField(name: String, schema: ujson.Value): ujson.Obj =
    ujson.Obj(
      "type" -> "json_schema",
      "json_schema" -> ujson.Obj(
        "name" -> name,
        "strict" -> true,
        "schema" -> schema
      )
    )

  override protected def parseProviderResponse(responseBody: String, model: String): ChatResponse = {
    try {
      val parsed = ujson.read(responseBody)

      val id = parsed("id").str
      val created = parsed("created").num.toLong
      val choices = parsed("choices").arr.zipWithIndex.map { case (choice, index) =>
        val message = choice("message")
        val role = message("role").str
        val content = message("content").str
        val finishReason = choice("finish_reason").str

        Choice(
          index = index,
          message = ChatMessage(role, content),
          finishReason = finishReason
        )
      }.toArray

      // Extract thinking text via the provider-specific hook
      val thinking = parsed("choices").arr.headOption
        .map(_("message"))
        .flatMap(extractThinking)

      ChatResponse(id, created, model, choices, thinking)
    } catch {
      case e: Exception =>
        throw new RuntimeException(s"Failed to parse ${providerName} response: ${e.getMessage}\nResponse: $responseBody", e)
    }
  }
}
