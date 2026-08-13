// ABOUTME: Rate-limit retry policy — backoff with jitter, budget accounting, and provider-requested delays
// ABOUTME: Parses retry hints from response bodies (e.g. Gemini RetryInfo) as well as Retry-After headers
package org.nlogo.extensions.llm.providers

import scala.concurrent.duration._
import scala.util.Try

/**
 * Tunable retry policy for rate-limit (HTTP 429) responses.
 *
 * The defaults are sized to clear a per-minute quota window. Free tiers commonly
 * allow only a handful of requests per minute, and an agent-based model calling
 * an LLM per agent per tick blows through that on the first tick. A policy whose
 * total budget is shorter than the quota window can never recover from such a
 * limit, so `maxElapsed` defaults to slightly over one minute.
 *
 * @param maxRetries    maximum retry attempts after the initial request
 * @param baseDelay     first backoff interval; doubles per attempt
 * @param maxDelay      ceiling on any single sleep
 * @param maxElapsed    ceiling on total time spent waiting across all retries
 * @param jitterFactor  fraction of each delay randomized, to desynchronize agents
 */
case class RetryPolicy(
  maxRetries: Int = RetryPolicy.DefaultMaxRetries,
  baseDelay: FiniteDuration = RetryPolicy.DefaultBaseDelay,
  maxDelay: FiniteDuration = RetryPolicy.DefaultMaxDelay,
  maxElapsed: FiniteDuration = RetryPolicy.DefaultMaxElapsed,
  jitterFactor: Double = RetryPolicy.DefaultJitterFactor
) {

  /** Exponential backoff for `attempt` (0-based), capped at maxDelay. */
  def backoffMs(attempt: Int): Long = {
    // Shift on a capped exponent; beyond this the cap dominates anyway and
    // shifting further would overflow.
    val exponent = math.min(attempt, 32)
    val raw = baseDelay.toMillis.toDouble * math.pow(2.0, exponent.toDouble)
    math.min(raw, maxDelay.toMillis.toDouble).toLong
  }

  /**
   * Delay before the next attempt: the larger of our backoff and whatever the
   * provider asked for, capped at maxDelay, then jittered.
   *
   * A provider-requested delay is the single most useful piece of information in
   * a 429 — it states exactly when the quota window reopens. Retrying earlier is
   * guaranteed to fail, so the request wins over our own backoff when longer.
   */
  def delayMsFor(attempt: Int, requested: Option[Long], rng: () => Double): Long = {
    val backoff = backoffMs(attempt)
    val wanted = requested.fold(backoff)(r => math.max(r, backoff))
    val capped = math.min(wanted, maxDelay.toMillis)
    applyJitter(capped, rng)
  }

  /**
   * Spread delays over [d, d * (1 + jitterFactor)] so that many agents rate-limited
   * by the same quota window do not all retry on the same millisecond. Jitter only
   * ever adds, never subtracts, so a provider-requested delay is still honored in full.
   */
  def applyJitter(delayMs: Long, rng: () => Double): Long =
    if (jitterFactor <= 0.0 || delayMs <= 0L) delayMs
    else delayMs + (delayMs.toDouble * jitterFactor * rng()).toLong

  /**
   * Whether another attempt is allowed: retries left, and the wait still fits
   * inside the total budget.
   */
  def canRetry(attempt: Int, elapsedMs: Long, nextDelayMs: Long): Boolean =
    attempt < maxRetries && (elapsedMs + nextDelayMs) <= maxElapsed.toMillis
}

object RetryPolicy {
  // Sized so the default policy can sit out a per-minute quota window:
  // 1s + 2s + 4s + 8s + 16s + 32s exceeds 60s of waiting across 6 retries.
  val DefaultMaxRetries = 6
  val DefaultBaseDelay: FiniteDuration = 1.second
  val DefaultMaxDelay: FiniteDuration = 64.seconds
  val DefaultMaxElapsed: FiniteDuration = 65.seconds
  val DefaultJitterFactor = 0.25

  /** Config keys allowing modelers to tune retry behavior. */
  val MAX_RETRIES = "retry_max_retries"
  val MAX_ELAPSED_SECONDS = "retry_max_elapsed_seconds"

  /**
   * Extract a provider-requested retry delay in milliseconds.
   *
   * Checked in order of reliability:
   *  1. `Retry-After` header (OpenAI-compatible, Anthropic) — seconds, or an
   *     HTTP-date which we ignore rather than guess at clock skew.
   *  2. Google's `error.details[].RetryInfo.retryDelay` — a duration string
   *     such as "18.5s". This is where Gemini puts it; reading only the header
   *     discards the one value that would make the retry succeed.
   *  3. `error.message` prose, e.g. "retry in 18.5s", as a last resort.
   */
  def requestedDelayMs(header: Option[String], body: String): Option[Long] =
    header.flatMap(parseRetryAfterHeader)
      .orElse(parseBodyRetryDelayMs(body))

  /** Parse a Retry-After header. Only the delta-seconds form is supported. */
  private def parseRetryAfterHeader(value: String): Option[Long] =
    parseSeconds(value.trim).map(s => (s * 1000.0).toLong)

  /** Parse a retry delay out of a JSON error body. Never throws on bad JSON. */
  def parseBodyRetryDelayMs(body: String): Option[Long] =
    if (body == null || body.isEmpty) None
    else Try {
      val parsed = ujson.read(body)
      retryInfoDelayMs(parsed).orElse(prosaicDelayMs(parsed))
    }.toOption.flatten

  /** Google RetryInfo: error.details[] entry with a "retryDelay" like "18.5s". */
  private def retryInfoDelayMs(parsed: ujson.Value): Option[Long] =
    Try(parsed("error")("details").arr).toOption.flatMap { details =>
      details.iterator.flatMap { detail =>
        Try(detail("retryDelay").str).toOption
          .orElse(Try(detail("RetryInfo")("retryDelay").str).toOption)
      }.flatMap(parseDuration).nextOption()
    }

  /** Fallback: "…please retry in 18.5s" prose inside error.message. */
  private def prosaicDelayMs(parsed: ujson.Value): Option[Long] =
    Try(parsed("error")("message").str).toOption.flatMap { msg =>
      RetryInProse.findFirstMatchIn(msg).flatMap(m => parseDuration(m.group(1)))
    }

  private val RetryInProse = """(?i)retry\s+(?:in|after)\s+([0-9.]+\s*(?:ms|s|m)?)""".r

  /** Parse a protobuf-style duration ("18.5s", "500ms", "2m") to milliseconds. */
  def parseDuration(raw: String): Option[Long] = {
    val v = raw.trim.toLowerCase.replaceAll("\\s+", "")
    val (numeric, multiplier) =
      if (v.endsWith("ms")) (v.dropRight(2), 1.0)
      else if (v.endsWith("s")) (v.dropRight(1), 1000.0)
      else if (v.endsWith("m")) (v.dropRight(1), 60000.0)
      else (v, 1000.0) // bare numbers are seconds, matching Retry-After
    numeric.toDoubleOption.filter(d => d >= 0.0 && d.isFinite).map(d => (d * multiplier).toLong)
  }

  private def parseSeconds(v: String): Option[Double] =
    v.toDoubleOption.filter(d => d >= 0.0 && d.isFinite)
}
