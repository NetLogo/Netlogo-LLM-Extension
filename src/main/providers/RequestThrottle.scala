// ABOUTME: Proactive request throttle — bounded in-flight requests and optional pacing per provider
// ABOUTME: Non-blocking FIFO gate so queued work never occupies a thread while it waits
package org.nlogo.extensions.llm.providers

import java.util.concurrent.{ConcurrentHashMap, Executors, ScheduledExecutorService, TimeUnit}
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration._
import scala.util.control.NonFatal

/**
 * Time source and delay scheduler for a throttle.
 *
 * Injected so pacing can be driven by an explicit clock in tests. Asserting on
 * wall-clock gaps would make the suite a scheduler-noise detector: a paced
 * delay that lands 20ms late says nothing about whether pacing is correct.
 */
trait ThrottleClock {
  /** Current time in milliseconds. */
  def nowMs: Long

  /** A Future completing after `delayMs`, without occupying a thread. */
  def sleep(delayMs: Long): Future[Unit]
}

/** Real time, with delays on a shared daemon scheduler. */
object SystemThrottleClock extends ThrottleClock {
  // Daemon so it never blocks JVM shutdown; one idle thread persists across
  // extension reloads, mirroring the retry scheduler in BaseHttpProvider.
  private lazy val scheduler: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor { (r: Runnable) =>
      val t = new Thread(r, "llm-throttle-scheduler")
      t.setDaemon(true)
      t
    }

  def nowMs: Long = System.currentTimeMillis()

  def sleep(delayMs: Long): Future[Unit] = {
    if (delayMs <= 0L) return Future.unit
    val p = Promise[Unit]()
    scheduler.schedule(
      new Runnable { def run(): Unit = p.trySuccess(()) },
      delayMs,
      TimeUnit.MILLISECONDS
    )
    p.future
  }
}

/**
 * Limits how many requests are in flight at once, and optionally paces them.
 *
 * Retry-on-429 recovers from a rate limit after it happens; this exists to
 * avoid tripping one. `ask turtles [ llm:chat-async ... ]` issues one request
 * per turtle with nothing between the agents and the socket, so a 200-turtle
 * model opens 200 connections on the first tick.
 *
 * Waiting is non-blocking by construction. A caller that cannot get a permit
 * receives an incomplete Future and its continuation runs later, when a permit
 * is handed over. No thread — least of all NetLogo's, or one belonging to
 * ExecutionContext.global — ever sits parked inside this class waiting for
 * capacity, which a `Semaphore.acquire()` would do.
 *
 * @param maxConcurrent  permits available; must be positive
 * @param minIntervalMs  minimum gap between successive request starts, 0 to disable
 * @param clock          time source and delay scheduler
 */
class RequestThrottle(
  val maxConcurrent: Int,
  val minIntervalMs: Long,
  clock: ThrottleClock = SystemThrottleClock
) {
  require(maxConcurrent > 0, "maxConcurrent must be positive")

  // Guards `available`, `waiters` and `lastStartMs` together. Held only for
  // queue arithmetic — never across a user callback or an HTTP send.
  private val lock = new Object
  private var available: Int = maxConcurrent

  // FIFO, so a waiter cannot be overtaken indefinitely — with agents calling
  // every tick, barging would pass one over for the whole run. Only ever
  // touched under `lock`, so a plain queue suffices.
  private val waiters = mutable.Queue.empty[Promise[Unit]]

  // Start time reserved for the most recently admitted request, for pacing.
  private var lastStartMs: Long = Long.MinValue

  /**
   * Run `body` once a permit is free, releasing the permit however it finishes.
   *
   * The permit covers the whole logical request including its retries, because
   * `body` is the retry loop rather than a single send. Releasing between
   * attempts would let a retrying request re-enter behind newly arrived ones and
   * push the real in-flight count above the cap — exactly what the cap forbids.
   */
  def withPermit[A](body: => Future[A])(implicit ec: ExecutionContext): Future[A] =
    acquire().flatMap { _ =>
      // A throw inside `body` before it returns a Future would otherwise leak
      // the permit, so failure is normalised into the Future here.
      val started =
        try body
        catch { case NonFatal(e) => Future.failed(e) }
      // onComplete covers success, HTTP failure, and thrown exception alike.
      started.onComplete(_ => release())
      started
    }

  /**
   * Obtain a permit. The returned Future completes immediately when capacity is
   * free, otherwise when an earlier request releases one.
   */
  private def acquire()(implicit ec: ExecutionContext): Future[Unit] = {
    // ONE synchronized transition: take a permit, or join the queue. A free
    // permit is taken only when nobody is already waiting, so a later arrival
    // can never be admitted ahead of a waiter.
    val waiting = lock.synchronized {
      if (available > 0 && waiters.isEmpty) {
        available -= 1
        None
      } else {
        val p = Promise[Unit]()
        waiters.enqueue(p)
        Some(p)
      }
    }
    waiting match {
      case None    => paceThenProceed()
      case Some(p) => p.future.flatMap(_ => paceThenProceed())
    }
  }

  /**
   * Delay the start of a request so successive starts are at least
   * `minIntervalMs` apart. Serves a requests-per-minute quota: a cap alone
   * bounds simultaneity, not rate, so N permits recycling quickly can still
   * exceed an RPM limit.
   */
  private def paceThenProceed()(implicit ec: ExecutionContext): Future[Unit] = {
    if (minIntervalMs <= 0L) return Future.unit

    val waitMs = lock.synchronized {
      val now = clock.nowMs
      // Reserve this request's slot before releasing the lock, so concurrent
      // callers each get a distinct one rather than all pacing off the same
      // timestamp and starting together.
      val earliest =
        if (lastStartMs == Long.MinValue) now
        else math.max(now, lastStartMs + minIntervalMs)
      lastStartMs = earliest
      earliest - now
    }

    clock.sleep(waitMs)
  }

  /**
   * Return a permit, transferring it directly to the oldest waiter if there is one.
   *
   * Exactly ONE synchronized transition decides the permit's fate: it either
   * dequeues the oldest waiter — leaving `available` untouched, so no arrival
   * can ever observe that permit as free capacity — or, when the queue is empty,
   * increments `available`. Both outcomes are decided under the same lock
   * acquisition.
   *
   * Splitting these into two locked sections would be a lost wakeup, not merely
   * unfair: a caller enqueuing after an empty queue was observed but before
   * `available` was incremented would be handed nothing by this release, and
   * nothing else would come to wake it — it would wait forever while capacity
   * sat idle. Deciding both in one transition makes that interleaving
   * unrepresentable.
   *
   * The Promise is completed after the lock is dropped, so a waiter's
   * continuation never runs while this thread holds it.
   */
  private def release(): Unit = {
    val handoff = lock.synchronized {
      if (waiters.nonEmpty) Some(waiters.dequeue())
      else { available += 1; None }
    }
    handoff.foreach(_.success(()))
  }
}

object RequestThrottle {
  /** Maximum requests in flight at once for one provider. 0 or unset disables. */
  val MAX_CONCURRENT_REQUESTS = "max_concurrent_requests"

  /** Minimum milliseconds between request starts. 0 or unset disables pacing. */
  val MIN_REQUEST_INTERVAL_MS = "min_request_interval_ms"

  // Messages already shown, so a bad config value is reported once rather than
  // once per request. forProvider runs on every request; an unconditional
  // println would emit one line per agent per tick — worst in exactly the
  // high-fan-out models throttling exists to help.
  private val warningsShown = ConcurrentHashMap.newKeySet[String]()

  /** Print `message` to stderr the first time it is seen. */
  private def warnOnce(message: String): Unit =
    if (warningsShown.add(message)) System.err.println(message)

  // Process-wide, because provider instances are not stable. LLMExtension drops
  // and rebuilds `currentProvider` on set-provider, load-config, set-thinking,
  // set-reasoning-effort and set-thinking-budget; an instance field would reset
  // the cap mid-run and lose track of requests the previous instance still has
  // open. Keyed identity survives that churn.
  private val throttles = new ConcurrentHashMap[String, RequestThrottle]()

  /**
   * Identity of the quota a request draws on: the provider and the endpoint it
   * talks to.
   *
   * Two instances of the same provider must share one gate, or the cap means
   * nothing. Different providers must not share one, or a Gemini free tier
   * throttles a paid OpenAI key alongside it.
   *
   * The base URL splits genuinely separate endpoints — two OpenRouter
   * configurations pointed at different hosts do not share a quota, nor does a
   * local Ollama share one with a remote.
   *
   * The API key is deliberately NOT part of this. It is the truest identity of
   * an account quota, but a secret has no business in a map key that may be
   * printed in a diagnostic. Base URL is the safe proxy.
   *
   * The URL is normalised first. Two spellings of one endpoint — a trailing
   * slash, a capitalised host — address the same quota, so treating them as
   * distinct would silently hand a modeler twice the cap they configured.
   */
  private[providers] def identity(providerName: String, baseUrl: String): String =
    s"${providerName.toLowerCase.trim}|${normalizeEndpoint(baseUrl)}"

  /**
   * Canonical form of a base URL for identity purposes.
   *
   * Scheme and host are case-insensitive per RFC 3986, and a trailing slash on
   * the base does not change which endpoint is addressed. The path is otherwise
   * left alone: `/v1` and `/v2` are genuinely different endpoints.
   *
   * Falls back to a trimmed, lowercased string if the value will not parse,
   * since a throttle key must never be the thing that fails a request.
   */
  private def normalizeEndpoint(baseUrl: String): String = {
    val trimmed = Option(baseUrl).getOrElse("").trim
    try {
      val uri = new java.net.URI(trimmed)
      val scheme = Option(uri.getScheme).map(_.toLowerCase).getOrElse("")
      val host = Option(uri.getHost).map(_.toLowerCase).getOrElse("")
      if (scheme.isEmpty || host.isEmpty) trimmed.toLowerCase.stripSuffix("/")
      else {
        // Default ports are implicit, so :443 must not split from the bare host.
        val port = uri.getPort match {
          case -1                          => ""
          case 80 if scheme == "http"      => ""
          case 443 if scheme == "https"    => ""
          case p                           => s":$p"
        }
        val path = Option(uri.getPath).getOrElse("").stripSuffix("/")
        s"$scheme://$host$port$path"
      }
    } catch {
      case NonFatal(_) => trimmed.toLowerCase.stripSuffix("/")
    }
  }

  /**
   * The throttle for a provider+endpoint, or None when throttling is off.
   *
   * Unset or zero disables the gate, preserving the unbounded behaviour of
   * earlier versions. A negative or unparseable value is a mistake rather than
   * an intention, so it is reported on stderr and then treated as disabled: a
   * typo must not stall a model, but silently ignoring a cap a modeler asked
   * for is how a run keeps overrunning a quota with nothing to show why.
   */
  def forProvider(
    providerName: String,
    baseUrl: String,
    maxConcurrentRaw: Option[String],
    minIntervalRaw: Option[String]
  ): Option[RequestThrottle] =
    parseCap(maxConcurrentRaw).map { limit =>
      val interval = parseInterval(minIntervalRaw)
      val key = identity(providerName, baseUrl)
      val replaced = new java.util.concurrent.atomic.AtomicBoolean(false)

      // compute() so a concurrent caller cannot observe a half-installed gate
      // or race two replacements past each other. The remapping function does
      // no I/O — it runs while the map holds a bin lock, so the notice below is
      // emitted afterwards rather than inside it.
      val throttle = throttles.compute(key, (_, existing) => {
        if (existing != null && existing.maxConcurrent == limit && existing.minIntervalMs == interval) {
          existing
        } else {
          if (existing != null) replaced.set(true)
          new RequestThrottle(limit, interval)
        }
      })

      if (replaced.get()) {
        // Requests already running under the previous gate keep its permits, so
        // in-flight work can briefly exceed the new cap. Reporting beats a stall
        // or a silently changed limit.
        warnOnce(
          s"NOTE: request throttle for $providerName updated to " +
          s"$MAX_CONCURRENT_REQUESTS=$limit, $MIN_REQUEST_INTERVAL_MS=$interval. " +
          "Requests already in flight finish under the previous limit."
        )
      }
      throttle
    }

  /**
   * Parse the concurrency cap. None means "no gate".
   *
   * Zero and unset are the documented ways to disable and pass silently.
   * Anything else unusable is announced, because it means the modeler intended
   * a limit and is not getting one.
   */
  private def parseCap(raw: Option[String]): Option[Int] =
    raw.map(_.trim).filter(_.nonEmpty) match {
      case None => None
      case Some(v) =>
        v.toIntOption match {
          case Some(n) if n > 0 => Some(n)
          case Some(0)          => None // documented "off"
          case Some(n) =>
            warnOnce(
              s"WARNING: $MAX_CONCURRENT_REQUESTS must be a positive whole number of requests, " +
              s"but was '$n'. Request throttling is disabled; use 0 to disable it deliberately."
            )
            None
          case None =>
            warnOnce(
              s"WARNING: $MAX_CONCURRENT_REQUESTS must be a whole number, but was '$v'. " +
              "Request throttling is disabled; use 0 to disable it deliberately."
            )
            None
        }
    }

  /** Parse the pacing interval in milliseconds. Unusable values disable pacing. */
  private def parseInterval(raw: Option[String]): Long =
    raw.map(_.trim).filter(_.nonEmpty) match {
      case None => 0L
      case Some(v) =>
        v.toLongOption match {
          case Some(n) if n >= 0 => n
          case Some(n) =>
            warnOnce(
              s"WARNING: $MIN_REQUEST_INTERVAL_MS must be milliseconds >= 0, but was '$n'. " +
              "Pacing is disabled."
            )
            0L
          case None =>
            warnOnce(
              s"WARNING: $MIN_REQUEST_INTERVAL_MS must be a whole number of milliseconds, " +
              s"but was '$v'. Pacing is disabled."
            )
            0L
        }
    }
}
