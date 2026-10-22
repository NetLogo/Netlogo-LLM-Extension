// ABOUTME: Deterministic tests for the YAML model catalog: per-provider defaults, the user catalog file, and layering
// ABOUTME: Also pins the refreshed bundled defaults and the request shape their APIs require (#64)
package org.nlogo.extensions.llm.providers

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.Comparator

import scala.concurrent.ExecutionContext.Implicits.global

import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

import org.nlogo.extensions.llm.config.{ModelRegistryLoader, ProviderModels}
import org.nlogo.extensions.llm.models.{ChatMessage, ChatRequest, ThinkingConfig}

private class CatalogInspectableGemini extends GeminiProvider {
  def body(r: ChatRequest): ujson.Value = createProviderRequest(r)
}

class ModelCatalogSpec extends AnyFunSuite with BeforeAndAfterEach with RegistryLock {

  // registerAll() is idempotent; no reset, which would race other suites.
  ProviderRegistrations.registerAll()

  // Provider keys no other suite uses, so the user catalog and overrides loaded
  // here never change what a parallel suite reads for a real provider.
  private val SpecProvider = "model-catalog-spec-provider"
  private val AddedOpenAiModel = "model-catalog-spec-added-model"

  private var dir: Path = scala.compiletime.uninitialized
  private var savedProperty: Option[String] = None

  override def beforeEach(): Unit = {
    dir = Files.createTempDirectory("model-catalog")
    savedProperty = Option(System.getProperty(ModelRegistryLoader.UserCatalogProperty))
  }

  override def afterEach(): Unit = {
    savedProperty match {
      case Some(v) => System.setProperty(ModelRegistryLoader.UserCatalogProperty, v)
      case None => System.clearProperty(ModelRegistryLoader.UserCatalogProperty)
    }
    ModelRegistry.clearOverride()
    ModelRegistry.reload()
    Files.walk(dir).sorted(Comparator.reverseOrder[Path]()).forEach(p => Files.deleteIfExists(p))
  }

  private def useUserCatalog(content: String): Path = {
    val file = dir.resolve("models.yaml")
    Files.writeString(file, content, StandardCharsets.UTF_8)
    System.setProperty(ModelRegistryLoader.UserCatalogProperty, file.toString)
    ModelRegistry.reload()
    file
  }

  private lazy val bundled: Map[String, ProviderModels] = ModelRegistryLoader.loadBundledConfig().get

  private def descriptors: Seq[ProviderDescriptor] =
    ProviderRegistry.allNames.toSeq.sorted.flatMap(ProviderRegistry.get)

  // --- default: in YAML ---

  test("the bundled catalog declares a default for every registered provider") {
    val missing = descriptors.map(_.name).filterNot(n => bundled.get(n).exists(_.default.isDefined))
    assert(missing.isEmpty, s"providers with no default: in models.yaml: ${missing.mkString(", ")}")
  }

  test("every bundled provider default is listed in its own catalog list") {
    val drifted = bundled.toSeq.sortBy(_._1).collect {
      case (name, pm) if pm.default.exists(d => !pm.models.contains(d)) =>
        s"provider '$name' default '${pm.default.get}' is not in its own list"
    }
    assert(drifted.isEmpty, s"\n${drifted.mkString("\n")}")
  }

  test("the provider default model comes from the catalog's default: key") {
    val expected = Map(
      "anthropic" -> "claude-haiku-5-5",
      "openai" -> "gpt-6-luna",
      "gemini" -> "gemini-3.5-flash-lite",
      "groq" -> "openai/gpt-oss-20b",
      "openrouter" -> "openai/gpt-oss-20b",
      "together" -> "Qwen/Qwen3.8-Flash",
      "ollama" -> "llama3.2"
    )
    expected.foreach { case (provider, model) =>
      assert(bundled(provider).default.contains(model), s"bundled default for $provider")
      assert(ModelRegistry.defaultModel(provider) == model, s"effective default for $provider")
    }
  }

  test("a provider section without default: falls back to the descriptor default") {
    val catalog = Map("openai" -> ProviderModels(Set("some-model")))
    val descriptorDefault = ProviderRegistry.get("openai").get.defaultModel
    assert(ModelRegistry.defaultFrom(catalog, "openai") == descriptorDefault)
    assert(ModelRegistry.defaultFrom(Map.empty, "anthropic") == ProviderRegistry.get("anthropic").get.defaultModel)
  }

  test("a catalog default wins over the descriptor default") {
    val catalog = Map("openai" -> ProviderModels(Set("a", "b"), default = Some("b")))
    assert(ModelRegistry.defaultFrom(catalog, "openai") == "b")
  }

  test("the parser accepts both the plain-list and the default:/models: provider shapes") {
    val parsed = ModelRegistryLoader.parseModelConfig(
      "plain:\n  - a\n  - b\nkeyed:\n  default: y\n  models:\n    - x\n    - y\n",
      isCustom = false
    ).get
    assert(parsed("plain") == ProviderModels(Set("a", "b")))
    assert(parsed("keyed") == ProviderModels(Set("x", "y"), default = Some("y")))
  }

  test("a provider section with neither models nor default is rejected") {
    assert(ModelRegistryLoader.parseModelConfig("keyed:\n  colour: blue\n", isCustom = false).isFailure)
  }

  // --- YAML-load fallback ---

  test("the YAML-load fallback is just each descriptor's default model") {
    val fallback = ModelRegistry.descriptorFallback()
    descriptors.foreach { d =>
      assert(fallback(d.name).models == Set(d.defaultModel), s"fallback for ${d.name}")
    }
  }

  test("descriptor defaults match the bundled defaults so the fallback stays current") {
    val drifted = descriptors.filterNot(d => bundled.get(d.name).flatMap(_.default).contains(d.defaultModel))
      .map(d => s"${d.name}: descriptor '${d.defaultModel}' vs YAML '${bundled.get(d.name).flatMap(_.default).getOrElse("-")}'")
    assert(drifted.isEmpty, s"\n${drifted.mkString("\n")}")
  }

  // --- user catalog file ---

  test("user catalog merge adds models, adds providers and overrides a default") {
    val user = Map(
      "openai" -> ProviderModels(Set("my-model"), default = Some("my-model")),
      "brand-new" -> ProviderModels(Set("m"))
    )
    val merged = ModelRegistryLoader.mergeUserConfig(bundled, user)
    assert(merged("openai").models.contains("my-model"))
    assert(merged("openai").models.contains("gpt-6-luna"), "user models are added, not substituted")
    assert(merged("openai").default.contains("my-model"))
    assert(merged("brand-new").models == Set("m"))
    assert(merged("anthropic") == bundled("anthropic"))
  }

  test("user catalog merge keeps the bundled default when the user file names none") {
    val merged = ModelRegistryLoader.mergeUserConfig(bundled, Map("openai" -> ProviderModels(Set("extra"))))
    assert(merged("openai").default == bundled("openai").default)
  }

  test("a user catalog file is merged over the bundled catalog at init") {
    useUserCatalog(
      s"openai:\n  - $AddedOpenAiModel\n$SpecProvider:\n  default: m1\n  models:\n    - m1\n    - m2\n"
    )
    assert(ModelRegistry.userCatalogWarning.isEmpty)
    assert(ModelRegistry.isValidModel("openai", AddedOpenAiModel))
    assert(ModelRegistry.isValidModel("openai", "gpt-6-luna"))
    assert(ModelRegistry.defaultModel("openai") == "gpt-6-luna")
    assert(ModelRegistry.isValidModel(SpecProvider, "m2"))
    assert(ModelRegistry.defaultModel(SpecProvider) == "m1")
  }

  test("a user catalog default missing from its models list is still a known model") {
    useUserCatalog(
      s"openai:\n  default: $AddedOpenAiModel\n$SpecProvider:\n  default: d1\n  models:\n    - m1\n"
    )
    assert(ModelRegistry.defaultModel("openai") == AddedOpenAiModel)
    assert(ModelRegistry.isValidModel("openai", AddedOpenAiModel))
    assert(ModelRegistry.isValidModel("openai", "gpt-6-luna"))
    assert(ModelRegistry.defaultModel(SpecProvider) == "d1")
    assert(ModelRegistry.isValidModel(SpecProvider, "d1"))
    assert(ModelRegistry.isValidModel(SpecProvider, "m1"))
  }

  test("an override default missing from its models list is still a known model") {
    Files.writeString(
      dir.resolve("models-override.yaml"),
      s"$SpecProvider:\n  default: d9\n  models:\n    - m9\n",
      StandardCharsets.UTF_8
    )
    ModelRegistry.loadOverride(dir.toString)
    assert(ModelRegistry.defaultModel(SpecProvider) == "d9")
    assert(ModelRegistry.isValidModel(SpecProvider, "d9"))
    assert(ModelRegistry.isValidModel(SpecProvider, "m9"))
  }

  test("a missing user catalog file is not an error") {
    System.setProperty(ModelRegistryLoader.UserCatalogProperty, dir.resolve("absent.yaml").toString)
    ModelRegistry.reload()
    assert(ModelRegistry.userCatalogWarning.isEmpty)
    assert(ModelRegistry.defaultModel("openai") == "gpt-6-luna")
  }

  test("a broken user catalog file is ignored with a warning") {
    val file = useUserCatalog(s"$SpecProvider: [\n")
    val warning = ModelRegistry.userCatalogWarning
    assert(warning.isDefined)
    assert(warning.get.contains(file.toString))
    assert(!ModelRegistry.getAllProviders.contains(SpecProvider))
    assert(ModelRegistry.defaultModel("openai") == "gpt-6-luna")
    assert(ModelRegistry.isValidModel("openai", "gpt-6-luna"))
  }

  test("models-override.yaml layers on top of the user catalog, and clearing it restores the user layer") {
    useUserCatalog(s"$SpecProvider:\n  default: m1\n  models:\n    - m1\n")
    Files.writeString(
      dir.resolve("models-override.yaml"),
      s"$SpecProvider:\n  default: m3\n  models:\n    - m3\n",
      StandardCharsets.UTF_8
    )
    assert(ModelRegistry.loadOverride(dir.toString).isDefined)
    assert(ModelRegistry.defaultModel(SpecProvider) == "m3")
    assert(ModelRegistry.isValidModel(SpecProvider, "m3"))

    ModelRegistry.clearOverride()
    assert(ModelRegistry.defaultModel(SpecProvider) == "m1")
    assert(!ModelRegistry.isValidModel(SpecProvider, "m3"))
  }

  test("an override list without default: keeps the lower layer's default") {
    useUserCatalog(s"$SpecProvider:\n  default: m1\n  models:\n    - m1\n")
    Files.writeString(dir.resolve("models-override.yaml"), s"$SpecProvider:\n  - m9\n", StandardCharsets.UTF_8)
    ModelRegistry.loadOverride(dir.toString)
    assert(ModelRegistry.defaultModel(SpecProvider) == "m1")
    assert(ModelRegistry.isValidModel(SpecProvider, "m9"))
  }

  test("the override in effect survives a user catalog reload") {
    Files.writeString(dir.resolve("models-override.yaml"), s"$SpecProvider:\n  - m9\n", StandardCharsets.UTF_8)
    ModelRegistry.loadOverride(dir.toString)
    useUserCatalog(s"openai:\n  - $AddedOpenAiModel\n")
    assert(ModelRegistry.isValidModel(SpecProvider, "m9"))
    assert(ModelRegistry.isValidModel("openai", AddedOpenAiModel))
  }

  // --- refreshed defaults get a request shape their API accepts ---

  test("GPT-5 and GPT-6 models get the reasoning request format, alongside the o-series") {
    Seq("gpt-6-luna", "gpt-6.1-sol", "gpt-5.6-terra", "gpt-5.4-mini", "o3", "o1").foreach { m =>
      assert(ReasoningModelDetector.requiresReasoningFormat("openai", m), m)
      assert(ReasoningModelDetector.isReasoningModel("openai", m), m)
    }
    assert(ReasoningModelDetector.requiresReasoningFormat("openrouter", "openai/gpt-6-luna"))
    assert(!ReasoningModelDetector.requiresReasoningFormat("openai", "gpt-4o-mini"))
    assert(!ReasoningModelDetector.requiresReasoningFormat("openrouter", "openai/gpt-oss-20b"))
  }

  private def geminiThinking(model: String, thinking: ThinkingConfig): ujson.Value =
    (new CatalogInspectableGemini).body(ChatRequest(
      model = model,
      messages = Seq(ChatMessage.user("hi")),
      maxTokens = Some(1000),
      thinkingConfig = Some(thinking)
    ))("generationConfig")("thinkingConfig")

  test("Gemini 3 models get thinkingLevel and never thinkingLevel plus thinkingBudget together") {
    val tc = geminiThinking("gemini-3.5-flash-lite",
      ThinkingConfig(enabled = true, reasoningEffort = Some("low"), budgetTokens = Some(2048)))
    assert(tc("thinkingLevel").str == "LOW")
    assert(!tc.obj.contains("thinkingBudget"))
  }

  test("Gemini 3 maps the extension's none and xhigh efforts onto levels it accepts") {
    assert(geminiThinking("gemini-3.8-flash", ThinkingConfig(enabled = true, reasoningEffort = Some("none")))("thinkingLevel").str == "MINIMAL")
    assert(geminiThinking("gemini-4-argon", ThinkingConfig(enabled = true, reasoningEffort = Some("xhigh")))("thinkingLevel").str == "HIGH")
  }

  test("Gemini 3 still accepts a budget when no effort is set") {
    val tc = geminiThinking("gemini-3.5-flash-lite", ThinkingConfig(enabled = true, budgetTokens = Some(2048)))
    assert(tc("thinkingBudget").num == 2048)
    assert(!tc.obj.contains("thinkingLevel"))
  }

  test("Gemini 2.5 models get thinkingBudget and no thinkingLevel") {
    val tc = geminiThinking("gemini-2.5-flash",
      ThinkingConfig(enabled = true, reasoningEffort = Some("low"), budgetTokens = Some(2048)))
    assert(tc("thinkingBudget").num == 2048)
    assert(!tc.obj.contains("thinkingLevel"))
  }
}
