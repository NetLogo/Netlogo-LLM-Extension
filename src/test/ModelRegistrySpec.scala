// ABOUTME: Deterministic tests for ModelRegistry override loading across model directories
// ABOUTME: Asserts that a models-override.yaml from one directory does not leak into later loads (#29)
package org.nlogo.extensions.llm.providers

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.Comparator

import org.scalatest.BeforeAndAfterEach
import org.scalatest.funsuite.AnyFunSuite

class ModelRegistrySpec extends AnyFunSuite with BeforeAndAfterEach with RegistryLock {

  // A provider key no other spec uses, so suites running in parallel against the
  // shared registry never see this override touch their providers.
  private val CustomProvider = "model-registry-spec-provider"
  private val CustomModel = "my-custom-leak-model"

  private var dirA: Path = scala.compiletime.uninitialized
  private var dirB: Path = scala.compiletime.uninitialized

  override def beforeEach(): Unit = {
    // clearOverride rather than reset: reset briefly empties the shared registry,
    // which would race with other suites reading it in parallel.
    ModelRegistry.clearOverride()
    dirA = Files.createTempDirectory("model-registry-a")
    dirB = Files.createTempDirectory("model-registry-b")
    Files.writeString(
      dirA.resolve("models-override.yaml"),
      s"$CustomProvider:\n  - $CustomModel\n",
      StandardCharsets.UTF_8
    )
  }

  override def afterEach(): Unit = {
    ModelRegistry.clearOverride()
    Seq(dirA, dirB).foreach { dir =>
      Files.walk(dir).sorted(Comparator.reverseOrder[Path]()).forEach(p => Files.deleteIfExists(p))
    }
  }

  private def assertNoCustomModel(): Unit = {
    assert(!ModelRegistry.isValidModel(CustomProvider, CustomModel))
    val listing = ModelRegistry.formatModelList("openai", "gpt-4o-mini")
    assert(!listing.contains(CustomProvider))
    assert(!listing.contains(CustomModel))
    assert(!listing.contains("[custom]"))
  }

  test("override from directory A is visible after loading A") {
    assert(ModelRegistry.loadOverride(dirA.toString).isDefined)
    assert(ModelRegistry.isValidModel(CustomProvider, CustomModel))
    assert(ModelRegistry.formatModelList("openai", "gpt-4o-mini").contains("[custom]"))
  }

  test("loading a directory without an override drops the previous override") {
    ModelRegistry.loadOverride(dirA.toString)
    assert(ModelRegistry.loadOverride(dirB.toString).isEmpty)
    assertNoCustomModel()
  }

  test("a broken override file drops the previous override") {
    ModelRegistry.loadOverride(dirA.toString)
    Files.writeString(dirB.resolve("models-override.yaml"), s"$CustomProvider: []\n", StandardCharsets.UTF_8)
    assert(ModelRegistry.loadOverride(dirB.toString).isEmpty)
    assertNoCustomModel()
  }

  test("clearOverride restores the bundled catalog") {
    ModelRegistry.loadOverride(dirA.toString)
    ModelRegistry.clearOverride()
    assertNoCustomModel()
    assert(ModelRegistry.isValidModel("openai", "gpt-4o-mini"))
  }
}
