// ABOUTME: Factory for creating LLM provider instances from the ProviderRegistry
// ABOUTME: Delegates creation, validation, and defaults to ProviderDescriptor — no per-provider match/case
package org.nlogo.extensions.llm.providers

import org.nlogo.extensions.llm.config.ConfigStore
import scala.concurrent.ExecutionContext
import scala.util.{Try, Success, Failure}

/**
 * Factory for creating LLM provider instances.
 *
 * All provider-specific knowledge lives in ProviderDescriptor and the
 * ProviderRegistry. This factory provides creation, validation, and
 * default-config methods that delegate to the registry.
 */
object ProviderFactory {

  /**
   * Create a provider instance by name.
   */
  def createProvider(providerName: String)(implicit ec: ExecutionContext): Try[LLMProvider] = {
    val normalizedName = providerName.toLowerCase.trim

    ProviderRegistry.get(normalizedName) match {
      case Some(desc) => Try(desc.factory(ec))
      case None =>
        Failure(new IllegalArgumentException(
          s"Unknown provider: '$normalizedName'. Supported providers: ${ProviderRegistry.allNames.mkString(", ")}"
        ))
    }
  }

  /**
   * Create a provider instance with configuration.
   */
  def createProvider(providerName: String, config: Map[String, String])(implicit ec: ExecutionContext): Try[LLMProvider] = {
    createProvider(providerName).map { provider =>
      config.foreach { case (key, value) =>
        provider.setConfig(key, value)
      }
      provider
    }
  }

  /**
   * Create a provider from a ConfigStore.
   */
  def createProviderFromConfig(configStore: ConfigStore)(implicit ec: ExecutionContext): Try[LLMProvider] = {
    val providerName = configStore.getOrElse(ConfigStore.PROVIDER, ConfigStore.DEFAULT_PROVIDER)
    createProvider(providerName).map { provider =>
      copyConfig(configStore, provider)
      provider
    }
  }

  /**
   * Copy every entry of a ConfigStore into a provider, keeping seeded defaults
   * marked as defaults so the provider can tell them from values the user set.
   */
  def copyConfig(configStore: ConfigStore, provider: LLMProvider): Unit = {
    val defaults = configStore.defaultKeys
    configStore.toMap.foreach { case (key, value) =>
      if (defaults.contains(key)) provider.setDefaultConfig(key, value)
      else provider.setConfig(key, value)
    }
  }

  /**
   * Validate that a provider name is supported.
   */
  def isSupported(providerName: String): Boolean =
    ProviderRegistry.isSupported(providerName)

  /**
   * Get set of supported provider names.
   */
  def getSupportedProviders: Set[String] = ProviderRegistry.allNames

  /**
   * Get set of currently implemented providers.
   */
  def getImplementedProviders: Set[String] = ProviderRegistry.allNames

  /**
   * Check if a provider is implemented.
   */
  def isImplemented(providerName: String): Boolean =
    ProviderRegistry.isSupported(providerName)

  /**
   * Validate provider configuration.
   *
   * Generic validation using descriptor metadata:
   * - Checks API key presence for providers that require it
   * - Optionally validates API key prefix
   * - Warns if model is not in the known registry (but allows it)
   */
  def validateProviderConfig(providerName: String, config: Map[String, String]): Try[Unit] = {
    val normalizedName = providerName.toLowerCase.trim

    ProviderRegistry.get(normalizedName) match {
      case None =>
        Failure(new IllegalArgumentException(
          s"Unsupported provider: '$normalizedName'. Supported: ${ProviderRegistry.allNames.mkString(", ")}"
        ))

      case Some(desc) =>
        val keyValidation: Try[Unit] = if (desc.requiresApiKey) {
          val apiKey = config.get(desc.apiKeyConfigKey).orElse(config.get(ConfigStore.API_KEY))

          if (apiKey.isEmpty) {
            Failure(new IllegalArgumentException(
              s"${desc.displayName} provider requires an API key. Set '${desc.apiKeyConfigKey}' in config or call llm:set-api-key"
            ))
          } else if (apiKey.get.trim.isEmpty) {
            Failure(new IllegalArgumentException(
              s"${desc.displayName} API key cannot be empty"
            ))
          } else {
            // Validate API key prefix if specified
            desc.apiKeyPrefix match {
              case Some(prefix) if !apiKey.get.startsWith(prefix) =>
                Failure(new IllegalArgumentException(
                  s"${desc.displayName} API key should start with '$prefix'"
                ))
              case _ => Success(())
            }
          }
        } else {
          Success(())
        }

        keyValidation.map { _ =>
          // Warn if model is not in the known list, but allow it anyway
          config.get(ConfigStore.MODEL).foreach { model =>
            if (!ModelRegistry.isValidModel(normalizedName, model)) {
              System.err.println(
                s"WARNING: Model '$model' is not in the known model list for '$normalizedName'. " +
                "It will be used anyway — if the model name is wrong, the API will return an error."
              )
            }
          }
        }
    }
  }

  /**
   * Get provider-specific configuration requirements.
   */
  def getRequiredConfigKeys(providerName: String): Set[String] = {
    ProviderRegistry.get(providerName.toLowerCase.trim) match {
      case Some(desc) if desc.requiresApiKey => Set(ConfigStore.API_KEY)
      case _ => Set()
    }
  }

  /**
   * Apply the config changes llm:set-provider makes when switching to `providerName`.
   *
   * The legacy generic base_url belongs to the provider that was active when it
   * was set. On a switch to a different provider it is moved to the previous
   * provider's own key (unless the user already set that key) and cleared, so
   * it keeps applying to that provider but never leaks to the new one. A
   * base_url set again after the switch applies to the new provider.
   */
  def applyProviderSwitch(configStore: ConfigStore, providerName: String): Unit = {
    val previous = configStore.getOrElse(ConfigStore.PROVIDER, ConfigStore.DEFAULT_PROVIDER).toLowerCase.trim
    if (previous != providerName) {
      configStore.remove(ConfigStore.BASE_URL).foreach { url =>
        val previousKey = ConfigStore.getProviderBaseUrlName(previous)
        if (previousKey != ConfigStore.BASE_URL && !configStore.contains(previousKey)) {
          configStore.set(previousKey, url)
        }
      }
    }
    val defaults = getDefaultConfig(providerName)
    defaults.foreach { case (key, value) =>
      if (!configStore.contains(key) || key == ConfigStore.MODEL) {
        configStore.setDefault(key, value)
      }
    }
    configStore.set(ConfigStore.PROVIDER, providerName)
  }

  /**
   * Get provider-specific default configuration.
   */
  def getDefaultConfig(providerName: String): Map[String, String] = {
    ProviderRegistry.get(providerName.toLowerCase.trim) match {
      case Some(desc) => Map(
        ConfigStore.MODEL -> ModelRegistry.defaultModel(desc.name),
        ConfigStore.TEMPERATURE -> ConfigStore.DEFAULT_TEMPERATURE,
        ConfigStore.MAX_TOKENS -> desc.defaultMaxTokens
      )
      case None => Map()
    }
  }
}
