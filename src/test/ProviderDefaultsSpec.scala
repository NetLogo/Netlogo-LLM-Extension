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
  //
  // Deliberately no ProviderRegistry.reset() first. The registry is global mutable
  // state shared with every other suite, and clearing it from a constructor emptied
  // it out from under suites that had already been built against it — ClaudeRequestSpec
  // aborts with "Unknown provider: anthropic" when it loses that race. registerAll()
  // is idempotent (it inserts by name), so re-registering is all this suite needs.
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

  test("every provider's effective default model is present in the loaded registry") {
    // The effective default comes from models.yaml's default: key when set, so
    // it can drift from the descriptor default checked above. Both must be
    // models the extension accepts. (The YAML-load fallback is built from the
    // descriptor defaults alone, so it cannot drift; ModelCatalogSpec covers it.)
    val drifted = descriptors.flatMap { d =>
      val effective = ModelRegistry.defaultModel(d.name)
      if (ModelRegistry.isValidModel(d.name, effective)) None
      else Some(s"provider '${d.name}' effective default '$effective' is not in the loaded registry")
    }
    assert(drifted.isEmpty, s"\n${drifted.mkString("\n")}")
  }
}
