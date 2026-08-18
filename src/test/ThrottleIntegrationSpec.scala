// ABOUTME: Integration tests for the throttle gate inside BaseHttpProvider's request path
// ABOUTME: Covers cap enforcement over real sends, retry interaction, and the unthrottled default
package org.nlogo.extensions.llm.providers

import org.nlogo.extensions.llm.models.{ChatRequest, ChatResponse, ChatMessage, Choice}
import org.scalatest.funsuite.AnyFunSuite
import sttp.client4._
import sttp.client4.testing.{BackendStub, ResponseStub}
import sttp.model.{StatusCode, Uri}
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

/**
 * Provider backed by a stub backend that holds every send open until released,
 * so requests admitted by the gate overlap and the true peak is observable. A
 * fast stub would complete before the next send began and measure a peak of 1
 * no matter how many requests were let through.
 *
 * Each instance gets a distinct base URL by default, so a test's gate is its
 * own — no shared-registry reset is needed to keep cases independent.
 */
class ProbeProvider(
  release: CountDownLatch,
  override val providerName: String = "throttle-probe",
  baseUrl: String
) extends BaseHttpProvider {

  private val running = new AtomicInteger(0)
  private val peakSeen = new AtomicInteger(0)
  val sendCount = new AtomicInteger(0)

  /** Highest number of sends in the backend at one instant. */
  def peak: Int = peakSeen.get()

  /** Sends currently inside the backend. */
  def inBackend: Int = running.get()

  override lazy val backend: Backend[Future] =
    BackendStub.asynchronousFuture.whenAnyRequest.thenRespondF { _ =>
      Future {
        sendCount.incrementAndGet()
        val n = running.incrementAndGet()
        peakSeen.updateAndGet(p => math.max(p, n))
        release.await()
        running.decrementAndGet()
        ResponseStub.adjust("ok", StatusCode.Ok)
      }
    }

  override protected def retryBaseDelayMs: Long = 10L
  override protected def retryRandom: () => Double = () => 0.0

  override def defaultModel: String = "stub-model"
  override protected def defaultBaseUrl: String = baseUrl
  override protected def baseUrlConfigKey: String = "throttle_probe_base_url"
  override protected def apiKeyConfigKey: String = "throttle_probe_api_key"
  override protected def defaultMaxTokens: String = "128"
  override protected def requiresApiKey: Boolean = false

  override protected def buildApiUrl(base: String): Uri = uri"$base/chat"
  override protected def buildHeaders(apiKey: Option[String]): Map[String, String] =
    Map("Content-Type" -> "application/json")
  override protected def createProviderRequest(request: ChatRequest): ujson.Value =
    ujson.Obj("messages" -> ujson.Arr(request.messages.map(m => ujson.Obj("content" -> m.content))*))
  override protected def parseProviderResponse(responseBody: String, model: String): ChatResponse =
    ChatResponse(
      id = "probe-response",
      created = 0L,
      model = model,
      choices = Array(Choice(0, ChatMessage.assistant(responseBody), "stop"))
    )
}

/** Provider returning a scripted sequence of responses, for the retry path. */
class ScriptedProvider(responses: Seq[Response[testing.StubBody]], baseUrl: String)
  extends BaseHttpProvider {

  val sendCount = new AtomicInteger(0)

  override lazy val backend: Backend[Future] =
    BackendStub.asynchronousFuture.whenAnyRequest.thenRespond {
      val idx = sendCount.getAndIncrement()
      responses(math.min(idx, responses.length - 1))
    }

  override protected def retryBaseDelayMs: Long = 10L
  override protected def retryRandom: () => Double = () => 0.0

  override def providerName: String = "throttle-scripted"
  override def defaultModel: String = "stub-model"
  override protected def defaultBaseUrl: String = baseUrl
  override protected def baseUrlConfigKey: String = "throttle_scripted_base_url"
  override protected def apiKeyConfigKey: String = "throttle_scripted_api_key"
  override protected def defaultMaxTokens: String = "128"
  override protected def requiresApiKey: Boolean = false

  override protected def buildApiUrl(base: String): Uri = uri"$base/chat"
  override protected def buildHeaders(apiKey: Option[String]): Map[String, String] =
    Map("Content-Type" -> "application/json")
  override protected def createProviderRequest(request: ChatRequest): ujson.Value =
    ujson.Obj("messages" -> ujson.Arr(request.messages.map(m => ujson.Obj("content" -> m.content))*))
  override protected def parseProviderResponse(responseBody: String, model: String): ChatResponse =
    ChatResponse(
      id = "scripted-response",
      created = 0L,
      model = model,
      choices = Array(Choice(0, ChatMessage.assistant(responseBody), "stop"))
    )
}

class ThrottleIntegrationSpec extends AnyFunSuite {

  private def request = ChatRequest(model = "stub-model", messages = Seq(ChatMessage.user("hi")))

  // A distinct endpoint per test, so each case gets its own gate out of the
  // shared registry without depending on a global reset between tests.
  private val urls = new AtomicInteger(0)
  private def freshUrl(): String = s"http://throttle-test-${urls.incrementAndGet()}.local"

  private def eventually(what: String)(cond: => Boolean): Unit = {
    val deadline = System.nanoTime() + 5.seconds.toNanos
    while (System.nanoTime() < deadline && !cond) Thread.sleep(2)
    assert(cond, s"timed out waiting for $what")
  }

  test("a burst of agent calls is capped and every request still completes") {
    // The defect this feature exists to fix: `ask turtles [ llm:chat-async ]`
    // fanning every agent's request out to HTTP at once.
    val release = new CountDownLatch(1)
    val provider = new ProbeProvider(release, baseUrl = freshUrl())
    provider.setConfig(RequestThrottle.MAX_CONCURRENT_REQUESTS, "2")

    val calls = (1 to 10).map(_ => provider.chat(request))
    try {
      eventually("the cap to be filled")(provider.inBackend == 2)

      // Every send blocks on the release latch, so none can have finished and let
      // a successor through unseen: with the cap filled, exactly the admitted
      // requests have been sent. An unbounded path would show all 10 here.
      assert(provider.sendCount.get() == 2,
        s"only 2 requests may reach the backend, saw ${provider.sendCount.get()}")
      assert(provider.peak == 2, s"expected at most 2 concurrent sends, saw ${provider.peak}")
    } finally {
      // Never leak blocked backend work into later tests when an assertion fails.
      release.countDown()
      Await.ready(Future.sequence(calls), 15.seconds)
    }
    assert(provider.peak <= 2, s"the cap must hold for the whole burst, saw ${provider.peak}")
    assert(provider.sendCount.get() == 10, "every deferred request must still be sent")
  }

  test("an unthrottled provider fans out exactly as before") {
    // Backward compatibility: with no throttle configured, behaviour is
    // unchanged from before this feature existed.
    val release = new CountDownLatch(1)
    val provider = new ProbeProvider(release, baseUrl = freshUrl())

    val calls = (1 to 5).map(_ => provider.chat(request))
    try {
      eventually("all five to reach the backend")(provider.inBackend == 5)
      assert(provider.peak == 5, s"unthrottled fan-out must be unbounded, saw ${provider.peak}")
    } finally {
      release.countDown()
      Await.ready(Future.sequence(calls), 15.seconds)
    }
  }

  test("a retrying request holds one permit across all its attempts") {
    // Reacquiring per attempt would let a retry re-enter behind fresh arrivals
    // and push real in-flight work above the cap. Holding one permit across the
    // whole sequence is what prevents that — and the permit must be released
    // once, at the end, or the next request never starts.
    val rateLimited = ResponseStub.adjust("rate limit exceeded", StatusCode.TooManyRequests)
    val ok = ResponseStub.adjust("recovered", StatusCode.Ok)
    val provider = new ScriptedProvider(Seq(rateLimited, rateLimited, ok), freshUrl())
    provider.setConfig(RequestThrottle.MAX_CONCURRENT_REQUESTS, "1")

    val result = Await.result(provider.chat(request), 15.seconds)
    assert(result.firstMessage.map(_.content).contains("recovered"))
    assert(provider.sendCount.get() == 3, "retry must still happen under the throttle")

    // With a cap of 1, a permit leaked across the retries would hang this.
    val next = Await.result(provider.chat(request), 15.seconds)
    assert(next.firstMessage.map(_.content).contains("recovered"))
  }

  test("a request that exhausts its retries releases its permit") {
    val rateLimited = ResponseStub.adjust("rate limit exceeded", StatusCode.TooManyRequests)
    val provider = new ScriptedProvider(Seq(rateLimited), freshUrl())
    provider.setConfig(RequestThrottle.MAX_CONCURRENT_REQUESTS, "1")
    provider.setConfig(RetryPolicy.MAX_RETRIES, "1")

    intercept[RuntimeException](Await.result(provider.chat(request), 15.seconds))

    // The gate must still admit work; a leak here would look like a hang.
    intercept[RuntimeException](Await.result(provider.chat(request), 15.seconds))
    assert(provider.sendCount.get() == 4, "two attempts per call, both calls admitted")
  }
}
