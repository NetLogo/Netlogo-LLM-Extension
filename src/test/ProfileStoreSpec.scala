// ABOUTME: Deterministic tests for ProfileStore, the named-configuration registry behind per-agent profiles
// ABOUTME: Asserts load/replace semantics, reserved names, and that each profile caches exactly one provider
package org.nlogo.extensions.llm.config

import org.nlogo.extensions.llm.providers.{DeterministicTestProvider, LLMProvider}
import org.scalatest.funsuite.AnyFunSuite
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success, Try}

class ProfileStoreSpec extends AnyFunSuite {

  /** A store whose provider factory counts calls and records the model it was built with. */
  private def storeWithCounter(): (ProfileStore, AtomicInteger) = {
    val created = new AtomicInteger(0)
    val store = new ProfileStore({ config =>
      created.incrementAndGet()
      val provider = new DeterministicTestProvider()
      config.toMap.foreach { case (k, v) => provider.setConfig(k, v) }
      Success(provider)
    })
    (store, created)
  }

  private val configA = Map("provider" -> "openai", "openai_api_key" -> "k", "model" -> "model-a")
  private val configB = Map("provider" -> "openai", "openai_api_key" -> "k", "model" -> "model-b")

  test("a freshly created store has no profiles") {
    val (store, _) = storeWithCounter()
    assert(store.names.isEmpty)
    assert(store.get("a").isEmpty)
  }

  test("load registers a profile under its name with its own config") {
    val (store, _) = storeWithCounter()
    store.load("a", configA)
    assert(store.names == Seq("a"))
    assert(store.get("a").map(_.config.get("model")).contains(Some("model-a")))
  }

  test("names are reported sorted") {
    val (store, _) = storeWithCounter()
    store.load("zeta", configA)
    store.load("alpha", configB)
    assert(store.names == Seq("alpha", "zeta"))
  }

  test("the reserved default name cannot be loaded") {
    val (store, _) = storeWithCounter()
    val err = intercept[IllegalArgumentException](store.load(ProfileStore.DefaultName, configA))
    assert(err.getMessage.contains("reserved"))
    assert(store.names.isEmpty)
  }

  test("a blank name cannot be loaded") {
    val (store, _) = storeWithCounter()
    intercept[IllegalArgumentException](store.load("   ", configA))
    assert(store.names.isEmpty)
  }

  test("names are matched case-insensitively and trimmed") {
    val (store, _) = storeWithCounter()
    store.load("Fast", configA)
    assert(store.get(" fast ").isDefined)
    assert(store.names == Seq("fast"))
  }

  test("provider is created once per profile and then reused") {
    val (store, created) = storeWithCounter()
    store.load("a", configA)
    val first = store.provider("a")
    val second = store.provider("a")
    assert(first.isSuccess)
    assert(first.get eq second.get)
    assert(created.get() == 1)
  }

  test("reloading a name replaces its config and drops the cached provider") {
    val (store, created) = storeWithCounter()
    store.load("a", configA)
    val before = store.provider("a").get
    store.load("a", configB)
    val after = store.provider("a").get
    assert(!(before eq after))
    assert(created.get() == 2)
    assert(after.getConfig("model").contains("model-b"))
    assert(store.names == Seq("a"))
  }

  test("provider for an unknown name is a failure that lists what is loaded") {
    val (store, _) = storeWithCounter()
    store.load("a", configA)
    store.provider("nope") match {
      case Failure(e) =>
        assert(e.getMessage.contains("nope"))
        assert(e.getMessage.contains("a"))
      case Success(_) => fail("expected a failure for an unknown profile")
    }
  }

  test("a provider factory failure is surfaced and not cached") {
    var attempts = 0
    val store = new ProfileStore({ _ =>
      attempts += 1
      if (attempts == 1) Failure(new RuntimeException("boom")) else Success(new DeterministicTestProvider())
    })
    store.load("a", configA)
    assert(store.provider("a").isFailure)
    assert(store.provider("a").isSuccess, "a later attempt should retry rather than cache the failure")
  }

  test("concurrent first use of a profile still creates exactly one provider") {
    val (store, created) = storeWithCounter()
    store.load("a", configA)
    val threads = (1 to 8).map { _ =>
      new Thread(() => { store.provider("a"); () })
    }
    threads.foreach(_.start())
    threads.foreach(_.join())
    assert(created.get() == 1)
  }
}
