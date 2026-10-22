// ABOUTME: Tests that the legacy generic base_url is honored, and scoped, consistently across providers
// ABOUTME: Uses sttp BackendStub to capture the URL each provider actually sends to, with no real HTTP
package org.nlogo.extensions.llm.providers

import org.nlogo.extensions.llm.LLMExtension
import org.nlogo.extensions.llm.config.ConfigStore
import org.nlogo.extensions.llm.models.{ChatMessage, ChatRequest}
import org.scalatest.funsuite.AnyFunSuite
import sttp.client4._
import sttp.client4.testing.{BackendStub, ResponseStub}
import sttp.model.StatusCode
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.Try

/** A stub backend that records every request URL and answers 200 with `body`. */
class UrlCapture(body: String) {
  val urls = new ConcurrentLinkedQueue[String]()
  val backend: BackendStub[Future] = BackendStub.asynchronousFuture
    .whenRequestMatches { req => urls.add(req.uri.toString); true }
    .thenRespond(ResponseStub.adjust(body, StatusCode.Ok))
  def sent: List[String] = urls.asScala.toList
}

class UrlCapturingOpenAI(capture: UrlCapture) extends OpenAIProvider()(using global) {
  override lazy val backend: Backend[Future] = capture.backend
}

class UrlCapturingClaude(capture: UrlCapture) extends ClaudeProvider()(using global) {
  override lazy val backend: Backend[Future] = capture.backend
}

class UrlCapturingGemini(capture: UrlCapture) extends GeminiProvider()(using global) {
  override lazy val backend: Backend[Future] = capture.backend
}

class UrlCapturingOllama(capture: UrlCapture) extends OllamaProvider()(using global) {
  override lazy val backend: Backend[Future] = capture.backend
}

class BaseUrlResolutionSpec extends AnyFunSuite {

  ProviderRegistrations.registerAll()

  private def request(model: String) = ChatRequest(model = model, messages = Seq(ChatMessage.user("hi")))

  /** Mirrors ProviderFactory.createProviderFromConfig: every key is copied in. */
  private def configure(provider: BaseHttpProvider, config: ConfigStore): provider.type = {
    config.toMap.foreach { case (k, v) => provider.setConfig(k, v) }
    provider
  }

  private def send(provider: BaseHttpProvider, model: String): Unit =
    Try(Await.result(provider.chat(request(model)), 5.seconds))

  test("OpenAI with only base_url set sends to that base_url") {
    val capture = new UrlCapture("{}")
    val provider = new UrlCapturingOpenAI(capture)
    provider.setConfig(ConfigStore.API_KEY, "sk-test")
    provider.setConfig(ConfigStore.BASE_URL, "http://x")
    send(provider, "gpt-4o-mini")
    assert(capture.sent == List("http://x/chat/completions"))
  }

  test("provider-specific base URL wins over the generic base_url") {
    val capture = new UrlCapture("{}")
    val provider = new UrlCapturingOpenAI(capture)
    provider.setConfig(ConfigStore.API_KEY, "sk-test")
    provider.setConfig(ConfigStore.BASE_URL, "http://generic")
    provider.setConfig(ConfigStore.OPENAI_BASE_URL, "http://specific")
    send(provider, "gpt-4o-mini")
    assert(capture.sent == List("http://specific/chat/completions"))
  }

  test("Gemini with only base_url set sends to that base_url") {
    val capture = new UrlCapture("{}")
    val provider = new UrlCapturingGemini(capture)
    provider.setConfig(ConfigStore.API_KEY, "k")
    provider.setConfig(ConfigStore.BASE_URL, "http://g")
    send(provider, "gemini-2.5-flash")
    assert(capture.sent.headOption.exists(_.startsWith("http://g/models/gemini-2.5-flash:generateContent")))
  }

  test("a fresh extension config does not seed a generic base_url") {
    assert(ConfigStore.withDefaults().get(ConfigStore.BASE_URL).isEmpty)
  }

  test("Anthropic with no base_url hits the Anthropic default after an OpenAI-only session") {
    val config = ConfigStore.withDefaults()
    ProviderFactory.applyProviderSwitch(config, "openai")
    ProviderFactory.applyProviderSwitch(config, "anthropic")
    config.set(ConfigStore.API_KEY, "sk-ant-test")

    val capture = new UrlCapture("{}")
    send(configure(new UrlCapturingClaude(capture), config), "claude-sonnet-4-5")
    assert(capture.sent == List(s"${ConfigStore.DEFAULT_ANTHROPIC_BASE_URL}/messages"))
  }

  test("a base_url set while OpenAI is active does not leak to Anthropic after set-provider") {
    val config = ConfigStore.withDefaults()
    ProviderFactory.applyProviderSwitch(config, "openai")
    config.set(ConfigStore.BASE_URL, "http://openai-proxy")
    ProviderFactory.applyProviderSwitch(config, "anthropic")
    config.set(ConfigStore.API_KEY, "sk-ant-test")

    val capture = new UrlCapture("{}")
    send(configure(new UrlCapturingClaude(capture), config), "claude-sonnet-4-5")
    assert(capture.sent == List(s"${ConfigStore.DEFAULT_ANTHROPIC_BASE_URL}/messages"))

    // ...and switching back to OpenAI keeps the URL the user set for it.
    ProviderFactory.applyProviderSwitch(config, "openai")
    val openAiCapture = new UrlCapture("{}")
    send(configure(new UrlCapturingOpenAI(openAiCapture), config), "gpt-4o-mini")
    assert(openAiCapture.sent == List("http://openai-proxy/chat/completions"))
  }

  test("Ollama with only base_url set probes that host") {
    val config = new ConfigStore()
    config.set(ConfigStore.BASE_URL, "http://ollama-host:1234")
    val capture = new UrlCapture("""{"models":[]}""")
    assert(LLMExtension.probeOllama(config, new UrlCapturingOllama(capture)))
    assert(capture.sent == List("http://ollama-host:1234/api/tags"))
  }

  test("Ollama with no base URL configured probes the default host") {
    val capture = new UrlCapture("""{"models":[]}""")
    assert(LLMExtension.probeOllama(new ConfigStore(), new UrlCapturingOllama(capture)))
    assert(capture.sent == List(s"${ConfigStore.DEFAULT_OLLAMA_BASE_URL}/api/tags"))
  }

  test("Ollama chat and model listing honor the generic base_url") {
    val capture = new UrlCapture("""{"models":[]}""")
    val provider = new UrlCapturingOllama(capture)
    provider.setConfig(ConfigStore.BASE_URL, "http://ollama-host:1234")
    Await.result(provider.listInstalledModels(), 5.seconds)
    send(provider, "llama3.2")
    assert(capture.sent == List("http://ollama-host:1234/api/tags", "http://ollama-host:1234/api/chat"))
  }
}
