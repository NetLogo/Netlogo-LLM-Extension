// ABOUTME: Unit tests for BaseHttpProvider rate-limit retry with exponential backoff
// ABOUTME: Uses an sttp BackendStub to script 429/200/500 responses without real HTTP
package org.nlogo.extensions.llm.providers

import org.nlogo.extensions.llm.models.{ChatRequest, ChatResponse, ChatMessage, Choice}
import org.scalatest.funsuite.AnyFunSuite
import sttp.client4._
import sttp.client4.testing.{BackendStub, ResponseStub, StubBody}
import sttp.model.{Header, StatusCode, Uri}
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

/**
 * Minimal concrete provider whose backend is a scripted stub. retryBaseDelayMs
 * is shrunk to 10ms so the exponential backoff runs fast in tests.
 */
class StubbedProvider(stub: BackendStub[Future], counter: AtomicInteger) extends BaseHttpProvider {
  override lazy val backend: Backend[Future] = stub
  override protected def retryBaseDelayMs: Long = 10L

  override def providerName: String = "stub"
  override def defaultModel: String = "stub-model"
  override protected def defaultBaseUrl: String = "http://stub.local"
  override protected def baseUrlConfigKey: String = "stub_base_url"
  override protected def apiKeyConfigKey: String = "stub_api_key"
  override protected def defaultMaxTokens: String = "128"
  override protected def requiresApiKey: Boolean = false

  override protected def buildApiUrl(baseUrl: String): Uri = uri"$baseUrl/chat"
  override protected def buildHeaders(apiKey: Option[String]): Map[String, String] =
    Map("Content-Type" -> "application/json")
  override protected def createProviderRequest(request: ChatRequest): ujson.Value =
    ujson.Obj("messages" -> ujson.Arr(request.messages.map(m => ujson.Obj("content" -> m.content))*))
  override protected def parseProviderResponse(responseBody: String, model: String): ChatResponse =
    ChatResponse(
      id = "stub-response",
      created = 0L,
      model = model,
      choices = Array(Choice(0, ChatMessage.assistant(responseBody), "stop"))
    )

  def sendCount: Int = counter.get()
}

class RetrySpec extends AnyFunSuite {

  private def request = ChatRequest(model = "stub-model", messages = Seq(ChatMessage.user("hi")))

  /** Build a stub that returns the scripted responses in order, counting sends. */
  private def stubReturning(responses: Response[StubBody]*): (BackendStub[Future], AtomicInteger) = {
    val counter = new AtomicInteger(0)
    val backend = BackendStub.asynchronousFuture.whenAnyRequest.thenRespond {
      val idx = counter.getAndIncrement()
      responses(math.min(idx, responses.length - 1))
    }
    (backend, counter)
  }

  private def rateLimited(retryAfter: Option[String] = None): Response[StubBody] = {
    val headers = retryAfter.map(v => Seq(Header("Retry-After", v))).getOrElse(Seq.empty)
    ResponseStub.adjust("rate limit exceeded", StatusCode.TooManyRequests, headers)
  }
  private def ok(body: String): Response[StubBody] =
    ResponseStub.adjust(body, StatusCode.Ok)
  private def serverError: Response[StubBody] =
    ResponseStub.adjust("boom", StatusCode.InternalServerError)

  test("retries 429 responses then succeeds") {
    val (stub, counter) = stubReturning(rateLimited(), rateLimited(), ok("hello"))
    val provider = new StubbedProvider(stub, counter)

    val result = Await.result(provider.chat(request), 5.seconds)
    assert(result.firstMessage.map(_.content).contains("hello"))
    assert(counter.get() == 3)
  }

  test("fails after exhausting retries on persistent 429") {
    val (stub, counter) = stubReturning(rateLimited(), rateLimited(), rateLimited(), rateLimited())
    val provider = new StubbedProvider(stub, counter)

    val ex = intercept[RuntimeException](Await.result(provider.chat(request), 10.seconds))
    assert(ex.getMessage.contains("after 4 attempts"))
    assert(ex.getMessage.contains("rate limited"))
    assert(counter.get() == 4)
  }

  test("non-rate-limit errors fail immediately without retry") {
    val (stub, counter) = stubReturning(serverError)
    val provider = new StubbedProvider(stub, counter)

    intercept[RuntimeException](Await.result(provider.chat(request), 5.seconds))
    assert(counter.get() == 1)
  }

  test("honors Retry-After header and still succeeds") {
    val (stub, counter) = stubReturning(rateLimited(retryAfter = Some("0")), ok("done"))
    val provider = new StubbedProvider(stub, counter)

    val result = Await.result(provider.chat(request), 5.seconds)
    assert(result.firstMessage.map(_.content).contains("done"))
    assert(counter.get() == 2)
  }
}
