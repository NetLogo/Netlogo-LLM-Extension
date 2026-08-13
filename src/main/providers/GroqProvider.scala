// ABOUTME: Groq provider — very fast inference on open-weight models via LPU hardware
// ABOUTME: Extends OpenAICompatibleProvider with Groq-specific reasoning fields and thinking extraction
package org.nlogo.extensions.llm.providers

import org.nlogo.extensions.llm.models.ChatRequest
import scala.concurrent.ExecutionContext

/**
 * Groq provider implementation.
 *
 * Groq serves open-weight models (gpt-oss, Qwen, Llama) on custom LPU hardware,
 * giving very high tokens/second through an OpenAI-compatible API. The generous
 * free tier makes it a good fit for classroom and workshop use.
 *
 * Key differences from direct OpenAI:
 * - max_tokens is deprecated in favor of max_completion_tokens (both accepted)
 * - Reasoning models expose thinking in message.reasoning, or in <think>...</think>
 *   tags within content when reasoning_format is "raw" (the API default)
 * - gpt-oss models ignore reasoning_format and always use the reasoning field
 * - Unsupported OpenAI fields (logprobs, logit_bias, top_logprobs, presence_penalty)
 *   are never sent by this extension, so no filtering is required
 */
class GroqProvider(implicit ec: ExecutionContext) extends OpenAICompatibleProvider {

  override def providerName: String = "groq"

  override def defaultModel: String = "openai/gpt-oss-20b"

  override protected def defaultBaseUrl: String = "https://api.groq.com/openai/v1"

  override protected def baseUrlConfigKey: String = "groq_base_url"

  override protected def apiKeyConfigKey: String = "groq_api_key"

  override protected def defaultMaxTokens: String = "1000"

  override protected def requiresApiKey: Boolean = true

  // No extra headers needed (unlike OpenRouter)

  /**
   * Groq accepts OpenAI's top-level reasoning_effort. Valid values vary by model
   * (gpt-oss accepts low/medium/high; qwen3 accepts none/default), so we pass the
   * user's choice straight through rather than guessing a default.
   *
   * We also request reasoning_format "parsed" so thinking arrives in the dedicated
   * message.reasoning field instead of inline <think> tags. gpt-oss models ignore
   * this parameter and use message.reasoning anyway, so it is safe either way.
   */
  override protected def applyReasoningFields(baseObj: ujson.Obj, request: ChatRequest): Unit = {
    baseObj("reasoning_format") = "parsed"
    request.thinkingConfig.flatMap(_.reasoningEffort).foreach { effort =>
      baseObj("reasoning_effort") = effort
    }
  }

  /**
   * Extract thinking text from Groq responses.
   *
   * Two extraction paths:
   * 1. message.reasoning field (reasoning_format "parsed", and always for gpt-oss)
   * 2. <think>...</think> tags in message.content (reasoning_format "raw")
   */
  override protected def extractThinking(message: ujson.Value): Option[String] = {
    // Path 1: check message.reasoning field
    val fromReasoning = try {
      message.obj.get("reasoning").flatMap { v =>
        val text = v.str.trim
        if (text.nonEmpty) Some(text) else None
      }
    } catch {
      case _: Exception => None
    }

    if (fromReasoning.isDefined) return fromReasoning

    // Path 2: parse <think>...</think> tags from content (reasoning_format "raw")
    // (?s) makes dot match newlines. Content is returned unmodified — we only
    // mirror the reasoning into a separate field for llm:chat-with-thinking.
    try {
      val content = message("content").str
      val pattern = """(?s)<think>(.*?)</think>""".r
      pattern.findFirstMatchIn(content)
        .map(_.group(1).trim)
        .filter(_.nonEmpty)
    } catch {
      case _: Exception => None
    }
  }
}
