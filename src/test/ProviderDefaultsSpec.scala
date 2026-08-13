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
}
