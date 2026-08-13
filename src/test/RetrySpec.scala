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

  // Jitter is randomized in production; tests pin it so timings are deterministic.
  // `jitter` is the fraction of the jitter range to apply (0.0 = none, 1.0 = max).
  @volatile var jitter: Double = 0.0
  override protected def retryRandom: () => Double = () => jitter

  /** Delays actually slept, in order, so tests can assert on backoff behavior. */
  val observedDelays = new java.util.concurrent.ConcurrentLinkedQueue[Long]()
  override protected def notifyRateLimitWait(delayMs: Long, attempt: Int, maxRetries: Int): Unit = {
    observedDelays.add(delayMs)
    super.notifyRateLimitWait(delayMs, attempt, maxRetries)
  }
  def delays: List[Long] = observedDelays.toArray(Array.empty[java.lang.Long]).map(_.toLong).toList

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

  /** A 429 whose body carries the retry hint, with no Retry-After header. */
  private def rateLimitedBody(body: String): Response[StubBody] =
    ResponseStub.adjust(body, StatusCode.TooManyRequests, Seq.empty)

  /** The real shape Gemini returns on a free-tier quota breach. */
  private def geminiQuotaBody(retryDelay: String): String =
    ujson.Obj(
      "error" -> ujson.Obj(
        "code" -> 429,
        "message" -> "You exceeded your current quota. Please retry shortly.",
        "status" -> "RESOURCE_EXHAUSTED",
        "details" -> ujson.Arr(
          ujson.Obj(
            "@type" -> "type.googleapis.com/google.rpc.QuotaFailure",
            "violations" -> ujson.Arr(ujson.Obj("quotaMetric" -> "generate_requests_per_model"))
          ),
          ujson.Obj(
            "@type" -> "type.googleapis.com/google.rpc.RetryInfo",
            "retryDelay" -> retryDelay
          )
        )
      )
    ).toString()
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
    // Pinned rather than relying on the default, so this test asserts exhaustion
    // behavior and not whatever the default retry count happens to be.
    provider.setConfig(RetryPolicy.MAX_RETRIES, "3")

    val ex = intercept[RuntimeException](Await.result(provider.chat(request), 10.seconds))
    assert(ex.getMessage.contains("after 4 attempts"))
    assert(ex.getMessage.contains("rate limited"))
    assert(counter.get() == 4)
  }

  test("default policy retries more than the old three attempts") {
    // The original 3-retry default could not outlast a per-minute quota window.
    assert(RetryPolicy.DefaultMaxRetries > 3)
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

  // --- Provider-requested delay parsed from the response BODY ---

  test("parses Gemini RetryInfo retryDelay from the response body") {
    // 0s keeps the test fast while still exercising the body-parsing path.
    val (stub, counter) = stubReturning(rateLimitedBody(geminiQuotaBody("0s")), ok("recovered"))
    val provider = new StubbedProvider(stub, counter)

    val result = Await.result(provider.chat(request), 5.seconds)
    assert(result.firstMessage.map(_.content).contains("recovered"))
    assert(counter.get() == 2)
  }

  test("body-parsed delay is used when no Retry-After header is present") {
    // Gemini's real 18.5s ask, parsed out of error.details[].RetryInfo.retryDelay.
    val delay = RetryPolicy.requestedDelayMs(None, geminiQuotaBody("18.5s"))
    assert(delay.contains(18500L))
  }

  test("falls back to prose in error.message when RetryInfo is absent") {
    val body = ujson.Obj(
      "error" -> ujson.Obj("message" -> "Quota exceeded, please retry in 24s.")
    ).toString()
    assert(RetryPolicy.requestedDelayMs(None, body).contains(24000L))
  }

  test("Retry-After header wins over the body when both are present") {
    val delay = RetryPolicy.requestedDelayMs(Some("5"), geminiQuotaBody("18.5s"))
    assert(delay.contains(5000L))
  }

  test("malformed bodies never throw and yield no delay") {
    assert(RetryPolicy.requestedDelayMs(None, "not json at all").isEmpty)
    assert(RetryPolicy.requestedDelayMs(None, "").isEmpty)
    assert(RetryPolicy.requestedDelayMs(None, "{}").isEmpty)
    assert(RetryPolicy.requestedDelayMs(Some("bogus"), "{}").isEmpty)
  }

  test("parses protobuf-style duration units") {
    assert(RetryPolicy.parseDuration("18.5s").contains(18500L))
    assert(RetryPolicy.parseDuration("500ms").contains(500L))
    assert(RetryPolicy.parseDuration("2m").contains(120000L))
    assert(RetryPolicy.parseDuration("7").contains(7000L)) // bare == seconds
    assert(RetryPolicy.parseDuration("garbage").isEmpty)
  }

  // --- The old 10s cap, which made quota windows unclearable ---

  test("honors a requested delay longer than the old 10s cap") {
    val policy = RetryPolicy()
    // 18.5s is what Google actually asks for; the old MaxDelayMs truncated it to
    // 10s, so every retry fired inside the window and was guaranteed to fail.
    val delay = policy.delayMsFor(0, Some(18500L), () => 0.0)
    assert(delay == 18500L, s"expected the full 18.5s wait, got $delay ms")
    assert(delay > 10000L, "delay must exceed the old 10s ceiling")
  }

  test("a single delay is still capped at maxDelay") {
    val policy = RetryPolicy()
    val delay = policy.delayMsFor(0, Some(10.minutes.toMillis), () => 0.0)
    assert(delay == policy.maxDelay.toMillis)
  }

  // --- Total budget vs. a per-minute quota window ---

  test("default retry budget can wait out a per-minute quota window") {
    val policy = RetryPolicy()
    // Simulate the schedule without sleeping: accumulate the delays the policy
    // would produce until it refuses to retry further.
    var elapsed = 0L
    var attempt = 0
    var continue = true
    while (continue) {
      val d = policy.delayMsFor(attempt, None, () => 0.0)
      if (policy.canRetry(attempt, elapsed, d)) {
        elapsed += d
        attempt += 1
      } else continue = false
    }
    assert(elapsed >= 60000L, s"total retry budget only reached ${elapsed}ms, cannot clear an RPM window")
    assert(attempt >= 5, s"expected several retries, got $attempt")
  }

  test("old 3-retry/1s-base schedule could not clear a per-minute window") {
    // Regression guard documenting the original defect: ~7s of total waiting.
    val old = RetryPolicy(maxRetries = 3, baseDelay = 1.second, maxDelay = 10.seconds,
                          maxElapsed = 1.hour, jitterFactor = 0.0)
    val total = (0 until old.maxRetries).map(n => old.delayMsFor(n, None, () => 0.0)).sum
    assert(total < 10000L, s"old schedule waited ${total}ms total")
  }

  test("stops retrying once the elapsed budget is exhausted") {
    val policy = RetryPolicy(maxRetries = 100, baseDelay = 1.second, maxElapsed = 5.seconds)
    assert(policy.canRetry(0, 0L, 1000L))
    assert(!policy.canRetry(0, 4000L, 2000L), "must refuse a wait that overruns the budget")
  }

  test("gives actionable guidance when the budget is exhausted") {
    val (stub, counter) = stubReturning(rateLimited(), rateLimited(), rateLimited(), rateLimited())
    val provider = new StubbedProvider(stub, counter)
    provider.setConfig(RetryPolicy.MAX_RETRIES, "2")

    val ex = intercept[RuntimeException](Await.result(provider.chat(request), 10.seconds))
    assert(ex.getMessage.contains("rate limited"))
    assert(ex.getMessage.contains("quota may be too low"))
    assert(counter.get() == 3, "1 initial attempt + 2 retries")
  }

  // --- Jitter ---

  test("jitter spreads delays instead of retrying in lockstep") {
    val policy = RetryPolicy(baseDelay = 1.second, jitterFactor = 0.25)
    // Same attempt number, different random draws => different delays. Without
    // jitter every rate-limited agent would retry on the same millisecond.
    val low = policy.delayMsFor(0, None, () => 0.0)
    val high = policy.delayMsFor(0, None, () => 1.0)
    assert(low != high, "jitter must vary the delay across agents")
    assert(high > low)
    assert(low >= 1000L, "jitter must never shorten the base delay")
    assert(high <= 1250L, s"jitter must stay within the configured factor, got $high")
  }

  test("jitter never shortens a provider-requested delay") {
    val policy = RetryPolicy(jitterFactor = 0.25)
    // Retrying before the quota window reopens is guaranteed to fail, so jitter
    // is additive only.
    Seq(0.0, 0.5, 1.0).foreach { r =>
      assert(policy.delayMsFor(0, Some(18500L), () => r) >= 18500L)
    }
  }

  test("zero jitter factor is deterministic") {
    val policy = RetryPolicy(jitterFactor = 0.0)
    assert(policy.delayMsFor(1, None, () => 0.0) == policy.delayMsFor(1, None, () => 1.0))
  }

  // --- Config plumbing ---

  test("retry count is configurable") {
    val (stub, counter) = stubReturning(rateLimited(), ok("fine"))
    val provider = new StubbedProvider(stub, counter)
    provider.setConfig(RetryPolicy.MAX_RETRIES, "0")

    intercept[RuntimeException](Await.result(provider.chat(request), 5.seconds))
    assert(counter.get() == 1, "maxRetries=0 must not retry at all")
  }

  test("backoff grows exponentially across attempts") {
    val policy = RetryPolicy(baseDelay = 1.second, jitterFactor = 0.0)
    val schedule = (0 until 5).map(policy.backoffMs)
    assert(schedule == Seq(1000L, 2000L, 4000L, 8000L, 16000L), s"got $schedule")
  }

  test("backoff does not overflow at large attempt numbers") {
    val policy = RetryPolicy(baseDelay = 1.second)
    assert(policy.backoffMs(1000) == policy.maxDelay.toMillis)
    assert(policy.backoffMs(1000) > 0L)
  }

  test("long waits are recorded so a stall is distinguishable from a hang") {
    BaseHttpProvider.resetRateLimitStats()
    val policy = RetryPolicy()
    // A 3s wait is above the notice threshold and should be counted.
    assert(policy.delayMsFor(0, Some(3000L), () => 0.0) >= BaseHttpProvider.WaitNoticeThresholdMs)
    assert(BaseHttpProvider.rateLimitWaitCount == 0L, "counters start clean")
  }
}
