// ABOUTME: Unit tests for RequestThrottle behavior — permits, FIFO queueing, pacing, config parsing
// ABOUTME: Uses a manual clock so pacing is asserted deterministically, never on wall-clock timing
package org.nlogo.extensions.llm.providers

import org.scalatest.funsuite.AnyFunSuite
import scala.collection.mutable
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

/**
 * Clock whose time only moves when a test moves it.
 *
 * Pacing is a statement about ordering and delay arithmetic, not about how
 * punctual the JVM scheduler is. Asserting on elapsed wall-clock time turns
 * these into flaky scheduler-noise detectors; with a manual clock the same
 * properties are exact.
 */
class ManualClock(start: Long = 0L) extends ThrottleClock {
  private var current: Long = start
  private val pending = mutable.ListBuffer.empty[(Long, Promise[Unit])]

  def nowMs: Long = synchronized(current)

  def sleep(delayMs: Long): Future[Unit] = synchronized {
    if (delayMs <= 0L) Future.unit
    else {
      val p = Promise[Unit]()
      pending += ((current + delayMs, p))
      p.future
    }
  }

  /** Delays requested but not yet elapsed, in milliseconds from `start`. */
  def scheduledAt: List[Long] = synchronized(pending.map(_._1).toList.sorted)

  /** Move time forward, completing every delay that has now elapsed. */
  def advance(ms: Long): Unit = {
    val due = synchronized {
      current += ms
      val (elapsed, rest) = pending.partition(_._1 <= current)
      pending.clear()
      pending ++= rest
      elapsed.toList
    }
    due.foreach(_._2.trySuccess(()))
  }
}

/** Clock whose delays always fail, for the pacing-failure path. */
object FailingClock extends ThrottleClock {
  def nowMs: Long = 0L
  def sleep(delayMs: Long): Future[Unit] =
    Future.failed(new IllegalStateException("pacing delay failed"))
}

/**
 * Clock whose `sleep` throws rather than returning a failed Future, mirroring a
 * scheduler that rejects work because it has been shut down.
 */
object ThrowingClock extends ThrottleClock {
  def nowMs: Long = 0L
  def sleep(delayMs: Long): Future[Unit] =
    throw new IllegalStateException("scheduler rejected the delay")
}

class RequestThrottleSpec extends AnyFunSuite {

  /** Text every bad-cap warning contains, used to count them. */
  private val MAX_CONCURRENT_WARNING_MARKER = RequestThrottle.MAX_CONCURRENT_REQUESTS

  /** Block until `cond` holds, or fail. Used only to await async handoffs. */
  private def eventually(what: String)(cond: => Boolean): Unit = {
    val deadline = System.nanoTime() + 5.seconds.toNanos
    while (System.nanoTime() < deadline && !cond) Thread.sleep(2)
    assert(cond, s"timed out waiting for $what")
  }

  test("permits beyond the cap wait, then every request completes") {
    // The core invariant: a burst wider than the cap is deferred, not dropped.
    val throttle = new RequestThrottle(2, 0)
    val gates = List.fill(5)(Promise[Unit]())
    val running = new java.util.concurrent.atomic.AtomicInteger(0)
    val peak = new java.util.concurrent.atomic.AtomicInteger(0)

    val calls = gates.map { gate =>
      throttle.withPermit {
        val n = running.incrementAndGet()
        peak.updateAndGet(p => math.max(p, n))
        gate.future.map(_ => running.decrementAndGet())
      }
    }

    eventually("the cap to be filled")(running.get() == 2)
    assert(peak.get() == 2, s"only 2 may run at once, saw ${peak.get()}")

    gates.foreach(_.trySuccess(()))
    Await.result(Future.sequence(calls), 10.seconds)
    assert(peak.get() == 2, s"the cap must hold for the whole run, saw ${peak.get()}")
  }

  test("permits are released on success, failure, and a synchronous throw") {
    // A leaked permit is worse than no throttle: after `maxConcurrent` leaks the
    // model stops issuing requests and looks like a hang. All three exit paths
    // must return the permit, so all three are exercised against a cap of 1 —
    // a leak in any of them blocks the requests that follow.
    val throttle = new RequestThrottle(1, 0)

    Await.result(throttle.withPermit(Future.successful("ok")), 5.seconds)

    intercept[RuntimeException] {
      Await.result(throttle.withPermit(Future.failed(new RuntimeException("boom"))), 5.seconds)
    }

    intercept[IllegalStateException] {
      Await.result(
        throttle.withPermit[String](throw new IllegalStateException("thrown before any Future")),
        5.seconds
      )
    }

    // If any of the three leaked, this final acquisition never completes.
    assert(Await.result(throttle.withPermit(Future.successful("free")), 5.seconds) == "free")
  }

  test("queued work is admitted in arrival order") {
    // FIFO is what rules out starvation: with agents calling every tick, a
    // barging (non-fair) gate can pass over one waiter for an entire run.
    val throttle = new RequestThrottle(1, 0)
    val hold = Promise[Unit]()
    val admitted = new java.util.concurrent.ConcurrentLinkedQueue[Int]()

    val blocker = throttle.withPermit(hold.future)
    // withPermit takes a permit or joins the queue synchronously, before it
    // returns, so constructing these in order IS enqueuing them in order — no
    // waiting or polling is needed to establish arrival order.
    val queuedCalls = (1 to 4).map(i => throttle.withPermit(Future { admitted.add(i); () }))

    hold.success(())
    Await.result(Future.sequence(blocker +: queuedCalls), 10.seconds)

    val order = admitted.toArray(Array.empty[Integer]).map(_.intValue()).toList
    assert(order == List(1, 2, 3, 4), s"expected FIFO admission, got $order")
  }

  test("queued work occupies no thread while it waits") {
    // Blocking on a semaphore would park one thread per queued agent and can
    // deadlock the global pool. Queue far more work than the pool has threads,
    // then prove the pool still runs something else.
    val throttle = new RequestThrottle(1, 0)
    val hold = Promise[Unit]()
    val blocker = throttle.withPermit(hold.future)
    val queuedCalls = (1 to 64).map(_ => throttle.withPermit(Future.successful(())))

    assert(Await.result(Future(41 + 1), 5.seconds) == 42,
      "the execution context must not be starved by queued requests")

    hold.success(())
    Await.result(Future.sequence(blocker +: queuedCalls), 30.seconds)
  }

  test("pacing spaces successive starts by the configured interval") {
    // Deterministic: the clock only moves when this test moves it, so the
    // assertion is about the delays the throttle asks for, not about timing.
    val clock = new ManualClock()
    val throttle = new RequestThrottle(4, 100, clock)

    val calls = (1 to 3).map(_ => throttle.withPermit(Future.successful(())))
    eventually("all three to reserve a start slot")(clock.scheduledAt.size == 2)

    // First starts immediately; the next two are held to 100ms and 200ms.
    assert(clock.scheduledAt == List(100L, 200L), s"got ${clock.scheduledAt}")

    clock.advance(200)
    Await.result(Future.sequence(calls), 10.seconds)
  }

  test("a failing pacing delay releases the permit instead of leaking it") {
    // The permit is taken before pacing runs, so a pacing delay that fails must
    // still give it back. Otherwise one failure permanently removes capacity:
    // with a cap of 1 the gate is dead, and every later request waits forever
    // for a permit nobody holds. Losing a request is recoverable; losing the
    // gate is not.
    val throttle = new RequestThrottle(1, 100, FailingClock)

    intercept[IllegalStateException] {
      Await.result(throttle.withPermit(Future.successful("never runs")), 5.seconds)
    }

    // Capacity must have returned. A leak makes this wait out the timeout.
    intercept[IllegalStateException] {
      Await.result(throttle.withPermit(Future.successful("never runs either")), 5.seconds)
    }
  }

  test("a pacing delay that throws synchronously releases the permit") {
    // SystemThrottleClock.sleep schedules on an executor, which throws
    // RejectedExecutionException rather than returning a failed Future if that
    // executor is shut down. That path must not lose the permit either.
    val throttle = new RequestThrottle(1, 100, ThrowingClock)

    intercept[IllegalStateException] {
      Await.result(throttle.withPermit(Future.successful("never runs")), 5.seconds)
    }

    intercept[IllegalStateException] {
      Await.result(throttle.withPermit(Future.successful("never runs either")), 5.seconds)
    }
  }

  test("pacing is skipped entirely when the interval is zero") {
    // Pacing off must cost nothing — no scheduled delays at all.
    val clock = new ManualClock()
    val throttle = new RequestThrottle(4, 0, clock)

    val calls = (1 to 3).map(_ => throttle.withPermit(Future.successful(())))
    Await.result(Future.sequence(calls), 10.seconds)
    assert(clock.scheduledAt.isEmpty, s"expected no pacing delays, got ${clock.scheduledAt}")
  }

  // --- Configuration ---

  test("unset and zero disable throttling, preserving unbounded behaviour") {
    assert(RequestThrottle.forProvider("p", "http://a", None, None).isEmpty)
    assert(RequestThrottle.forProvider("p", "http://a", Some("0"), None).isEmpty)
    assert(RequestThrottle.forProvider("p", "http://a", Some(""), None).isEmpty)
  }

  test("negative and unparseable caps warn and disable throttling") {
    // A typo must not stall a model, but it must not pass in silence either:
    // the modeler asked for a limit and is not getting one.
    Seq("-1", "lots", "2.5").foreach { bad =>
      val warning = captureStdErr {
        assert(RequestThrottle.forProvider("p", "http://a", Some(bad), None).isEmpty,
          s"'$bad' should disable throttling")
      }
      assert(warning.contains(RequestThrottle.MAX_CONCURRENT_REQUESTS),
        s"'$bad' should be reported to the modeler, got: $warning")
    }
  }

  test("a bad value is reported once, not on every request") {
    // forProvider runs per request, so an unconditional warning would print once
    // per agent per tick — hundreds of thousands of lines in the very models
    // throttling is meant to help, drowning the console and slowing the run.
    val warnings = captureStdErr {
      (1 to 50).foreach(_ =>
        RequestThrottle.forProvider("warn-once", "http://warn-once.local", Some("nonsense"), None))
    }
    val count = warnings.linesIterator.count(_.contains(MAX_CONCURRENT_WARNING_MARKER))
    assert(count == 1, s"expected exactly 1 warning across 50 resolutions, got $count")
  }

  test("an unusable interval warns, disables pacing, and keeps the cap") {
    val warning = captureStdErr {
      val throttle = RequestThrottle
        .forProvider("p", "http://a", Some("2"), Some("soon"))
        .getOrElse(fail("the cap should still apply"))
      assert(throttle.maxConcurrent == 2)
      assert(throttle.minIntervalMs == 0L)
    }
    assert(warning.contains(RequestThrottle.MIN_REQUEST_INTERVAL_MS), s"got: $warning")
  }

  // --- Shared identity ---

  test("identity covers provider and endpoint but never a credential") {
    // A secret in a map key can surface in any diagnostic that prints it, so the
    // identity is rebuilt from scheme, host, port and path only. That drops the
    // two places a credential can legitimately appear in a URL: userinfo, and a
    // query parameter — which is exactly how Gemini passes its API key.
    assert(RequestThrottle.identity("openai", "https://api.openai.com/v1") ==
      "openai|https://api.openai.com/v1")

    val withUserInfo = RequestThrottle.identity("p", "https://user:sk-secret@api.test/v1")
    assert(!withUserInfo.contains("sk-secret"), s"userinfo must not survive: $withUserInfo")

    val withQueryKey = RequestThrottle.identity("gemini", "https://api.test/v1?key=sk-secret")
    assert(!withQueryKey.contains("sk-secret"), s"a query key must not survive: $withQueryKey")
  }

  test("one gate per provider and endpoint, not per instance") {
    // The gate must survive LLMExtension rebuilding its provider (set-provider,
    // load-config, set-thinking all do), and must not merge separate quotas.
    def gate(provider: String, url: String) =
      RequestThrottle.forProvider(provider, url, Some("3"), None).getOrElse(fail("expected a gate"))

    val a = gate("openai", "https://api.openai.com/v1")
    val again = gate("openai", "https://api.openai.com/v1")
    assert(a.eq(again), "the same provider and endpoint must share one gate")

    assert(!a.eq(gate("gemini", "https://api.openai.com/v1")),
      "different providers must not share a gate")
    assert(!gate("ollama", "http://localhost:11434").eq(gate("ollama", "http://remote:11434")),
      "the same provider on different endpoints must not share a gate")
  }

  test("endpoints differing only in trailing slash or case share one gate") {
    // These address one quota, so splitting them silently doubles the effective
    // cap: a modeler who writes a trailing slash gets 2x the limit they set.
    def gate(url: String) =
      RequestThrottle.forProvider("normalize", url, Some("1"), None).getOrElse(fail("expected a gate"))

    val canonical = gate("https://api.normalize.test/v1")
    assert(canonical.eq(gate("https://api.normalize.test/v1/")), "a trailing slash must not split the gate")
    assert(canonical.eq(gate("https://API.Normalize.TEST/v1")), "host case must not split the gate")
    assert(canonical.eq(gate("  https://api.normalize.test/v1  ")), "surrounding space must not split the gate")

    // Genuinely different endpoints must still be kept apart.
    assert(!canonical.eq(gate("https://api.normalize.test/v2")), "a different path is a different endpoint")
    assert(!canonical.eq(gate("https://other.normalize.test/v1")), "a different host is a different endpoint")
  }

  test("changing the configured cap reconfigures the same gate in place") {
    val url = "http://identity-change.local"
    val first = RequestThrottle.forProvider("cfgchange", url, Some("2"), Some("100")).getOrElse(fail())
    assert(first.maxConcurrent == 2)

    val second = RequestThrottle.forProvider("cfgchange", url, Some("5"), Some("250")).getOrElse(fail())
    assert(second.maxConcurrent == 5, "a changed cap must take effect")
    assert(second.minIntervalMs == 250L, "a changed interval must take effect")
    assert(first.eq(second), "the endpoint keeps one gate so in-flight permits stay counted")
  }

  /** Poll until `cond` holds or `limit` passes; release() runs on a callback thread. */
  private def eventually(limit: FiniteDuration = 2.seconds)(cond: => Boolean): Boolean = {
    val deadline = System.nanoTime() + limit.toNanos
    while (!cond && System.nanoTime() < deadline) Thread.sleep(5)
    cond
  }

  test("shrinking the cap under in-flight requests admits nothing until they drain below it") {
    // Two profiles on one endpoint with caps 2 and 1, alternating calls: the
    // gate must not be swapped for a fresh one with a full set of permits.
    val url = "http://cap-shrink.local"
    val gate = RequestThrottle.forProvider("capshrink", url, Some("2"), None).getOrElse(fail())
    val hold1 = Promise[Unit]()
    val hold2 = Promise[Unit]()
    val f1 = gate.withPermit(hold1.future)
    val f2 = gate.withPermit(hold2.future)
    assert(gate.inFlight == 2)

    val same = RequestThrottle.forProvider("capshrink", url, Some("1"), None).getOrElse(fail())
    assert(same.eq(gate))
    @volatile var thirdStarted = false
    val f3 = same.withPermit { thirdStarted = true; Future.unit }
    assert(same.waiting == 1, "the third request must queue: two are in flight above the new cap of 1")
    assert(!thirdStarted)

    hold1.success(())
    Await.ready(f1, 1.second)
    assert(eventually()(same.inFlight == 1))
    assert(!thirdStarted, "one still in flight fills a cap of 1")

    hold2.success(())
    Await.ready(f2, 1.second)
    Await.ready(f3, 1.second)
    assert(thirdStarted)
    assert(eventually()(same.inFlight == 0 && same.waiting == 0))
  }

  test("growing the cap hands the new permits to waiters immediately") {
    val url = "http://cap-grow.local"
    val gate = RequestThrottle.forProvider("capgrow", url, Some("1"), None).getOrElse(fail())
    val hold = Promise[Unit]()
    val f1 = gate.withPermit(hold.future)
    @volatile var secondStarted = false
    val f2 = gate.withPermit { secondStarted = true; Future.unit }
    assert(gate.waiting == 1)

    val same = RequestThrottle.forProvider("capgrow", url, Some("2"), None).getOrElse(fail())
    assert(same.eq(gate))
    Await.ready(f2, 1.second)
    assert(secondStarted, "raising the cap must admit the waiter without waiting for a release")
    assert(same.waiting == 0)

    hold.success(())
    Await.ready(f1, 1.second)
    assert(eventually()(same.inFlight == 0))
  }

  /** Run `body`, returning whatever it wrote to stderr. */
  private def captureStdErr(body: => Unit): String = {
    val buffer = new java.io.ByteArrayOutputStream()
    val original = System.err
    try {
      System.setErr(new java.io.PrintStream(buffer, true, "UTF-8"))
      body
    } finally {
      System.setErr(original)
    }
    buffer.toString("UTF-8")
  }
}
