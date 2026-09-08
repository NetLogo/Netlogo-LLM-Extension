// ABOUTME: Abstract base class for HTTP-based LLM providers, consolidating common functionality
// ABOUTME: Reduces boilerplate by providing shared implementation of config, validation, and HTTP request handling
package org.nlogo.extensions.llm.providers

import org.nlogo.extensions.llm.models.{ChatMessage, ChatRequest, ChatResponse, ResponseFormat}
import org.nlogo.extensions.llm.config.ConfigStore
import sttp.client4._
import sttp.client4.httpclient.HttpClientFutureBackend
import sttp.model.{StatusCode, Uri}
import ujson._
import java.util.concurrent.{Executors, ScheduledExecutorService, TimeUnit}
import scala.concurrent.{Future, ExecutionContext, Promise}
import scala.concurrent.duration._
import scala.util.{Try, Success, Failure}

object BaseHttpProvider {
  /** Waits at or above this are announced on stderr so a stall isn't mistaken for a hang. */
  private[providers] val WaitNoticeThresholdMs = 2000L

  // Process-wide counters so a modeler can tell how much of a run was spent
  // sitting out rate limits, rather than guessing from wall-clock time.
  private[providers] val rateLimitWaits = new java.util.concurrent.atomic.LongAdder()
  private[providers] val rateLimitWaitMillis = new java.util.concurrent.atomic.LongAdder()

  /** Number of rate-limit waits that have been announced this session. */
  def rateLimitWaitCount: Long = rateLimitWaits.sum()

  /** Total milliseconds spent waiting out announced rate limits this session. */
  def rateLimitWaitTotalMs: Long = rateLimitWaitMillis.sum()

  /** Reset the rate-limit counters. Primarily for tests and fresh runs. */
  def resetRateLimitStats(): Unit = {
    rateLimitWaits.reset()
    rateLimitWaitMillis.reset()
  }

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

  // Retry policy for rate-limit (HTTP 429) responses. retryBaseDelayMs is a
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
   * Reports a rate-limit wait to the modeler. A silent multi-second stall inside
   * `go` is indistinguishable from a hang, so long waits are announced on stderr.
   * Short waits stay quiet to avoid spamming the console on routine backoff.
   */
  protected def notifyRateLimitWait(delayMs: Long, attempt: Int, maxRetries: Int): Unit =
    if (delayMs >= BaseHttpProvider.WaitNoticeThresholdMs) {
      System.err.println(
        f"NOTE: $providerName rate limited (HTTP 429); waiting ${delayMs / 1000.0}%.1fs " +
        s"before retry ${attempt + 1} of $maxRetries. " +
        "Set retry_max_elapsed_seconds / retry_max_retries to tune, or reduce request rate."
      )
      BaseHttpProvider.rateLimitWaits.increment()
      BaseHttpProvider.rateLimitWaitMillis.add(delayMs)
    }

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
  protected def buildHeaders(apiKey: Option[String]): Map[String, String]
  protected def createProviderRequest(request: ChatRequest): ujson.Value
  protected def parseProviderResponse(responseBody: String, model: String): ChatResponse

  /**
   * Initialize provider-specific default configuration
   */
  protected def initializeDefaults(): Unit = {
    configStore.set(ConfigStore.PROVIDER, providerName)
    configStore.set(ConfigStore.MODEL, defaultModel)
    configStore.set(baseUrlConfigKey, defaultBaseUrl)
    configStore.set(ConfigStore.TEMPERATURE, ConfigStore.DEFAULT_TEMPERATURE)
    configStore.set(ConfigStore.MAX_TOKENS, defaultMaxTokens)
  }

  /**
   * Send a chat request with validation
   */
  override def chat(request: ChatRequest): Future[ChatResponse] = {
    validateConfig() match {
      case Success(_) => sendChatRequest(request)
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

    val baseUrl = configStore.get(baseUrlConfigKey).getOrElse(defaultBaseUrl)
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

  /**
   * Send an HTTP request, retrying rate-limit (HTTP 429) responses with jittered
   * exponential backoff. Only rate-limit errors retry; all others fail immediately.
   *
   * The delay the provider asks for wins over our own backoff when it is longer,
   * because retrying before a quota window reopens is guaranteed to fail. Waiting
   * is bounded by the policy's total elapsed budget rather than a fixed per-sleep
   * cap, so a quota window longer than the old 10s ceiling can actually be cleared.
   */
  protected def executeWithRetry(
    httpRequest: Request[Either[String, String]],
    model: String
  ): Future[ChatResponse] = {

    val policy = retryPolicy
    val rng = retryRandom

    def isRateLimited(code: StatusCode, error: String): Boolean =
      code.code == 429 || error.toLowerCase.contains("rate_limit")

    def attempt(n: Int, elapsedMs: Long): Future[ChatResponse] =
      httpRequest.send(backend).flatMap { response =>
        response.body match {
          case Right(responseBody) =>
            Future.successful(parseProviderResponse(responseBody, model))

          case Left(error) if isRateLimited(response.code, error) =>
            val delayMs = policy.delayMsFor(n, parseRetryDelayMs(response, error), rng)
            if (policy.canRetry(n, elapsedMs, delayMs)) {
              notifyRateLimitWait(delayMs, n, policy.maxRetries)
              BaseHttpProvider.delayedFuture(delayMs)(attempt(n + 1, elapsedMs + delayMs))
            } else {
              Future.failed(new RuntimeException(
                s"HTTP request failed after ${n + 1} attempts " +
                s"(rate limited, HTTP ${response.code.code}) after waiting " +
                f"${elapsedMs / 1000.0}%.1fs: $error. " +
                "This model's quota may be too low for this simulation - reduce the " +
                "request rate, or raise retry_max_elapsed_seconds / retry_max_retries."))
            }

          case Left(error) =>
            Future.failed(new RuntimeException(s"HTTP request failed: $error"))
        }
      }

    attempt(0, 0L)
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
