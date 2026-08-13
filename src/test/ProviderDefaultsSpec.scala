// ABOUTME: Drift guard asserting every registered provider's defaultModel exists in the bundled registry
// ABOUTME: Prevents ProviderRegistrations.scala and models.yaml from silently falling out of sync
package org.nlogo.extensions.llm.providers

import org.scalatest.funsuite.AnyFunSuite

/**
 * Guards the invariant that each provider's advertised default model is one the
 * extension will actually accept.
 *
 * Without this, a stale default (e.g. a retired model, or an Ollama tag that is
 * not pullable) only surfaces at runtime as a stderr warning from the
 * extension's own validation -- the extension complaining about its own default.
 */
class ProviderDefaultsSpec extends AnyFunSuite {

  // Registrations are normally installed by LLMExtension.load(); do it here so
  // the suite is self-contained and order-independent.
  ProviderRegistry.reset()
  ProviderRegistrations.registerAll()

  private val descriptors = ProviderRegistry.allNames.toSeq.sorted.flatMap(ProviderRegistry.get)

  test("providers are actually registered") {
    assert(descriptors.nonEmpty, "ProviderRegistrations.registerAll() registered no providers")
  }

  test("every provider default model is present in the bundled registry") {
    val drifted = descriptors
      .filterNot(d => ModelRegistry.isValidModel(d.name, d.defaultModel))
      .map { d =>
        s"provider '${d.name}' default '${d.defaultModel}' is not in the bundled model registry. " +
          s"Known models: ${ModelRegistry.getModelListForDisplay(d.name)}"
      }

    // Build the message as a String so a failure prints the drifted defaults
    // rather than dumping whole ProviderDescriptor instances (helpText included).
    assert(drifted.isEmpty, s"\n${drifted.mkString("\n")}")
  }

  test("every provider has a non-empty model list in the bundled registry") {
    val empty = descriptors.filter(d => ModelRegistry.getSupportedModels(d.name).isEmpty).map(_.name)
    assert(empty.isEmpty, s"providers with no models in models.yaml: ${empty.mkString(", ")}")
  }

  test("every provider default model is present in the YAML-load fallback too") {
    // The check above reads the LOADED registry, so a stale entry in
    // FALLBACK_CONFIG survives it. That map is what the extension falls back on
    // when models.yaml cannot be read, and it kept retired models long after
    // they were removed from the YAML.
    val drifted = descriptors.flatMap { d =>
      ModelRegistry.FALLBACK_CONFIG.get(d.name) match {
        case None =>
          Some(s"provider '${d.name}' has no FALLBACK_CONFIG entry")
        case Some(pm) if !pm.models.contains(d.defaultModel) =>
          Some(s"provider '${d.name}' default '${d.defaultModel}' missing from FALLBACK_CONFIG " +
            s"(has: ${pm.models.toSeq.sorted.mkString(", ")})")
        case _ => None
      }
    }
    assert(drifted.isEmpty, s"\n${drifted.mkString("\n")}")
  }
}
