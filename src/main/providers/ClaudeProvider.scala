// ABOUTME: Anthropic Claude provider implementation for Claude models
// ABOUTME: Extends BaseHttpProvider with Claude-specific request/response formatting and authentication

package org.nlogo.extensions.llm.providers

import org.nlogo.extensions.llm.models.{ChatMessage, ChatRequest, ChatResponse, EnumFormat, JsonObjectFormat, JsonSchemaFormat, ResponseFormat}
import org.nlogo.extensions.llm.config.ConfigStore
import sttp.client4._
import sttp.model.Uri
import ujson._
import scala.concurrent.ExecutionContext

/**
 * Anthropic Claude provider implementation for Claude models
 *
 * Extends BaseHttpProvider with Claude-specific behavior:
 * - Uses x-api-key header instead of Bearer token
 * - Requires anthropic-version header
 * - Separates system messages from other messages in request format
 */
class ClaudeProvider(implicit ec: ExecutionContext) extends BaseHttpProvider {

  override def providerName: String = "anthropic"

  override def defaultModel: String = ModelRegistry.defaultModel("anthropic")

  override protected def defaultBaseUrl: String = ConfigStore.DEFAULT_ANTHROPIC_BASE_URL

  override protected def baseUrlConfigKey: String = ConfigStore.ANTHROPIC_BASE_URL

  override protected def apiKeyConfigKey: String = ConfigStore.ANTHROPIC_API_KEY

  override protected def defaultMaxTokens: String = "4000"

  override protected def requiresApiKey: Boolean = true

  override protected def buildApiUrl(baseUrl: String): Uri = {
    uri"$baseUrl/messages"
  }

  override protected def buildHeaders(apiKey: Option[String]): Map[String, String] = {
    // 2023-06-01 is the only current Anthropic API version; thinking does not
    // require a different one. (A previous version bumped this to "2025-04-15"
    // when thinking was on, which is not a version Anthropic publishes.)
    Map(
      "x-api-key" -> apiKey.getOrElse(throw new IllegalStateException("API key required for Claude")),
      "content-type" -> "application/json",
      "anthropic-version" -> ClaudeProvider.ApiVersion
    )
  }

  override protected def createProviderRequest(request: ChatRequest): ujson.Value = {
    val isThinking = request.thinkingConfig.exists(_.enabled)

    // Claude API expects system message separate from other messages
    val (systemMessage, userMessages) = request.messages.partition(_.role == "system")

    val messages = ujson.Arr(
      userMessages.map { msg =>
        ujson.Obj(
          "role" -> msg.role,
          "content" -> msg.content
        )
      }*
    )

    val maxTokens = request.maxTokens.getOrElse(4000)

    val baseRequest = ujson.Obj(
      "model" -> request.model,
      "messages" -> messages,
      "max_tokens" -> maxTokens
    )

    // Add system message if present
    systemMessage.headOption.foreach { sysMsg =>
      baseRequest("system") = sysMsg.content
    }

    // Newer Claude generations (4.7+) reject non-default temperature/top_p/top_k
    // with a 400 on EVERY request, thinking or not -- so this gate also applies
    // to the non-thinking path below.
    val allowsSampling = ClaudeModelCapabilities.supportsSamplingParams(request.model)

    if (isThinking) {
      ClaudeModelCapabilities.thinkingMode(request.model) match {
        case ClaudeThinkingMode.Extended =>
          // Legacy shape: budget >= 1024 AND budget < max_tokens, so max_tokens must be > 1024
          if (maxTokens <= 1024) {
            throw new RuntimeException(
              s"Claude thinking requires max_tokens > 1024 (current: $maxTokens). " +
              "The thinking budget must be at least 1024 and less than max_tokens."
            )
          }

          // These models require temperature=1.0 when thinking is enabled
          if (allowsSampling) {
            baseRequest("temperature") = 1.0
          }

          val budget = request.thinkingConfig.flatMap(_.budgetTokens)
            .map(b => math.max(1024, math.min(b, maxTokens - 1)))
            .getOrElse(math.max(1024, math.min(4096, maxTokens - 1)))

          baseRequest("thinking") = ujson.Obj(
            "type" -> "enabled",
            "budget_tokens" -> budget
          )

        case ClaudeThinkingMode.Adaptive =>
          // Adaptive models take no budget_tokens and no temperature; depth is
          // steered by output_config.effort instead.
          baseRequest("thinking") = ujson.Obj("type" -> "adaptive")

          ClaudeModelCapabilities
            .effortValue(request.thinkingConfig.flatMap(_.reasoningEffort))
            .foreach { effort =>
              outputConfig(baseRequest)("effort") = effort
            }
      }
    } else if (allowsSampling) {
      request.temperature.foreach { temp =>
        baseRequest("temperature") = temp
      }
    }

    applyResponseFormat(baseRequest, request)

    baseRequest
  }

  /**
   * Get (creating if needed) the request's `output_config` object.
   *
   * `effort` and `format` are siblings under one key, so both writers must
   * extend the same object. Assigning a fresh `ujson.Obj` from either would
   * silently drop whatever the other had already set.
   */
  private def outputConfig(baseRequest: ujson.Obj): ujson.Obj =
    baseRequest.value.get("output_config") match {
      case Some(existing: ujson.Obj) => existing
      case _ =>
        val created = ujson.Obj()
        baseRequest("output_config") = created
        created
    }

  /**
   * Add Anthropic's native `output_config.format`.
   *
   * Only schema-bearing formats are sent. Anthropic has no schemaless JSON mode,
   * so [[JsonObjectFormat]] adds nothing here — inventing a `json_object` type
   * would be rejected by the API. The prompt-level instruction added by the
   * extension carries that case instead.
   */
  private def applyResponseFormat(baseRequest: ujson.Obj, request: ChatRequest): Unit =
    request.responseFormat.foreach {
      case JsonSchemaFormat(schema, _) =>
        outputConfig(baseRequest)("format") = ujson.Obj(
          "type" -> "json_schema",
          "schema" -> ResponseFormat.strictSchema(schema)
        )

      case enumFormat: EnumFormat =>
        outputConfig(baseRequest)("format") = ujson.Obj(
          "type" -> "json_schema",
          "schema" -> enumFormat.schema
        )

      case JsonObjectFormat => ()
    }

  override protected def parseProviderResponse(responseBody: String, model: String): ChatResponse = {
    try {
      val parsed = ujson.read(responseBody)

      val id = parsed("id").str
      val created = System.currentTimeMillis() / 1000 // Claude doesn't provide created timestamp

      val contentBlocks = parsed("content").arr

      // Separate thinking blocks from text blocks
      val thinkingBlocks = contentBlocks.filter(b =>
        scala.util.Try(b("type").str).toOption.contains("thinking")
      )
      val thinkingTexts = thinkingBlocks.flatMap { b =>
        scala.util.Try(b("thinking").str).toOption.orElse {
          System.err.println(s"WARNING: Claude thinking block present but could not extract thinking text: $b")
          None
        }
      }

      val textBlocks = contentBlocks.filter(b =>
        scala.util.Try(b("type").str).toOption.contains("text")
      )

      // Fall back to first block if no explicit text blocks found
      val text = if (textBlocks.nonEmpty) {
        textBlocks.map { b =>
          scala.util.Try(b("text").str).getOrElse {
            System.err.println(s"WARNING: Claude text block missing 'text' field: $b")
            ""
          }
        }.mkString
      } else {
        contentBlocks.headOption.flatMap { b =>
          scala.util.Try(b("text").str).toOption
        }.getOrElse {
          System.err.println(s"WARNING: No text blocks found in Claude response, falling back to empty string. Content blocks: $contentBlocks")
          ""
        }
      }

      val thinking = if (thinkingTexts.nonEmpty) Some(thinkingTexts.mkString("\n")) else None

      val choices = Array(
        org.nlogo.extensions.llm.models.Choice(
          index = 0,
          message = ChatMessage("assistant", text),
          finishReason = parsed("stop_reason").str
        )
      )

      ChatResponse(id, created, model, choices, thinking = thinking)
    } catch {
      case e: Exception =>
        throw new RuntimeException(s"Failed to parse Claude response: ${e.getMessage}\nResponse: $responseBody", e)
    }
  }
}

object ClaudeProvider {
  /** The only Anthropic API version currently published. */
  val ApiVersion: String = "2023-06-01"
}
