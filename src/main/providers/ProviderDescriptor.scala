// ABOUTME: Data class describing a provider's metadata, config keys, defaults, and factory
// ABOUTME: Replaces scattered hardcoded match/case blocks with a single registration point per provider
package org.nlogo.extensions.llm.providers

import scala.concurrent.ExecutionContext

/**
 * Describes how a provider should be ready-checked.
 * ApiKey: check that the provider-specific API key is set and non-empty.
 * ServerReachable: check that the provider's server responds (e.g. Ollama).
 */
enum ReadinessCheck:
  case ApiKey
  case ServerReachable

/**
 * Immutable descriptor holding all metadata for a single LLM provider.
 *
 * One descriptor is registered per provider at extension load time.
 * Consumers (ProviderFactory, LLMExtension, ModelRegistry, etc.) read
 * from the registry instead of maintaining per-provider match/case blocks.
 *
 * @param name             Lowercase canonical name (e.g. "openai", "openrouter")
 * @param displayName      Human-readable name for UI/help text
 * @param apiKeyConfigKey  Config key for this provider's API key (e.g. "openai_api_key")
 * @param baseUrlConfigKey Config key for this provider's base URL (e.g. "openai_base_url")
 * @param defaultBaseUrl   Default API base URL
 * @param defaultModel     Default model name
 * @param defaultMaxTokens Default max_tokens value as string
 * @param requiresApiKey   Whether an API key is mandatory
 * @param apiKeyPrefix     Optional prefix for API key validation hint (e.g. Some("sk-"))
 * @param readinessCheck   How to determine if the provider is ready to use
 * @param exposesThinking  Whether thinking/reasoning text appears in API responses
 * @param reasoningEffortValues Effort levels this provider's API accepts. Providers
 *                         disagree: Groq rejects anything outside
 *                         none|default|low|medium|high with a 400, while OpenAI and
 *                         Anthropic take xhigh. Validating against one global set let
 *                         `llm:set-reasoning-effort "xhigh"` through to a hard failure
 *                         on Groq, and rejected "default", which Groq accepts.
 * @param helpText         Multi-line setup instructions shown by llm:provider-help
 * @param factory          Function that creates a new LLMProvider instance given an ExecutionContext
 */
case class ProviderDescriptor(
  name: String,
  displayName: String,
  apiKeyConfigKey: String,
  baseUrlConfigKey: String,
  defaultBaseUrl: String,
  defaultModel: String,
  defaultMaxTokens: String,
  requiresApiKey: Boolean,
  apiKeyPrefix: Option[String],
  readinessCheck: ReadinessCheck,
  exposesThinking: Boolean,
  helpText: String,
  factory: ExecutionContext => LLMProvider,
  reasoningEffortValues: Set[String] = ProviderDescriptor.DefaultReasoningEffortValues
)

object ProviderDescriptor {
  /** Effort levels accepted by OpenAI-style APIs, and the fallback for providers
    * that have not declared their own. */
  val DefaultReasoningEffortValues: Set[String] =
    Set("none", "low", "medium", "high", "xhigh")
}
