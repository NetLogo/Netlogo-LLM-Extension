package org.nlogo.extensions.llm.providers

import org.nlogo.extensions.llm.models.{ChatMessage, ChatRequest, ChatResponse, ResponseFormat}
import scala.concurrent.Future
import scala.util.Try

/**
 * Abstract interface for all LLM providers
 *
 * This trait defines the common interface that all LLM providers must implement.
 * It follows the Strategy pattern to allow easy switching between different providers.
 */
trait LLMProvider {

  /**
   * Send a chat request and receive a response asynchronously
   *
   * @param request The chat request containing model, messages, and parameters
   * @return Future containing the chat response
   */
  def chat(request: ChatRequest): Future[ChatResponse]

  /**
   * Simplified chat method that takes messages directly
   *
   * @param messages The conversation history
   * @return Future containing the response message
   */
  def chat(messages: Seq[ChatMessage]): Future[ChatMessage]

  /**
   * Chat method that returns the full response including thinking text
   *
   * @param messages The conversation history
   * @return Future containing the complete ChatResponse with thinking field
   */
  def chatWithFullResponse(messages: Seq[ChatMessage]): Future[ChatResponse]

  /**
   * Chat with the reply constrained to a response format.
   *
   * Defaults to an unconstrained request so existing providers keep compiling
   * and behave exactly as before; providers that can enforce a format override
   * this. A provider that ignores the format still returns a usable answer,
   * which is the graceful-degradation behaviour the extension relies on.
   *
   * @param messages The conversation history
   * @param format   How the reply should be shaped
   */
  def chatWithFormat(messages: Seq[ChatMessage], format: ResponseFormat): Future[ChatResponse] =
    chatWithFullResponse(messages)

  /**
   * Set a configuration parameter for this provider
   *
   * @param key The configuration key (e.g., "api_key", "base_url")
   * @param value The configuration value
   */
  def setConfig(key: String, value: String): Unit

  /**
   * Get a configuration parameter value
   *
   * @param key The configuration key
   * @return Option containing the value if it exists
   */
  def getConfig(key: String): Option[String]

  /**
   * Validate that all required configuration is present
   *
   * @return Success if valid, Failure with error message if invalid
   */
  def validateConfig(): Try[Unit]

  /**
   * Get the provider name (e.g., "openai", "anthropic")
   */
  def providerName: String

  /**
   * Get the default model for this provider
   */
  def defaultModel: String

  /**
   * Check if the provider supports a specific model
   *
   * @param model The model name to check
   * @return true if the model is supported
   */
  def supportsModel(model: String): Boolean
}
