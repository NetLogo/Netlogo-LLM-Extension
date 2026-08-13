// ABOUTME: Detection and configuration resolution for reasoning/thinking models
// ABOUTME: Minimal auto-detect for models that BREAK without special handling; everything else is user-driven
package org.nlogo.extensions.llm.providers

import org.nlogo.extensions.llm.config.ConfigStore
import org.nlogo.extensions.llm.models.ThinkingConfig

/**
 * Utility for detecting reasoning models and resolving thinking configuration.
 *
 * Philosophy: Only auto-detect models that BREAK without special request format
 * (OpenAI o-series). All other providers require explicit user opt-in via
 * `llm:set-thinking true`.
 */
object ReasoningModelDetector {

  /**
   * Check if a model REQUIRES reasoning request format to avoid API errors.
   *
   * Only returns true for models that return HTTP 400 without special handling:
   * - OpenAI o1, o3, o4 series: must strip temperature, use max_completion_tokens,
   *   convert system->developer role
   *
   * All other providers return false — they don't break, they just won't
   * activate thinking without explicit config.
   */
  def requiresReasoningFormat(provider: String, model: String): Boolean = {
    provider.toLowerCase.trim match {
      case "openai" =>
        val m = model.toLowerCase
        m.startsWith("o1") || m.startsWith("o3") || m.startsWith("o4")
      case "openrouter" =>
        // Vendor-prefixed: check if the model after the prefix is an OpenAI o-series
        val m = model.toLowerCase
        if (m.startsWith("openai/")) {
          val modelName = m.stripPrefix("openai/")
          modelName.startsWith("o1") || modelName.startsWith("o3") || modelName.startsWith("o4")
        } else false
      case _ => false
    }
  }

  /**
   * Resolve the effective ThinkingConfig for a request.
   *
   * Priority:
   * 1. If requiresReasoningFormat() is true -> always enabled (non-negotiable)
   * 2. If enable_thinking is explicitly set in config -> use that value
   * 3. Otherwise -> None (standard behavior)
   *
   * When enabled, populates reasoningEffort and budgetTokens from config if set.
   */
  def resolveThinkingConfig(provider: String, model: String, configStore: ConfigStore): Option[ThinkingConfig] = {
    val forcedByModel = requiresReasoningFormat(provider, model)
    val explicitEnabled = configStore.get(ConfigStore.ENABLE_THINKING).map(_.toLowerCase == "true")

    val enabled = if (forcedByModel) true else explicitEnabled.getOrElse(false)

    if (!enabled) return None

    val effort = configStore.get(ConfigStore.REASONING_EFFORT)
    val budget = configStore.get(ConfigStore.THINKING_BUDGET_TOKENS).flatMap { s =>
      scala.util.Try(s.toInt).toOption.orElse {
        System.err.println(s"WARNING: Invalid thinking_budget_tokens value '$s' (not a valid integer), ignoring")
        None
      }
    }

    Some(ThinkingConfig(
      enabled = true,
      reasoningEffort = effort,
      budgetTokens = budget
    ))
  }

  /**
   * Whether a provider exposes thinking text in its API response.
   *
   * Delegates to ProviderRegistry.exposesThinking() so this knowledge
   * is defined once per provider in the descriptor.
   */
  def providerExposesThinking(provider: String): Boolean =
    ProviderRegistry.exposesThinking(provider)

  /**
   * Check if a model is a known reasoning model (for display purposes).
   * Broader than requiresReasoningFormat — includes models that support
   * thinking but don't break without it.
   */
  def isReasoningModel(provider: String, model: String): Boolean = {
    provider.toLowerCase.trim match {
      case "openai" =>
        val m = model.toLowerCase
        m.startsWith("o1") || m.startsWith("o3") || m.startsWith("o4")
      case "anthropic" =>
        model.contains("claude-3-7") || model.contains("claude-4")
      case "gemini" =>
        model.contains("2.5") || model.contains("3.")
      case "ollama" =>
        val m = model.toLowerCase
        m.contains("deepseek-r1") || m.contains("qwen3") || m.contains("qwq")
      case "openrouter" =>
        // Vendor-prefixed model names: detect reasoning models across vendors
        val m = model.toLowerCase
        m.startsWith("openai/o1") || m.startsWith("openai/o3") || m.startsWith("openai/o4") ||
        m.contains("claude-3-7") || m.contains("claude-4") ||
        m.contains("deepseek-r1") || m.contains("qwq")
      case "together" =>
        val m = model.toLowerCase
        m.contains("deepseek-r1") || m.contains("qwq") || m.contains("qwen3")
      case "groq" =>
        val m = model.toLowerCase
        m.contains("gpt-oss") || m.contains("qwen3") || m.contains("deepseek-r1")
      case _ => false
    }
  }
}
