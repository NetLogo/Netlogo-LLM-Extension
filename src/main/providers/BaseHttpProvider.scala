// ABOUTME: Abstract base class for HTTP-based LLM providers, consolidating common functionality
// ABOUTME: Reduces boilerplate by providing shared implementation of config, validation, and HTTP request handling
package org.nlogo.extensions.llm.providers

import org.nlogo.extensions.llm.models.{ChatMessage, ChatRequest, ChatResponse}
import org.nlogo.extensions.llm.config.ConfigStore
import sttp.client4._
import sttp.client4.httpclient.HttpClientFutureBackend
import sttp.model.{StatusCode, Uri}
import ujson._
import java.util.concurrent.{Executors, ScheduledExecutorService, TimeUnit}
import scala.concurrent.{Future, ExecutionContext, Promise}
import scala.util.{Try, Success, Failure}

object BaseHttpProvider {
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
  private val MaxRetries = 3
  private val MaxDelayMs = 10000L
  protected def retryBaseDelayMs: Long = 1000L

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
  protected def buildRequest(messages: Seq[ChatMessage]): ChatRequest = {
    val model = configStore.getOrElse(ConfigStore.MODEL, defaultModel)
    val temperature = configStore.get(ConfigStore.TEMPERATURE).map(_.toDouble)
    val maxTokens = configStore.get(ConfigStore.MAX_TOKENS).map(_.toInt)
    val thinkingConfig = ReasoningModelDetector.resolveThinkingConfig(providerName, model, configStore)

    ChatRequest(
      model = model,
      messages = messages,
      maxTokens = maxTokens,
      temperature = temperature,
      thinkingConfig = thinkingConfig
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
   * Send an HTTP request, retrying rate-limit (HTTP 429) responses with
   * exponential backoff. Only rate-limit errors retry; all others fail
   * immediately. Honors the server's Retry-After header when it asks for a
   * longer wait than the backoff, capped so it can't exceed the request budget.
   */
  protected def executeWithRetry(
    httpRequest: Request[Either[String, String]],
    model: String
  ): Future[ChatResponse] = {

    def isRateLimited(code: StatusCode, error: String): Boolean =
      code.code == 429 || error.toLowerCase.contains("rate_limit")

    def delayFor(response: Response[?], attempt: Int): Long = {
      val backoff = retryBaseDelayMs << attempt // 1s, 2s, 4s, ...
      val retryAfterMs = response.header("Retry-After")
        .flatMap(_.trim.toLongOption)
        .map(_ * 1000L)
      math.min(retryAfterMs.fold(backoff)(math.max(_, backoff)), MaxDelayMs)
    }

    def attempt(n: Int): Future[ChatResponse] =
      httpRequest.send(backend).flatMap { response =>
        response.body match {
          case Right(responseBody) =>
            Future.successful(parseProviderResponse(responseBody, model))
          case Left(error) if isRateLimited(response.code, error) && n < MaxRetries =>
            BaseHttpProvider.delayedFuture(delayFor(response, n))(attempt(n + 1))
          case Left(error) if isRateLimited(response.code, error) =>
            Future.failed(new RuntimeException(
              s"HTTP request failed after ${MaxRetries + 1} attempts " +
              s"(rate limited, HTTP ${response.code.code}): $error"))
          case Left(error) =>
            Future.failed(new RuntimeException(s"HTTP request failed: $error"))
        }
      }

    attempt(0)
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
