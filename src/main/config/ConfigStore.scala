package org.nlogo.extensions.llm.config

import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters._
import scala.util.{Try, Success, Failure}

/**
 * In-memory configuration storage and management
 *
 * This class provides thread-safe storage and retrieval of configuration
 * key-value pairs with support for validation and defaults.
 */
class ConfigStore {
  private val config: scala.collection.concurrent.Map[String, String] =
    new ConcurrentHashMap[String, String]().asScala

  // Keys whose current value was seeded by setDefault rather than chosen by the
  // user. Any later set, load, or remove of a key takes it out of this set.
  private val defaulted: java.util.Set[String] = ConcurrentHashMap.newKeySet[String]()

  /**
   * Set a configuration value
   *
   * @param key Configuration key
   * @param value Configuration value
   */
  def set(key: String, value: String): Unit = {
    config(key) = value
    defaulted.remove(key)
  }

  /**
   * Seed a default value. Unlike set, the key still reads as not chosen by
   * the user (isDefault) until something else sets it.
   */
  def setDefault(key: String, value: String): Unit = {
    config(key) = value
    defaulted.add(key)
  }

  /** True when the key holds a value seeded by setDefault, not one the user set. */
  def isDefault(key: String): Boolean = defaulted.contains(key)

  /** The keys currently holding seeded default values. */
  def defaultKeys: Set[String] = defaulted.asScala.toSet

  /**
   * Get a configuration value
   *
   * @param key Configuration key
   * @return Option containing the value if it exists
   */
  def get(key: String): Option[String] = {
    config.get(key)
  }

  /**
   * Get a configuration value with a default
   *
   * @param key Configuration key
   * @param default Default value if key is not found
   * @return The configuration value or default
   */
  def getOrElse(key: String, default: String): String = {
    config.getOrElse(key, default)
  }

  /**
   * Check if a configuration key exists
   *
   * @param key Configuration key
   * @return true if the key exists
   */
  def contains(key: String): Boolean = {
    config.contains(key)
  }

  /**
   * Remove a configuration key
   *
   * @param key Configuration key to remove
   * @return Option containing the removed value
   */
  def remove(key: String): Option[String] = {
    defaulted.remove(key)
    config.remove(key)
  }

  /**
   * Clear all configuration
   */
  def clear(): Unit = {
    config.clear()
    defaulted.clear()
  }

  /**
   * Load configuration from a map, replacing existing values
   *
   * @param newConfig Map of configuration key-value pairs
   * @param defaultKeys Keys in newConfig that hold seeded defaults rather than
   *                    user choices (used to restore a snapshot)
   */
  def loadFromMap(newConfig: Map[String, String], defaultKeys: Set[String] = Set.empty): Unit = {
    config.clear()
    defaulted.clear()
    config ++= newConfig
    defaultKeys.filter(newConfig.contains).foreach(defaulted.add)
  }

  /**
   * Update configuration from a map, merging with existing values
   *
   * @param newConfig Map of configuration key-value pairs
   */
  def updateFromMap(newConfig: Map[String, String]): Unit = {
    config ++= newConfig
    newConfig.keys.foreach(defaulted.remove)
  }

  /**
   * Get all configuration as an immutable map
   *
   * @return Map containing all configuration key-value pairs
   */
  def toMap: Map[String, String] = {
    config.toMap
  }

  /**
   * Get all configuration keys
   *
   * @return Set of all configuration keys
   */
  def keys: Set[String] = {
    config.keySet.toSet
  }

  /**
   * Validate that required keys are present
   *
   * @param requiredKeys Set of required configuration keys
   * @return Try[Unit] - Success if all required keys present, Failure otherwise
   */
  def validateRequired(requiredKeys: Set[String]): Try[Unit] = {
    val missingKeys = requiredKeys -- config.keySet

    if (missingKeys.nonEmpty) {
      Failure(new IllegalStateException(
        s"Missing required configuration keys: ${missingKeys.mkString(", ")}"
      ))
    } else {
      Success(())
    }
  }

  /**
   * Get configuration summary for debugging
   *
   * @return String representation of configuration (values masked for security)
   */
  def summary: String = {
    config.map { case (key, value) =>
      val maskedValue = if (key.toLowerCase.contains("key") || key.toLowerCase.contains("secret")) {
        if (value.length > 8) s"${value.take(4)}...${value.takeRight(4)}" else "***"
      } else {
        value
      }
      s"$key=$maskedValue"
    }.mkString(", ")
  }
}

/**
 * Companion object with common configuration keys
 */
object ConfigStore {
  // Common configuration keys
  val PROVIDER = "provider"
  val API_KEY = "api_key"
  val MODEL = "model"
  val BASE_URL = "base_url"
  val TEMPERATURE = "temperature"
  val MAX_TOKENS = "max_tokens"
  val TIMEOUT_SECONDS = "timeout_seconds"
  
  // Per-provider API keys
  val OPENAI_API_KEY = "openai_api_key"
  val ANTHROPIC_API_KEY = "anthropic_api_key"
  val GEMINI_API_KEY = "gemini_api_key"
  
  // Per-provider base URLs
  val OPENAI_BASE_URL = "openai_base_url"
  val ANTHROPIC_BASE_URL = "anthropic_base_url"
  val GEMINI_BASE_URL = "gemini_base_url"
  val OLLAMA_BASE_URL = "ollama_base_url"
  
  // Thinking/reasoning model configuration
  val ENABLE_THINKING = "enable_thinking"
  val REASONING_EFFORT = "reasoning_effort"
  val THINKING_BUDGET_TOKENS = "thinking_budget_tokens"

  // Reserved for future use
  val INFERENCE_URL = "inference_url"

  // Default values
  val DEFAULT_PROVIDER = "openai"
  val DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1"
  val DEFAULT_ANTHROPIC_BASE_URL = "https://api.anthropic.com/v1"
  val DEFAULT_GEMINI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
  val DEFAULT_OLLAMA_BASE_URL = "http://localhost:11434"
  val DEFAULT_TEMPERATURE = "0.7"
  val DEFAULT_MAX_TOKENS = "1000"
  val DEFAULT_TIMEOUT_SECONDS = "30"

  /**
   * Create a new ConfigStore with default values
   */
  def withDefaults(): ConfigStore = {
    val store = new ConfigStore()
    store.setDefault(PROVIDER, DEFAULT_PROVIDER)
    // The default provider's default model comes from the model catalog
    // (models.yaml `default:`), so it can be updated without a Scala change.
    store.setDefault(MODEL, org.nlogo.extensions.llm.providers.ModelRegistry.defaultModel(DEFAULT_PROVIDER))
    store.setDefault(TEMPERATURE, DEFAULT_TEMPERATURE)
    store.setDefault(MAX_TOKENS, DEFAULT_MAX_TOKENS)
    store.setDefault(TIMEOUT_SECONDS, DEFAULT_TIMEOUT_SECONDS)
    store
  }
  
  /**
   * Get the provider-specific API key constant name.
   * Delegates to ProviderRegistry for registered providers, falls back to generic API_KEY.
   */
  def getProviderApiKeyName(provider: String): String =
    org.nlogo.extensions.llm.providers.ProviderRegistry.apiKeyConfigKey(provider)

  /**
   * Get the provider-specific base URL constant name.
   * Delegates to ProviderRegistry for registered providers, falls back to generic BASE_URL.
   */
  def getProviderBaseUrlName(provider: String): String =
    org.nlogo.extensions.llm.providers.ProviderRegistry.baseUrlConfigKey(provider)

  /**
   * Resolve the base URL a provider should send to.
   *
   * The provider-specific key (e.g. "anthropic_base_url") wins, then the legacy
   * generic BASE_URL, then the provider's built-in default. Neither key is ever
   * seeded with a default, so a value present here is one the user set.
   */
  def resolveBaseUrl(config: ConfigStore, providerBaseUrlKey: String, defaultBaseUrl: String): String =
    config.get(providerBaseUrlKey)
      .orElse(config.get(BASE_URL))
      .getOrElse(defaultBaseUrl)

  /**
   * Get default base URL for a provider.
   * Delegates to ProviderRegistry for registered providers.
   */
  def getDefaultBaseUrl(provider: String): String =
    org.nlogo.extensions.llm.providers.ProviderRegistry.defaultBaseUrl(provider)
}
