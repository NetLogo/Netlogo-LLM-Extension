// ABOUTME: Abstract base for providers using OpenAI-compatible Chat Completions API
// ABOUTME: Shared by OpenAI, OpenRouter, and Together AI — subclasses override hooks for headers, reasoning, and thinking
package org.nlogo.extensions.llm.providers

import scala.concurrent.ExecutionContext

import sttp.client4._
import sttp.model.Uri
import ujson._

import org.nlogo.extensions.llm.config.ConfigStore
import org.nlogo.extensions.llm.models.{ChatMessage, ChatRequest, ChatResponse, Choice, EnumFormat, JsonObjectFormat, JsonSchemaFormat, ResponseFormat, Usage}

object OpenAICompatibleProvider {
  /**
   * Smallest completion budget sent to a model in reasoning format when
   * max_tokens is still the provider default. Reasoning tokens count against
   * max_completion_tokens, so the plain-chat default leaves too little room
   * for the visible answer.
   */
  val MinReasoningCompletionTokens: Int = 4000
}

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
        baseRequest("max_completion_tokens") = reasoningCompletionBudget(maxTokens)
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
   * The max_completion_tokens for a reasoning-format request. A max_tokens
   * the user never set (still the seeded default) is raised to
   * MinReasoningCompletionTokens; any value the user set, even one equal to
   * the default, is the modeler's choice and is sent unchanged.
   */
  protected def reasoningCompletionBudget(maxTokens: Int): Int = {
    val seededDefault = configStore.isDefault(ConfigStore.MAX_TOKENS) &&
      configStore.get(ConfigStore.MAX_TOKENS).flatMap(_.trim.toIntOption).contains(maxTokens)
    if (seededDefault) math.max(maxTokens, OpenAICompatibleProvider.MinReasoningCompletionTokens)
    else maxTokens
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

  /**
   * Token usage in the OpenAI chat-completions shape. Reasoning tokens are a
   * subset of completion_tokens and cached tokens a subset of prompt_tokens,
   * so the headline counts already match the cross-provider definition.
   * OpenRouter adds a dollar `cost`; it is passed through untouched.
   */
  protected def parseUsage(parsed: ujson.Value): Option[Usage] =
    parsed.obj.get("usage").collect { case u: ujson.Obj => u }.flatMap { u =>
      for {
        input  <- Usage.longField(u, "prompt_tokens")
        output <- Usage.longField(u, "completion_tokens")
      } yield Usage(
        inputTokens = input,
        outputTokens = output,
        totalTokens = Usage.longField(u, "total_tokens").getOrElse(input + output),
        reasoningTokens = Usage.nestedLongField(u, "completion_tokens_details", "reasoning_tokens"),
        cacheReadTokens = Usage.nestedLongField(u, "prompt_tokens_details", "cached_tokens"),
        cacheWriteTokens = Usage.nestedLongField(u, "prompt_tokens_details", "cache_write_tokens"),
        cost = u.value.get("cost").collect { case ujson.Num(n) => n }
      )
    }

  override protected def parseProviderResponse(responseBody: String, model: String): ChatResponse = {
    try {
      val parsed = ujson.read(responseBody)

      val id = parsed("id").str
      val created = parsed("created").num.toLong
      val choices = parsed("choices").arr.zipWithIndex.map { case (choice, index) =>
        val message = choice("message")
        val role = message("role").str
        // A refusal arrives as content: null plus a "refusal" string, and
        // finish_reason may be null too. Read both null-safely so the usage
        // below is still parsed and the refusal reaches the caller.
        val content = message.obj.get("content").collect { case ujson.Str(s) => s }.getOrElse("")
        val finishReason = choice.obj.get("finish_reason").collect { case ujson.Str(s) => s }.getOrElse("")
        val refusal = message.obj.get("refusal").collect { case ujson.Str(s) => s }

        Choice(
          index = index,
          message = ChatMessage(role, content),
          finishReason = finishReason,
          refusal = refusal
        )
      }.toArray

      // Extract thinking text via the provider-specific hook
      val thinking = parsed("choices").arr.headOption
        .map(_("message"))
        .flatMap(extractThinking)

      ChatResponse(id, created, model, choices, thinking, usage = parseUsage(parsed))
    } catch {
      case e: Exception =>
        throw new RuntimeException(s"Failed to parse ${providerName} response: ${e.getMessage}\nResponse: $responseBody", e)
    }
  }
}
