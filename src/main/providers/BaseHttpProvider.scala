// ABOUTME: Abstract base class for HTTP-based LLM providers, consolidating common functionality
// ABOUTME: Reduces boilerplate by providing shared implementation of config, validation, and HTTP request handling
package org.nlogo.extensions.llm.providers

import org.nlogo.extensions.llm.models.{ChatMessage, ChatRequest, ChatResponse, ResponseFormat, Usage}
import org.nlogo.extensions.llm.config.ConfigStore
import sttp.client4._
import sttp.client4.httpclient.HttpClientFutureBackend
import sttp.model.{StatusCode, Uri}
import ujson._
import java.util.concurrent.{Executors, ScheduledExecutorService, TimeUnit}
import scala.concurrent.{Future, ExecutionContext, Promise}
import scala.concurrent.duration._
import scala.util.{Try, Success, Failure}

/** A non-success HTTP response, carrying the status code so callers can branch on it. */
class HttpStatusException(val statusCode: Int, val errorBody: String, message: String)
  extends RuntimeException(message)

object BaseHttpProvider {
  /** Waits at or above this are announced on stderr so a stall isn't mistaken for a hang. */
  private[providers] val WaitNoticeThresholdMs = 2000L

  // Process-wide counters so a modeler can tell how much of a run was spent
  // sitting out retries (rate limits, server errors, overloads, network
  // failures), rather than guessing from wall-clock time.
  private[providers] val retryWaits = new java.util.concurrent.atomic.LongAdder()
  private[providers] val retryWaitMillis = new java.util.concurrent.atomic.LongAdder()

  /** Number of retry waits that have been announced this session. */
  def retryWaitCount: Long = retryWaits.sum()

  /** Total milliseconds spent in announced retry waits this session. */
  def retryWaitTotalMs: Long = retryWaitMillis.sum()

  // (provider, model) pairs already told that schema-constrained output is unsupported.
  private[providers] val responseFormatFallbackNoticed: java.util.Set[String] =
    java.util.concurrent.ConcurrentHashMap.newKeySet[String]()

  /**
   * True when a 400 says the model rejected the requested response_format, as
   * opposed to any other bad request (bad key, unknown model) that must still fail.
   */
  private[providers] def isResponseFormatUnsupported(e: HttpStatusException): Boolean = {
    val body = e.errorBody.toLowerCase
    e.statusCode == 400 &&
      Seq("response_format", "json_schema", "json_object", "structured", "not supported", "unsupported")
        .exists(body.contains)
  }

  /** Reset the retry-wait counters. Primarily for tests and fresh runs. */
  def resetRetryWaitStats(): Unit = {
    retryWaits.reset()
    retryWaitMillis.reset()
  }

  /**
   * Server-side statuses worth retrying: the request was fine, the provider was
   * briefly unable to serve it. 529 is Anthropic's "overloaded". 429 is handled
   * separately because it also carries a requested delay and quota guidance.
   */
  val TransientStatuses: Set[Int] = Set(500, 502, 503, 504, 529)

  /** `e` and its chain of causes, outermost first. */
  private[providers] def causes(e: Throwable): List[Throwable] =
    Iterator.iterate(e)(_.getCause).takeWhile(_ != null).take(16).toList

  /**
   * Whether a failed send is a transport hiccup worth retrying: the connection
   * could not be opened, dropped mid-read, or timed out. Checked across the
   * cause chain because backends and Future plumbing wrap the original error.
   */
  def isTransientFailure(e: Throwable): Boolean =
    !isUnknownHost(e) && causes(e).exists {
      case _: SttpClientException.ConnectException | _: SttpClientException.ReadException => true
      case _: java.net.ConnectException | _: java.net.SocketTimeoutException => true
      case _: java.net.http.HttpTimeoutException => true
      case _ => false
    }

  /**
   * Whether a failed send is a host name that does not resolve. java.net.http
   * reports this as a ConnectException caused by UnresolvedAddressException, so
   * it would otherwise look like a refused connection. Not retried: a mistyped
   * base URL will not resolve on the next attempt either.
   */
  def isUnknownHost(e: Throwable): Boolean =
    causes(e).exists {
      case _: java.nio.channels.UnresolvedAddressException | _: java.net.UnknownHostException => true
      case _ => false
    }

  /**
   * Whether an error body says the API key itself was rejected. Gemini reports a
   * bad key as HTTP 400 with reason API_KEY_INVALID rather than as a 401.
   */
  def isInvalidApiKey(code: Int, errorBody: String): Boolean =
    code == 401 || errorBody.contains("API_KEY_INVALID")

  /**
   * The stderr line announcing a retry wait, e.g.
   * "NOTE: Anthropic returned HTTP 529 (overloaded); retrying in 2.0s (attempt 2 of 4)".
   * `attempt` is the 0-based attempt that just failed; attempts are counted 1-based
   * out of the initial request plus every allowed retry.
   */
  def retryWaitNotice(provider: String, cause: String, delayMs: Long, attempt: Int, maxRetries: Int): String =
    f"NOTE: $provider $cause; retrying in ${delayMs / 1000.0}%.1fs " +
    s"(attempt ${attempt + 2} of ${maxRetries + 1})"

  // Single daemon thread schedules retry attempts. Daemon so it never blocks JVM
  // shutdown; one idle thread persists across extension reloads, which is fine.
  private lazy val retryScheduler: ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor { (r: Runnable) =>
      val t = new Thread(r, "llm-retry-scheduler")
      t.setDaemon(true)
      t
    }

  /**
   * Run `f` after `delayMs`, off the caller and Future-pool threads, so backoff
   * never blocks NetLogo's thread or starves the global execution context.
   */
  private[providers] def delayedFuture[A](delayMs: Long)(f: => Future[A])(
    implicit ec: ExecutionContext
  ): Future[A] = {
    val p = Promise[A]()
    retryScheduler.schedule(
      new Runnable { def run(): Unit = p.completeWith(f) },
      delayMs,
      TimeUnit.MILLISECONDS
    )
    p.future
  }
}

/**
 * Abstract base class for HTTP-based LLM providers
 *
 * Consolidates common functionality to reduce boilerplate in provider implementations.
 * Subclasses only need to implement provider-specific request/response formatting.
 */
abstract class BaseHttpProvider(implicit ec: ExecutionContext) extends LLMProvider {

  protected val configStore = new ConfigStore()
  // lazy + overridable so tests can substitute a stub backend without ever
  // constructing the real HTTP client.
  protected lazy val backend: Backend[Future] = HttpClientFutureBackend()

  // Retry policy for rate-limit (HTTP 429) responses and other transient
  // failures (5xx, 529, connect/read timeouts). retryBaseDelayMs is a
  // protected def so tests can shrink the delays.
  protected def retryBaseDelayMs: Long = RetryPolicy.DefaultBaseDelay.toMillis

  /**
   * Effective retry policy, rebuilt per request so config changes take effect
   * without recreating the provider. Modelers can tune the retry count and the
   * total waiting budget; delays scale off retryBaseDelayMs so tests can shrink them.
   */
  protected def retryPolicy: RetryPolicy = {
    val base = retryBaseDelayMs
    val default = RetryPolicy()
    val scale = base.toDouble / RetryPolicy.DefaultBaseDelay.toMillis.toDouble
    val maxRetries = configStore.get(RetryPolicy.MAX_RETRIES)
      .flatMap(_.trim.toIntOption).filter(_ >= 0).getOrElse(default.maxRetries)
    val maxElapsed = configStore.get(RetryPolicy.MAX_ELAPSED_SECONDS)
      .flatMap(_.trim.toDoubleOption).filter(d => d >= 0.0 && d.isFinite)
      .map(d => (d * 1000.0).toLong.millis)
      .getOrElse((default.maxElapsed.toMillis * scale).toLong.max(1L).millis)
    default.copy(
      maxRetries = maxRetries,
      baseDelay = base.millis,
      maxDelay = (default.maxDelay.toMillis * scale).toLong.max(1L).millis,
      maxElapsed = maxElapsed
    )
  }

  /** Random source for jitter. Overridable so tests can make backoff deterministic. */
  protected def retryRandom: () => Double = () => scala.util.Random.nextDouble()

  /**
   * Proactive throttle for this provider, or None when it is switched off.
   *
   * Resolved per request so a config change takes effect without recreating the
   * provider, matching how retryPolicy is handled. The gate is shared across
   * every instance addressing the same provider and endpoint — see
   * RequestThrottle.identity for why that is the right granularity.
   */
  protected def requestThrottle: Option[RequestThrottle] =
    RequestThrottle.forProvider(
      providerName,
      resolvedBaseUrl,
      configStore.get(RequestThrottle.MAX_CONCURRENT_REQUESTS),
      configStore.get(RequestThrottle.MIN_REQUEST_INTERVAL_MS)
    )

  /**
   * Reports a retry wait to the modeler. A silent multi-second stall inside
   * `go` is indistinguishable from a hang, so long waits are announced on stderr.
   * Short waits stay quiet to avoid spamming the console on routine backoff.
   *
   * @param cause what is being waited out, e.g. "returned HTTP 529 (overloaded)"
   */
  protected def notifyRetryWait(delayMs: Long, attempt: Int, maxRetries: Int, cause: String): Unit =
    if (delayMs >= BaseHttpProvider.WaitNoticeThresholdMs) {
      System.err.println(BaseHttpProvider.retryWaitNotice(displayName, cause, delayMs, attempt, maxRetries))
      BaseHttpProvider.retryWaits.increment()
      BaseHttpProvider.retryWaitMillis.add(delayMs)
    }

  /**
   * Tells the modeler, once per (provider, model), that structured output fell
   * back to an unconstrained reply because the model rejected the response_format.
   */
  protected def notifyResponseFormatUnsupported(model: String): Unit =
    System.err.println(
      s"NOTE: $displayName model $model does not support schema-constrained output " +
      "(HTTP 400 on response_format); resending without it, so the reply is unconstrained."
    )

  /** Human-readable provider name for messages, from the provider registry. */
  def displayName: String =
    ProviderRegistry.get(providerName).map(_.displayName).getOrElse(providerName)

  /** Next step suggested when this provider's endpoint cannot be reached. */
  protected def unreachableHint: String =
    s"Check your network connection and $baseUrlConfigKey."

  // Initialize with provider-specific defaults
  initializeDefaults()

  // Abstract methods for provider-specific behavior
  def providerName: String
  def defaultModel: String
  protected def defaultBaseUrl: String
  protected def baseUrlConfigKey: String
  protected def apiKeyConfigKey: String
  protected def defaultMaxTokens: String
  protected def requiresApiKey: Boolean

  protected def buildApiUrl(baseUrl: String): Uri

  /** Base URL for requests: provider-specific key, then legacy base_url, then default. */
  protected def resolvedBaseUrl: String =
    ConfigStore.resolveBaseUrl(configStore, baseUrlConfigKey, defaultBaseUrl)
  protected def buildHeaders(apiKey: Option[String]): Map[String, String]
  protected def createProviderRequest(request: ChatRequest): ujson.Value
  protected def parseProviderResponse(responseBody: String, model: String): ChatResponse

  /**
   * Initialize provider-specific default configuration
   */
  protected def initializeDefaults(): Unit = {
    configStore.set(ConfigStore.PROVIDER, providerName)
    configStore.set(ConfigStore.MODEL, defaultModel)
    configStore.set(ConfigStore.TEMPERATURE, ConfigStore.DEFAULT_TEMPERATURE)
    configStore.set(ConfigStore.MAX_TOKENS, defaultMaxTokens)
  }

  /**
   * Send a chat request with validation
   */
  override def chat(request: ChatRequest): Future[ChatResponse] = {
    validateConfig() match {
      case Success(_) =>
        sendChatRequest(request).recoverWith {
          // Some models reject response_format outright; resend once without it.
          // Callers already prompt for the expected shape, so the reply still parses.
          case e: HttpStatusException
              if request.responseFormat.isDefined && BaseHttpProvider.isResponseFormatUnsupported(e) =>
            if (BaseHttpProvider.responseFormatFallbackNoticed.add(s"$providerName/${request.model}"))
              notifyResponseFormatUnsupported(request.model)
            sendChatRequest(request.copy(responseFormat = None))
        }
      case Failure(e) => Future.failed(e)
    }
  }

  /**
   * Build a ChatRequest from current config and messages, resolving ThinkingConfig
   */
  protected def buildRequest(
    messages: Seq[ChatMessage],
    responseFormat: Option[ResponseFormat] = None
  ): ChatRequest = {
    val model = configStore.getOrElse(ConfigStore.MODEL, defaultModel)
    val temperature = configStore.get(ConfigStore.TEMPERATURE).map(_.toDouble)
    val maxTokens = configStore.get(ConfigStore.MAX_TOKENS).map(_.toInt)
    val thinkingConfig = ReasoningModelDetector.resolveThinkingConfig(providerName, model, configStore)

    ChatRequest(
      model = model,
      messages = messages,
      maxTokens = maxTokens,
      temperature = temperature,
      thinkingConfig = thinkingConfig,
      responseFormat = responseFormat
    )
  }

  /**
   * Simplified chat method that takes messages directly
   */
  override def chat(messages: Seq[ChatMessage]): Future[ChatMessage] = {
    val request = buildRequest(messages)

    chat(request).map(_.firstMessage.getOrElse(
      throw new RuntimeException(s"No response message received from $providerName")
    ))
  }

  /**
   * Chat method that returns the full response including thinking text
   */
  override def chatWithFullResponse(messages: Seq[ChatMessage]): Future[ChatResponse] = {
    val request = buildRequest(messages)
    chat(request)
  }

  /**
   * Chat with the reply constrained to a response format.
   *
   * The format travels on the ChatRequest; each provider's createProviderRequest
   * decides how to express it on the wire.
   */
  override def chatWithFormat(
    messages: Seq[ChatMessage],
    format: ResponseFormat
  ): Future[ChatResponse] = {
    val request = buildRequest(messages, Some(format))
    chat(request)
  }

  /**
   * Send the actual HTTP request
   */
  protected def sendChatRequest(request: ChatRequest): Future[ChatResponse] = {
    val apiKey = if (requiresApiKey) {
      Some(configStore.get(apiKeyConfigKey)
        .orElse(configStore.get(ConfigStore.API_KEY))
        .getOrElse(throw new IllegalStateException("API key not configured")))
    } else {
      configStore.get(apiKeyConfigKey).orElse(configStore.get(ConfigStore.API_KEY))
    }

    val baseUrl = resolvedBaseUrl
    val apiUrl = buildApiUrl(baseUrl)
    val headers = buildHeaders(apiKey)
    val requestBody = createProviderRequest(request).toString()

    val httpRequest = basicRequest
      .headers(headers)
      .body(requestBody)
      .post(apiUrl)

    executeWithRetry(httpRequest, request.model)
  }

  /**
   * Provider-specific hook for reading the retry delay a server asked for.
   *
   * The location differs by provider: OpenAI-compatible and Anthropic use the
   * `Retry-After` header, while Gemini returns it only in the response body under
   * `error.details[].RetryInfo.retryDelay`. The default handles all of these;
   * override for a provider with a different convention.
   *
   * @return requested delay in milliseconds, if the response states one
   */
  protected def parseRetryDelayMs(response: Response[?], errorBody: String): Option[Long] =
    RetryPolicy.requestedDelayMs(
      response.header("Retry-After").orElse(response.header("retry-after")),
      errorBody
    )

  /** Short reason shown next to an HTTP status in messages. */
  protected def statusReason(code: Int): String = code match {
    case 400 => "bad request"
    case 401 => "invalid API key"
    case 403 => "access denied"
    case 404 => "not found"
    case 408 => "request timeout"
    case 413 => "request too large"
    case 422 => "unprocessable request"
    case 429 => "rate limited"
    case 500 => "server error"
    case 502 => "bad gateway"
    case 503 => "service unavailable"
    case 504 => "gateway timeout"
    case 529 => "overloaded"
    case _   => "error"
  }

  /** One next step for a request the provider rejected outright. */
  protected def rejectedHint(code: Int): String = code match {
    case 401 | 403 => s"Check $apiKeyConfigKey in your config or call llm:set-api-key."
    case 404       => s"Check the model name and $baseUrlConfigKey."
    case 413       => "Shorten the prompt or the conversation history."
    case _         => "Check the model name and request settings in your config."
  }

  /** Short description of a transport failure, e.g. "connection refused". */
  private def transportReason(e: Throwable): String = {
    // Checked most-specific first across the whole chain: a TimeoutException is
    // a ReadException. Unknown hosts never get here; see isUnknownHost.
    val chain = BaseHttpProvider.causes(e)
    def has(p: Throwable => Boolean): Boolean = chain.exists(p)
    if (has {
      case _: SttpClientException.TimeoutException | _: java.net.SocketTimeoutException |
           _: java.net.http.HttpTimeoutException => true
      case _ => false
    }) "timed out"
    else if (has {
      case _: SttpClientException.ConnectException | _: java.net.ConnectException => true
      case _ => false
    }) "connection refused"
    else if (has(_.isInstanceOf[SttpClientException.ReadException])) "connection dropped"
    else "network error"
  }

  /**
   * Send an HTTP request, retrying transient failures with jittered exponential
   * backoff: rate limits (HTTP 429), server errors (500, 502, 503, 504), Anthropic's
   * 529 "overloaded", and connect/read timeouts. Every other error fails at once.
   * All transient failures share the same RetryPolicy, backoff and budget.
   *
   * The delay the provider asks for wins over our own backoff when it is longer,
   * because retrying before a quota window reopens is guaranteed to fail. Waiting
   * is bounded by the policy's total elapsed budget rather than a fixed per-sleep
   * cap, so a quota window longer than the old 10s ceiling can actually be cleared.
   *
   * When a throttle is configured, the whole attempt sequence runs under a single
   * permit. Holding it across retries rather than reacquiring per attempt is what
   * keeps a retrying request from re-entering behind fresh arrivals and pushing
   * the real in-flight count over the cap.
   */
  protected def executeWithRetry(
    httpRequest: Request[Either[String, String]],
    model: String
  ): Future[ChatResponse] = {

    val policy = retryPolicy
    val rng = retryRandom
    val startedAt = System.currentTimeMillis()

    val baseUrl = configStore.get(baseUrlConfigKey).getOrElse(defaultBaseUrl)

    def isRateLimited(code: StatusCode, error: String): Boolean =
      code.code == 429 || error.toLowerCase.contains("rate_limit")

    def details(error: String): String = {
      val trimmed = error.trim
      if (trimmed.isEmpty) ""
      else s" Details: ${if (trimmed.length > 300) trimmed.take(300) + "..." else trimmed}"
    }

    /** Wait and try again if the policy allows, otherwise fail with `exhausted`. */
    def retryOrFail(n: Int, elapsedMs: Long, requestedMs: Option[Long], cause: String)(
      exhausted: => Throwable
    ): Future[ChatResponse] = {
      val delayMs = policy.delayMsFor(n, requestedMs, rng)
      if (policy.canRetry(n, elapsedMs, delayMs)) {
        notifyRetryWait(delayMs, n, policy.maxRetries, cause)
        BaseHttpProvider.delayedFuture(delayMs)(attempt(n + 1, elapsedMs + delayMs))
      } else Future.failed(exhausted)
    }

    def attempt(n: Int, elapsedMs: Long): Future[ChatResponse] =
      httpRequest.send(backend).transformWith {
        case Success(response) =>
          val code = response.code.code
          response.body match {
            case Right(responseBody) =>
              Future.successful(parseProviderResponse(responseBody, model))

            case Left(error) if isRateLimited(response.code, error) =>
              retryOrFail(n, elapsedMs, parseRetryDelayMs(response, error),
                          s"returned HTTP $code (${statusReason(429)})") {
                new RuntimeException(
                  s"$displayName returned HTTP $code (${statusReason(429)}) after ${n + 1} attempts " +
                  f"and ${elapsedMs / 1000.0}%.1fs of waiting. " +
                  "This model's quota may be too low for this simulation - reduce the request " +
                  "rate with the throttling settings, or raise retry_max_elapsed_seconds / " +
                  "retry_max_retries." + details(error))
              }

            case Left(error) if BaseHttpProvider.TransientStatuses.contains(code) =>
              val cause = s"returned HTTP $code (${statusReason(code)})"
              retryOrFail(n, elapsedMs, parseRetryDelayMs(response, error), cause) {
                new RuntimeException(
                  s"$displayName $cause after ${n + 1} attempts. " +
                  "Try again shortly, or lower concurrency with the throttling settings." +
                  details(error))
              }

            case Left(error) =>
              // Report a rejected key as such whatever status carried it.
              val reasonCode = if (BaseHttpProvider.isInvalidApiKey(code, error)) 401 else code
              Future.failed(new HttpStatusException(code, error,
                s"$displayName rejected the request: HTTP $code (${statusReason(reasonCode)}). " +
                rejectedHint(reasonCode) + details(error)))
          }

        case Failure(e) if BaseHttpProvider.isUnknownHost(e) =>
          Future.failed(new RuntimeException(
            s"Could not reach $displayName at $baseUrl (unknown host). " +
            s"Check $baseUrlConfigKey in your config for a typo.", e))

        case Failure(e) if BaseHttpProvider.isTransientFailure(e) =>
          val reason = transportReason(e)
          retryOrFail(n, elapsedMs, None, s"could not be reached ($reason)") {
            new RuntimeException(
              s"Could not reach $displayName at $baseUrl ($reason) after ${n + 1} attempts. " +
              unreachableHint, e)
          }

        case Failure(e) =>
          Future.failed(e)
      }

    val completed = requestThrottle match {
      // One permit covers the complete logical request, including all retries.
      case Some(throttle) => throttle.withPermit(attempt(0, 0L))
      case None           => attempt(0, 0L)
    }

    // Latency is the whole logical call as the modeler waits for it: throttle
    // queueing and rate-limit backoff included. It is stamped even when the
    // provider reported no token counts, so every call still has a record.
    completed.map { response =>
      val elapsedMs = System.currentTimeMillis() - startedAt
      response.copy(usage = Some(response.usage.getOrElse(Usage.empty).copy(latencyMs = Some(elapsedMs))))
    }
  }

  override def setConfig(key: String, value: String): Unit = {
    configStore.set(key, value)
  }

  override def getConfig(key: String): Option[String] = {
    configStore.get(key)
  }

  override def validateConfig(): Try[Unit] = {
    if (requiresApiKey) {
      val hasKey = configStore.get(apiKeyConfigKey).orElse(configStore.get(ConfigStore.API_KEY)).isDefined
      if (!hasKey) {
        Failure(new IllegalStateException(s"$providerName requires an API key"))
      } else {
        Success(())
      }
    } else {
      Success(())
    }
  }

  override def supportsModel(model: String): Boolean = {
    ModelRegistry.isValidModel(providerName, model)
  }

  /**
   * Load configuration from external map
   */
  def loadConfig(config: Map[String, String]): Unit = {
    configStore.updateFromMap(config)
  }

  /**
   * Get configuration summary for debugging
   */
  def getConfigSummary: String = {
    s"$providerName Provider - ${configStore.summary}"
  }
}
