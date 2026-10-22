// ABOUTME: Together AI provider — fast open-source model inference
// ABOUTME: Extends OpenAICompatibleProvider with Together-specific reasoning and thinking extraction
package org.nlogo.extensions.llm.providers

import org.nlogo.extensions.llm.models.ChatRequest
import scala.concurrent.ExecutionContext

/**
 * Together AI provider implementation.
 *
 * Together AI provides fast inference for open-source models (Llama, DeepSeek,
 * Qwen, Gemma, Mistral, etc.) via an OpenAI-compatible API.
 *
 * Key differences from direct OpenAI:
 * - Model names are vendor-prefixed (e.g. "meta-llama/Llama-3.3-70B-Instruct-Turbo")
 * - Hybrid reasoning models use { reasoning: { enabled: true } }
 * - DeepSeek-R1 embeds thinking in <think>...</think> tags within content
 */
class TogetherProvider(implicit ec: ExecutionContext) extends OpenAICompatibleProvider {

  override def providerName: String = "together"

  override def defaultModel: String = ModelRegistry.defaultModel("together")

  override protected def defaultBaseUrl: String = "https://api.together.xyz/v1"

  override protected def baseUrlConfigKey: String = "together_base_url"

  override protected def apiKeyConfigKey: String = "together_api_key"

  override protected def defaultMaxTokens: String = "1000"

  override protected def requiresApiKey: Boolean = true

  // No extra headers needed (unlike OpenRouter)

  /**
   * Together hybrid models use { reasoning: { enabled: true } }.
   * Adjustable-effort models use top-level reasoning_effort (the default from base class).
   * We send both when thinking is enabled — the API ignores unrecognized fields.
   */
  override protected def applyReasoningFields(baseObj: ujson.Obj, request: ChatRequest): Unit = {
    // Enable reasoning for hybrid models
    baseObj("reasoning") = ujson.Obj("enabled" -> true)
    // Also pass effort level if specified (for adjustable-effort models)
    request.thinkingConfig.flatMap(_.reasoningEffort).foreach { effort =>
      baseObj("reasoning_effort") = effort
    }
  }

  /**
   * Extract thinking text from Together AI responses.
   *
   * Two extraction paths:
   * 1. message.reasoning field (most reasoning models)
   * 2. <think>...</think> tags in message.content (DeepSeek-R1)
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

    // Path 2: parse <think>...</think> tags from content (DeepSeek-R1)
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
