// ABOUTME: OpenAI provider implementation for GPT models
// ABOUTME: Extends OpenAICompatibleProvider — all request/response logic is inherited
package org.nlogo.extensions.llm.providers

import scala.concurrent.ExecutionContext

/**
 * OpenAI provider implementation.
 *
 * Inherits all Chat Completions wire format from OpenAICompatibleProvider.
 * Only declares provider-specific config values.
 */
class OpenAIProvider(implicit ec: ExecutionContext) extends OpenAICompatibleProvider {

  override def providerName: String = "openai"

  override def defaultModel: String = ModelRegistry.defaultModel("openai")

  override protected def defaultBaseUrl: String = "https://api.openai.com/v1"

  override protected def baseUrlConfigKey: String = "openai_base_url"

  override protected def apiKeyConfigKey: String = "openai_api_key"

  override protected def defaultMaxTokens: String = "1000"

  override protected def requiresApiKey: Boolean = true
}
