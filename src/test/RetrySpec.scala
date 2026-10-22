// ABOUTME: Unit tests for BaseHttpProvider retry of rate limits, 5xx/529 and transport failures
// ABOUTME: Uses an sttp BackendStub to script 429/5xx/200 responses and failed sends without real HTTP
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
  /** What each retry was waiting out, e.g. "returned HTTP 529 (overloaded)". */
  val observedCauses = new java.util.concurrent.ConcurrentLinkedQueue[String]()
  override protected def notifyRetryWait(delayMs: Long, attempt: Int, maxRetries: Int, cause: String): Unit = {
    observedDelays.add(delayMs)
    observedCauses.add(cause)
    super.notifyRetryWait(delayMs, attempt, maxRetries, cause)
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

  // Real providers read the registry for their defaults and display names; it is
  // normally installed by LLMExtension.load(). registerAll() is idempotent.
  ProviderRegistrations.registerAll()

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
    assert(ex.getMessage.startsWith("stub returned HTTP 429 (rate limited) after 4 attempts"), ex.getMessage)
    assert(counter.get() == 4)
  }

  test("default policy retries more than the old three attempts") {
    // The original 3-retry default could not outlast a per-minute quota window.
    assert(RetryPolicy.DefaultMaxRetries > 3)
  }

  test("transient 5xx errors are retried then succeed") {
    val (stub, counter) = stubReturning(serverError, serverError, ok("recovered"))
    val provider = new StubbedProvider(stub, counter)

    val result = Await.result(provider.chat(request), 5.seconds)
    assert(result.firstMessage.map(_.content).contains("recovered"))
    assert(counter.get() == 3)
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

  test("the longer of header and body wins when both are present") {
    // Body asks for longer — honour the body, not the header.
    assert(RetryPolicy.requestedDelayMs(Some("5"), geminiQuotaBody("18.5s")).contains(18500L))
    // Header asks for longer — honour the header.
    assert(RetryPolicy.requestedDelayMs(Some("30"), geminiQuotaBody("18.5s")).contains(30000L))
  }

  test("Retry-After: 0 does not mask a body-requested delay") {
    // Regression for the defect this policy exists to fix: `0` parses
    // successfully, so preferring the header discarded the body hint and
    // retried inside the quota window.
    assert(RetryPolicy.requestedDelayMs(Some("0"), geminiQuotaBody("18.5s")).contains(18500L))
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
    BaseHttpProvider.resetRetryWaitStats()
    val policy = RetryPolicy()
    // A 3s wait is above the notice threshold and should be counted.
    assert(policy.delayMsFor(0, Some(3000L), () => 0.0) >= BaseHttpProvider.WaitNoticeThresholdMs)
    assert(BaseHttpProvider.retryWaitCount == 0L, "counters start clean")
  }

  // --- Transient server and transport failures (#48, #60) ---

  private def status(code: Int, body: String = "error"): Response[StubBody] =
    ResponseStub.adjust(body, StatusCode(code))

  /** A stub whose first `failures` sends fail with `error`, then answer "ok". */
  private def stubFailingSends(failures: Int)(
    error: GenericRequest[?, ?] => Exception
  ): (BackendStub[Future], AtomicInteger) = {
    val counter = new AtomicInteger(0)
    val backend = BackendStub.asynchronousFuture.whenAnyRequest.thenRespondF { req =>
      if (counter.getAndIncrement() < failures) Future.failed(error(req))
      else Future.successful(ok("ok"))
    }
    (backend, counter)
  }

  private def connectRefused(req: GenericRequest[?, ?]): Exception =
    new SttpClientException.ConnectException(req, new java.net.ConnectException("Connection refused"))

  test("Anthropic 529 overloaded is retried then succeeds") {
    val (stub, counter) = stubReturning(status(529, """{"type":"error","error":{"type":"overloaded_error"}}"""), ok("fine"))
    val provider = new StubbedProvider(stub, counter)

    val result = Await.result(provider.chat(request), 5.seconds)
    assert(result.firstMessage.map(_.content).contains("fine"))
    assert(counter.get() == 2)
    assert(provider.observedCauses.peek() == "returned HTTP 529 (overloaded)")
  }

  test("502, 503 and 504 are retried") {
    Seq(502, 503, 504).foreach { code =>
      val (stub, counter) = stubReturning(status(code), ok("fine"))
      val provider = new StubbedProvider(stub, counter)
      Await.result(provider.chat(request), 5.seconds)
      assert(counter.get() == 2, s"HTTP $code must be retried")
    }
  }

  test("4xx other than 429 is never retried") {
    Seq(400, 401, 403, 404, 422).foreach { code =>
      val (stub, counter) = stubReturning(status(code), ok("fine"))
      val provider = new StubbedProvider(stub, counter)
      val ex = intercept[RuntimeException](Await.result(provider.chat(request), 5.seconds))
      assert(counter.get() == 1, s"HTTP $code must not be retried")
      assert(ex.getMessage.startsWith(s"stub rejected the request: HTTP $code"), ex.getMessage)
    }
  }

  test("401 names the API key setting to fix") {
    val (stub, counter) = stubReturning(status(401, """{"error":{"message":"Incorrect API key"}}"""))
    val provider = new StubbedProvider(stub, counter)

    val ex = intercept[RuntimeException](Await.result(provider.chat(request), 5.seconds))
    assert(ex.getMessage.startsWith(
      "stub rejected the request: HTTP 401 (invalid API key). " +
      "Check stub_api_key in your config or call llm:set-api-key."), ex.getMessage)
    assert(ex.getMessage.contains("Incorrect API key"), "the provider's own detail is kept")
  }

  test("exhausted 529 retries give a friendly message") {
    val (stub, counter) = stubReturning(status(529))
    val provider = new StubbedProvider(stub, counter)
    provider.setConfig(RetryPolicy.MAX_RETRIES, "3")

    val ex = intercept[RuntimeException](Await.result(provider.chat(request), 10.seconds))
    assert(ex.getMessage.startsWith(
      "stub returned HTTP 529 (overloaded) after 4 attempts. " +
      "Try again shortly, or lower concurrency with the throttling settings."), ex.getMessage)
    assert(counter.get() == 4)
  }

  test("a send that fails to connect is retried then succeeds") {
    val (stub, counter) = stubFailingSends(2)(connectRefused)
    val provider = new StubbedProvider(stub, counter)

    val result = Await.result(provider.chat(request), 5.seconds)
    assert(result.firstMessage.map(_.content).contains("ok"))
    assert(counter.get() == 3)
    assert(provider.observedCauses.peek() == "could not be reached (connection refused)")
  }

  test("read timeouts are retried then succeed") {
    val (stub, counter) = stubFailingSends(1) { req =>
      new SttpClientException.TimeoutException(req, new java.net.http.HttpTimeoutException("request timed out"))
    }
    val provider = new StubbedProvider(stub, counter)

    Await.result(provider.chat(request), 5.seconds)
    assert(counter.get() == 2)
  }

  test("timeouts nested as the cause of another exception are retried") {
    // The stub backend already maps a bare SocketTimeoutException to sttp's
    // TimeoutException, so wrap one to exercise the cause-chain check.
    val (stub, counter) = stubFailingSends(1) { _ =>
      new java.util.concurrent.CompletionException(new java.net.SocketTimeoutException("Read timed out"))
    }
    val provider = new StubbedProvider(stub, counter)

    Await.result(provider.chat(request), 5.seconds)
    assert(counter.get() == 2)
  }

  test("non-transport send failures are not retried") {
    val (stub, counter) = stubFailingSends(5)(_ => new IllegalArgumentException("bad request object"))
    val provider = new StubbedProvider(stub, counter)

    intercept[IllegalArgumentException](Await.result(provider.chat(request), 5.seconds))
    assert(counter.get() == 1)
  }

  test("an unreachable Ollama server names the URL and asks if it is running") {
    val (stub, counter) = stubFailingSends(100)(connectRefused)
    val provider = new OllamaProvider {
      override lazy val backend: Backend[Future] = stub
      override protected def retryBaseDelayMs: Long = 1L
      override protected def retryRandom: () => Double = () => 0.0
    }
    provider.setConfig(RetryPolicy.MAX_RETRIES, "1")
    provider.setConfig(org.nlogo.extensions.llm.config.ConfigStore.OLLAMA_BASE_URL, "http://localhost:11434")

    val ex = intercept[RuntimeException](Await.result(provider.chat(request), 5.seconds))
    assert(ex.getMessage == "Could not reach Ollama at http://localhost:11434 (connection refused) " +
      "after 2 attempts. Is the Ollama server running?", ex.getMessage)
    assert(counter.get() == 2)
  }

  /** What java.net.http raises for a host that does not resolve, as sttp wraps it. */
  private def unresolvedHost(req: GenericRequest[?, ?]): Exception = {
    val connect = new java.net.ConnectException()
    connect.initCause(new java.nio.channels.UnresolvedAddressException())
    new SttpClientException.ConnectException(req, connect)
  }

  test("an unknown host is reported as such and not retried") {
    val (stub, counter) = stubFailingSends(100)(unresolvedHost)
    val provider = new OllamaProvider {
      override lazy val backend: Backend[Future] = stub
      override protected def retryBaseDelayMs: Long = 1L
      override protected def retryRandom: () => Double = () => 0.0
    }
    provider.setConfig(RetryPolicy.MAX_RETRIES, "2")
    provider.setConfig(org.nlogo.extensions.llm.config.ConfigStore.OLLAMA_BASE_URL, "http://no-such-host-zz.invalid")

    val ex = intercept[RuntimeException](Await.result(provider.chat(request), 5.seconds))
    assert(ex.getMessage == "Could not reach Ollama at http://no-such-host-zz.invalid (unknown host). " +
      "Check ollama_base_url in your config for a typo.", ex.getMessage)
    assert(counter.get() == 1, "a host that does not resolve will not resolve on retry")
  }

  test("Gemini's HTTP 400 API_KEY_INVALID points at the API key setting") {
    val body = """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.",""" +
      """"status":"INVALID_ARGUMENT","details":[{"reason":"API_KEY_INVALID"}]}}"""
    val (stub, counter) = stubReturning(status(400, body))
    val provider = new StubbedProvider(stub, counter)

    val ex = intercept[RuntimeException](Await.result(provider.chat(request), 5.seconds))
    assert(ex.getMessage.startsWith(
      "stub rejected the request: HTTP 400 (invalid API key). " +
      "Check stub_api_key in your config or call llm:set-api-key."), ex.getMessage)
    assert(counter.get() == 1)
  }

  test("providers report a readable display name") {
    assert(new ClaudeProvider().displayName == "Anthropic (Claude)")
    assert(new OpenAIProvider().displayName == "OpenAI")
    assert(new OllamaProvider().displayName == "Ollama")
  }

  test("the retry wait notice names the provider, status and attempt") {
    assert(BaseHttpProvider.retryWaitNotice("Anthropic", "returned HTTP 529 (overloaded)", 2000L, 0, 3) ==
      "NOTE: Anthropic returned HTTP 529 (overloaded); retrying in 2.0s (attempt 2 of 4)")
  }
}
