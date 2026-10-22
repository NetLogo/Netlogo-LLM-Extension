// ABOUTME: Loads and merges model registry configurations from bundled YAML, the user catalog, and override files
// ABOUTME: Provides model lists and optional default models per provider, with custom model counting
package org.nlogo.extensions.llm.config

import scala.util.{Try, Success, Failure}
import scala.io.Source
import io.circe.yaml.parser
import io.circe.{ACursor, Json, HCursor}
import java.nio.file.{Files, Path, Paths}
import java.nio.charset.StandardCharsets

/**
 * Container for provider model lists
 *
 * @param models Set of model identifiers for this provider
 * @param isCustom Whether these models come from a custom/override configuration
 * @param default The provider's default model, when the YAML names one with `default:`
 */
case class ProviderModels(models: Set[String], isCustom: Boolean = false, default: Option[String] = None)

/**
 * Utility for loading and merging model registry configurations
 *
 * The model registry defines which models are available for each provider.
 * Configuration can come from, lowest layer first:
 * 1. Bundled config in JAR resources (/config/models.yaml)
 * 2. User catalog (~/.netlogo-llm/models.yaml, path overridable), merged additively
 * 3. Override config in model directory (models-override.yaml)
 */
object ModelRegistryLoader {

  /** System property naming the user catalog file (takes precedence over the env var). */
  val UserCatalogProperty: String = "llm.models.user.file"

  /** Environment variable naming the user catalog file. */
  val UserCatalogEnv: String = "NETLOGO_LLM_MODELS"

  /**
   * Where the user catalog lives: the system property, then the environment
   * variable, then ~/.netlogo-llm/models.yaml.
   */
  def userCatalogPath: Path = {
    val configured = Option(System.getProperty(UserCatalogProperty))
      .orElse(sys.env.get(UserCatalogEnv))
      .map(_.trim)
      .filter(_.nonEmpty)
    configured match {
      case Some(p) => Paths.get(p)
      case None => Paths.get(System.getProperty("user.home"), ".netlogo-llm", "models.yaml")
    }
  }

  /**
   * Load the bundled model configuration from JAR resources
   *
   * Loads /config/models.yaml from the JAR's resources directory.
   *
   * @return Try containing Map of provider name to ProviderModels
   */
  def loadBundledConfig(): Try[Map[String, ProviderModels]] = {
    Try {
      val resourceStream = getClass.getResourceAsStream("/config/models.yaml")

      if (resourceStream == null) {
        throw new IllegalArgumentException(
          "Bundled model configuration not found: /config/models.yaml must be present in JAR resources"
        )
      }

      val source = Source.fromInputStream(resourceStream)
      try {
        val content = source.mkString
        parseModelConfig(content, isCustom = false)
      } finally {
        source.close()
        resourceStream.close()
      }
    }.flatten
  }

  /**
   * Load the user catalog from a file
   *
   * The file is optional: a missing file is an empty catalog, not an error.
   * Its providers are not marked custom; it is the user's own catalog rather
   * than a per-model tweak.
   *
   * @param path User catalog file
   * @return Try containing Map of provider name to ProviderModels
   */
  def loadUserConfig(path: Path): Try[Map[String, ProviderModels]] =
    if (!Files.exists(path)) Success(Map.empty)
    else Try(Files.readString(path, StandardCharsets.UTF_8)).flatMap(parseModelConfig(_, isCustom = false))

  /**
   * Load override model configuration from a directory
   *
   * Loads models-override.yaml from the specified directory.
   * Returns both the configuration and a count of custom models.
   *
   * @param modelDir Directory containing the models-override.yaml file
   * @return Try containing tuple of (provider map, custom model count)
   */
  def loadOverrideConfig(modelDir: String): Try[(Map[String, ProviderModels], Int)] = {
    val possiblePaths = Seq(
      Paths.get(modelDir, "models-override.yaml"),
      Paths.get(modelDir, "models-override.yml")
    )

    val path = possiblePaths.find(Files.exists(_))

    path match {
      case Some(p) =>
        parseModelConfig(
          Files.readString(p, StandardCharsets.UTF_8),
          isCustom = true
        ).map { config =>
          val customCount = config.values.map(_.models.size).sum
          (config, customCount)
        }

      case None =>
        // Override file is optional - return empty config with 0 custom models
        Success((Map.empty[String, ProviderModels], 0))
    }
  }

  /**
   * Merge the user catalog over a base catalog
   *
   * Additive, unlike mergeConfigs: a user section adds its models to the
   * provider's existing list rather than replacing it, and its `default:`
   * (when present) replaces the base default. Providers only the user file
   * names are added as-is.
   *
   * @param base The bundled (or fallback) catalog
   * @param user The user catalog
   * @return Merged configuration map
   */
  def mergeUserConfig(
    base: Map[String, ProviderModels],
    user: Map[String, ProviderModels]
  ): Map[String, ProviderModels] =
    user.foldLeft(base) { case (acc, (provider, u)) =>
      acc.get(provider) match {
        case Some(b) =>
          acc.updated(provider, b.copy(models = b.models ++ u.models, default = u.default.orElse(b.default)))
        case None =>
          acc.updated(provider, u)
      }
    }

  /**
   * Merge bundled and override configurations
   *
   * Override sections completely replace bundled sections for each provider.
   * If a provider exists in override config, its bundled model list is ignored.
   * An override section without `default:` keeps the lower layer's default.
   *
   * @param bundled The bundled configuration from JAR resources
   * @param overrideConfig The override configuration from model directory
   * @return Merged configuration map
   */
  def mergeConfigs(
    bundled: Map[String, ProviderModels],
    overrideConfig: Map[String, ProviderModels]
  ): Map[String, ProviderModels] = {
    // Start with bundled config, then overlay override config
    // Override completely replaces bundled for each provider key
    bundled ++ overrideConfig.map { case (provider, o) =>
      provider -> o.copy(default = o.default.orElse(bundled.get(provider).flatMap(_.default)))
    }
  }

  /**
   * Parse YAML model configuration content
   *
   * Expected YAML structure — each provider is either a plain list or a
   * mapping with an optional `default:` and an optional `models:` list (at
   * least one of the two):
   * ```yaml
   * openai:
   *   default: gpt-6-luna
   *   models:
   *     - gpt-6-luna
   *     - gpt-4o-mini
   * anthropic:
   *   - claude-haiku-5-5
   * ```
   *
   * @param content YAML content as string
   * @param isCustom Whether this config comes from a custom/override source
   * @return Try containing Map of provider name to ProviderModels
   */
  private[llm] def parseModelConfig(content: String, isCustom: Boolean): Try[Map[String, ProviderModels]] = {
    Try {
      parser.parse(content) match {
        case Right(json) =>
          val cursor: HCursor = json.hcursor

          // Get all top-level keys (provider names)
          val providers = json.asObject.getOrElse(
            throw new RuntimeException("YAML root must be an object with provider names as keys")
          ).keys.toSeq

          // Parse each provider's model list (and default, in the mapping form)
          val config = providers.map { provider =>
            val section = cursor.downField(provider)

            def modelList(c: ACursor): Set[String] = c.as[Seq[String]] match {
              case Right(modelList) =>
                if (modelList.isEmpty) {
                  throw new RuntimeException(s"Provider '$provider' has empty model list")
                }
                modelList.toSet

              case Left(error) =>
                throw new RuntimeException(
                  s"Failed to parse model list for provider '$provider': ${error.getMessage}"
                )
            }

            val parsed = if (section.focus.exists(_.isObject)) {
              val defaultField = section.downField("default")
              val default = defaultField.focus.map { _ =>
                defaultField.as[String].toOption.map(_.trim).filter(_.nonEmpty).getOrElse(
                  throw new RuntimeException(s"Provider '$provider' default must be a model name")
                )
              }
              val modelsField = section.downField("models")
              val models = if (modelsField.focus.isDefined) modelList(modelsField) else Set.empty[String]
              if (default.isEmpty && models.isEmpty) {
                throw new RuntimeException(s"Provider '$provider' must have a models list or a default")
              }
              ProviderModels(models, isCustom, default)
            } else {
              ProviderModels(modelList(section), isCustom)
            }

            provider -> parsed
          }.toMap

          if (config.isEmpty) {
            throw new RuntimeException("Model configuration is empty - at least one provider must be defined")
          }

          config

        case Left(error) =>
          throw new RuntimeException(s"Failed to parse YAML: ${error.getMessage}")
      }
    }
  }
}
