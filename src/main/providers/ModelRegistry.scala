package org.nlogo.extensions.llm.providers

import org.nlogo.extensions.llm.config.{ModelRegistryLoader, ProviderModels}
import scala.util.{Try, Success, Failure}

/**
 * Central registry for supported LLM models across all providers
 *
 * This registry provides a single source of truth for:
 * - Supported models per provider (loaded from YAML configs)
 * - Default models per provider
 * - Model validation
 * - Custom model tracking
 *
 * Models are loaded from, lowest layer first:
 * 1. Bundled YAML config in JAR resources (/config/models.yaml)
 * 2. Optional user catalog (~/.netlogo-llm/models.yaml, see ModelRegistryLoader.userCatalogPath)
 * 3. Optional override config (models-override.yaml) in model directory
 *
 * Any layer may name a provider's default model with `default:`; without one
 * the ProviderDescriptor default in ProviderRegistrations.scala applies.
 */
object ModelRegistry {

  // Lock for thread-safe access to mutable state
  private val lock = new Object

  // Mutable state for dynamic loading (access must be synchronized)
  private var providers: Map[String, ProviderModels] = Map.empty
  // Bundled (or fallback) catalog with the user catalog merged in, kept apart so
  // each model-directory override is applied to it fresh
  private var bundled: Map[String, ProviderModels] = Map.empty
  // The model-directory override currently applied on top of `bundled`
  private var overrideLayer: Map[String, ProviderModels] = Map.empty
  private var isInitialized: Boolean = false
  private var modelDirLoaded: Option[String] = None
  private var overrideLoadMessage: Option[String] = None
  private var userWarning: Option[String] = None

  /**
   * Catalog used when models.yaml cannot be loaded: each registered
   * provider's descriptor default and nothing else.
   *
   * Deliberately no hand-kept model list here — a list in Scala goes stale
   * without anyone noticing, which is what #64 removed.
   */
  private[llm] def descriptorFallback(): Map[String, ProviderModels] = {
    ensureDescriptors()
    ProviderRegistry.allNames.toSeq.flatMap(ProviderRegistry.get).map { d =>
      d.name -> ProviderModels(Set(d.defaultModel), isCustom = false, default = Some(d.defaultModel))
    }.toMap
  }

  // Descriptors are normally registered by LLMExtension.load(), but the
  // registry can be read before that (ConfigStore.withDefaults runs in the
  // extension's constructor). registerAll() is idempotent.
  private def ensureDescriptors(): Unit =
    if (!ProviderRegistry.isInitialized) ProviderRegistrations.registerAll()

  /**
   * Bundled catalog (or the descriptor fallback) with the user catalog merged
   * over it. A broken user file is reported and skipped.
   */
  private def loadBase(): (Map[String, ProviderModels], Option[String]) = {
    val base = ModelRegistryLoader.loadBundledConfig() match {
      case Success(config) => config
      case Failure(error) =>
        System.err.println(s"WARNING: Failed to load bundled model config: ${error.getMessage}")
        System.err.println("WARNING: Using each provider's default model only")
        descriptorFallback()
    }

    val userPath = ModelRegistryLoader.userCatalogPath
    ModelRegistryLoader.loadUserConfig(userPath) match {
      case Success(user) => (ModelRegistryLoader.mergeUserConfig(base, user), None)
      case Failure(error) =>
        val warning = s"WARNING: Ignoring user model catalog $userPath: ${error.getMessage}"
        System.err.println(warning)
        (base, Some(warning))
    }
  }

  /**
   * Initialize the registry by loading bundled configuration
   *
   * This is called automatically on first use, but can be called explicitly.
   * Safe to call multiple times - will only initialize once unless reset.
   */
  def init(): Unit = lock.synchronized {
    if (isInitialized) return

    val (base, warning) = loadBase()
    bundled = base
    providers = base
    userWarning = warning
    isInitialized = true
  }

  /**
   * Re-read the bundled catalog and the user catalog, keeping any
   * model-directory override in effect on top.
   *
   * Swaps the catalog in one step, so readers never see an empty registry.
   */
  private[llm] def reload(): Unit = lock.synchronized {
    val (base, warning) = loadBase()
    bundled = base
    providers = if (overrideLayer.nonEmpty) ModelRegistryLoader.mergeConfigs(base, overrideLayer) else base
    userWarning = warning
    isInitialized = true
  }

  /** The warning printed for a broken user catalog at the last load, if any. */
  private[llm] def userCatalogWarning: Option[String] = lock.synchronized(userWarning)

  /**
   * Load override configuration from a model directory
   *
   * Merges override config with bundled config. Override takes precedence.
   * Always rebuilds from the bundled catalog, so an override from a previously
   * loaded directory never survives a load from a directory without one.
   * Returns a notification message if custom models were loaded.
   *
   * @param modelDir Directory containing models-override.yaml
   * @return Some(message) if override loaded with custom model count, None otherwise
   */
  def loadOverride(modelDir: String): Option[String] = lock.synchronized {
    ensureInitialized()

    ModelRegistryLoader.loadOverrideConfig(modelDir) match {
      case Success((overrideConfig, customCount)) =>
        if (overrideConfig.nonEmpty) {
          providers = ModelRegistryLoader.mergeConfigs(bundled, overrideConfig)
          overrideLayer = overrideConfig
          modelDirLoaded = Some(modelDir)
          val message = s"Loaded $customCount custom models from override config"
          overrideLoadMessage = Some(message)
          Some(message)
        } else {
          clearOverrideState()
          None
        }

      case Failure(error) =>
        System.err.println(s"WARNING: Failed to load override config: ${error.getMessage}")
        clearOverrideState()
        None
    }
  }

  /**
   * Drop any loaded override and restore the bundled catalog
   *
   * Used when there is no model directory (e.g. an unsaved model).
   */
  def clearOverride(): Unit = lock.synchronized {
    ensureInitialized()
    clearOverrideState()
  }

  private def clearOverrideState(): Unit = {
    providers = bundled
    overrideLayer = Map.empty
    modelDirLoaded = None
    overrideLoadMessage = None
  }

  /**
   * Check if a specific model is from custom/override configuration
   *
   * @param providerName Provider name (case-insensitive)
   * @param model Model name
   * @return true if the model exists and is from override config
   */
  def isCustomModel(providerName: String, model: String): Boolean = {
    ensureInitialized()
    val normalizedProvider = providerName.toLowerCase.trim
    providers.get(normalizedProvider) match {
      case Some(pm) if pm.isCustom && pm.models.contains(model) => true
      case _ => false
    }
  }

  /**
   * Check if an entire provider's model list is from override configuration
   *
   * @param providerName Provider name (case-insensitive)
   * @return true if the provider exists and is from override config
   */
  def isProviderCustom(providerName: String): Boolean = {
    ensureInitialized()
    val normalizedProvider = providerName.toLowerCase.trim
    providers.get(normalizedProvider).exists(_.isCustom)
  }

  /**
   * Format a complete model list for display (used by llm:list-models)
   *
   * @param activeProvider Currently active provider name
   * @param activeModel Currently active model name
   * @return Formatted string showing all providers and models with markers
   */
  def formatModelList(activeProvider: String, activeModel: String): String = {
    ensureInitialized()

    val normalizedActiveProvider = activeProvider.toLowerCase.trim
    val lines = new StringBuilder()

    lines.append("=== Available Models ===\n")

    // Sort providers alphabetically for consistent output
    providers.keys.toSeq.sorted.foreach { provider =>
      lines.append(s"\n--- $provider ---\n")

      val pm = providers(provider)
      val sortedModels = pm.models.toSeq.sorted

      sortedModels.foreach { model =>
        val markers = new StringBuilder()

        // Add [ACTIVE] marker
        if (provider == normalizedActiveProvider && model == activeModel) {
          markers.append(" [ACTIVE]")
        }

        // Add [reasoning] marker
        if (ReasoningModelDetector.isReasoningModel(provider, model)) {
          markers.append(" [reasoning]")
        }

        // Add [custom] marker
        if (pm.isCustom || isCustomModel(provider, model)) {
          markers.append(" [custom]")
        }

        lines.append(s"  $model${markers.toString}\n")
      }
    }

    lines.append(s"\nCurrently using: $normalizedActiveProvider / $activeModel\n")
    lines.toString
  }

  /**
   * Reset the registry to uninitialized state (for testing)
   *
   * Clears all loaded configuration and state.
   */
  def reset(): Unit = lock.synchronized {
    providers = Map.empty
    bundled = Map.empty
    overrideLayer = Map.empty
    isInitialized = false
    modelDirLoaded = None
    overrideLoadMessage = None
    userWarning = None
  }

  /**
   * Ensure the registry is initialized before use
   *
   * Called internally by all public methods that need data.
   */
  private def ensureInitialized(): Unit = lock.synchronized {
    if (!isInitialized) init()
  }

  /**
   * Get supported models for a provider
   *
   * @param providerName Provider name (case-insensitive)
   * @return Set of supported model names
   */
  def getSupportedModels(providerName: String): Set[String] = {
    ensureInitialized()
    val normalizedProvider = providerName.toLowerCase.trim
    providers.get(normalizedProvider).map(_.models).getOrElse(Set.empty[String])
  }

  /**
   * Get default model for a provider.
   *
   * The loaded catalog's `default:` for the provider (bundled, user catalog,
   * or model-directory override — whichever layer set it last), falling back
   * to the ProviderDescriptor default in ProviderRegistrations.scala.
   *
   * @param providerName Provider name (case-insensitive)
   * @return Default model name
   */
  def defaultModel(providerName: String): String = {
    ensureInitialized()
    defaultFrom(lock.synchronized(providers), providerName)
  }

  /**
   * Default model for a provider in a given catalog, else its descriptor default.
   *
   * @param catalog Provider catalog to read `default:` from
   * @param providerName Provider name (case-insensitive)
   * @return Default model name
   */
  private[llm] def defaultFrom(catalog: Map[String, ProviderModels], providerName: String): String = {
    val normalizedProvider = providerName.toLowerCase.trim
    catalog.get(normalizedProvider).flatMap(_.default).getOrElse {
      ensureDescriptors()
      ProviderRegistry.defaultModel(normalizedProvider)
    }
  }

  /**
   * Check if a model is valid for a provider
   *
   * @param providerName Provider name (case-insensitive)
   * @param model Model name
   * @return true if the model is supported by the provider
   */
  def isValidModel(providerName: String, model: String): Boolean = {
    ensureInitialized()
    getSupportedModels(providerName).contains(model)
  }

  /**
   * Get all provider names that have models registered
   *
   * @return Set of provider names
   */
  def getAllProviders: Set[String] = {
    ensureInitialized()
    providers.keySet
  }

  /**
   * Get a user-friendly list of supported models for error messages
   *
   * @param providerName Provider name
   * @return Formatted string of models
   */
  def getModelListForDisplay(providerName: String): String = {
    ensureInitialized()
    val models = getSupportedModels(providerName).toList.sorted
    if (models.isEmpty) {
      "No models available"
    } else if (models.size <= 10) {
      models.mkString(", ")
    } else {
      // Show first 10 and indicate there are more
      val firstTen = models.take(10).mkString(", ")
      s"$firstTen, ... (${models.size} total)"
    }
  }
}

