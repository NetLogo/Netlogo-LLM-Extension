// ABOUTME: OpenRouter provider — access 200+ models through one API key
// ABOUTME: Extends OpenAICompatibleProvider with OpenRouter-specific headers, reasoning format, and thinking extraction
package org.nlogo.extensions.llm.providers

import org.nlogo.extensions.llm.models.ChatRequest
import scala.concurrent.ExecutionContext

/**
 * OpenRouter provider implementation.
 *
 * OpenRouter proxies to 200+ models from multiple vendors (OpenAI, Anthropic,
 * Google, Meta, DeepSeek, etc.) using an OpenAI-compatible API format.
 *
 * Key differences from direct OpenAI:
 * - Model names are vendor-prefixed (e.g. "openai/gpt-6-luna", "anthropic/claude-haiku-5.5")
 * - Extra headers: HTTP-Referer and X-Title for attribution
 * - Reasoning uses { reasoning: { effort: "..." } } instead of top-level reasoning_effort
 * - Thinking text is exposed in the response "reasoning" field
 */
class OpenRouterProvider(implicit ec: ExecutionContext) extends OpenAICompatibleProvider {

  override def providerName: String = "openrouter"

  override def defaultModel: String = ModelRegistry.defaultModel("openrouter")

  override protected def defaultBaseUrl: String = "https://openrouter.ai/api/v1"

  override protected def baseUrlConfigKey: String = "openrouter_base_url"

  override protected def apiKeyConfigKey: String = "openrouter_api_key"

  override protected def defaultMaxTokens: String = "1000"

  override protected def requiresApiKey: Boolean = true

  /** OpenRouter recommends HTTP-Referer and X-Title for attribution/ranking. */
  override protected def extraHeaders: Map[String, String] = Map(
    "HTTP-Referer" -> "https://ccl.northwestern.edu/netlogo/",
    "X-Title" -> "NetLogo LLM Extension"
  )

  /**
   * OpenRouter uses a unified reasoning object:
   * { "reasoning": { "effort": "high" } }
   * instead of OpenAI's top-level reasoning_effort.
   */
  override protected def applyReasoningFields(baseObj: ujson.Obj, request: ChatRequest): Unit = {
    request.thinkingConfig.flatMap(_.reasoningEffort).foreach { effort =>
      baseObj("reasoning") = ujson.Obj("effort" -> effort)
    }
  }

  /**
   * OpenRouter exposes thinking text in the response message's "reasoning" field
   * for models that support it (Anthropic, DeepSeek, etc.).
   */
  override protected def extractThinking(message: ujson.Value): Option[String] = {
    try {
      message.obj.get("reasoning").flatMap { v =>
        val text = v.str.trim
        if (text.nonEmpty) Some(text) else None
      }
    } catch {
      case _: Exception => None
    }
  }
}
