// ABOUTME: Ollama provider implementation for local Ollama models
// ABOUTME: Handles API communication with local Ollama server using BaseHttpProvider

package org.nlogo.extensions.llm.providers

import org.nlogo.extensions.llm.models.{ChatMessage, ChatRequest, ChatResponse, EnumFormat, JsonObjectFormat, JsonSchemaFormat, ResponseFormat}
import org.nlogo.extensions.llm.config.ConfigStore
import sttp.client4._
import sttp.model.Uri
import ujson._
import scala.concurrent.{Future, ExecutionContext}

/**
 * Ollama provider implementation for local Ollama models
 *
 * Extends BaseHttpProvider to reduce boilerplate. Ollama runs locally and
 * does not require an API key.
 */
class OllamaProvider(implicit ec: ExecutionContext) extends BaseHttpProvider {

  override def providerName: String = "ollama"
  override def defaultModel: String = ModelRegistry.defaultModel("ollama")
  override protected def defaultBaseUrl: String = ConfigStore.DEFAULT_OLLAMA_BASE_URL
  override protected def baseUrlConfigKey: String = ConfigStore.OLLAMA_BASE_URL
  override protected def apiKeyConfigKey: String = ConfigStore.API_KEY
  override protected def defaultMaxTokens: String = "2048"
  override protected def requiresApiKey: Boolean = false

  override protected def buildApiUrl(baseUrl: String): Uri = {
    uri"$baseUrl/api/chat"
  }

  override protected def buildHeaders(apiKey: Option[String]): Map[String, String] = {
    Map("Content-Type" -> "application/json")
  }

  override protected def createProviderRequest(request: ChatRequest): ujson.Value = {
    val messages = ujson.Arr(
      request.messages.map { msg =>
        ujson.Obj(
          "role" -> msg.role,
          "content" -> msg.content
        )
      }*
    )

    val baseRequest = ujson.Obj(
      "model" -> request.model,
      "messages" -> messages,
      "stream" -> false
    )

    // Enable thinking for reasoning models
    if (request.thinkingConfig.exists(_.enabled)) {
      baseRequest("think") = true
    }

    // Ollama takes the raw schema in `format`, or the bare string "json" for
    // schemaless JSON mode. Enforcement is grammar-level at the sampling layer,
    // so it is independent of `think`.
    request.responseFormat.foreach {
      case JsonSchemaFormat(schema, _)  => baseRequest("format") = ResponseFormat.strictSchema(schema)
      case enumFormat: EnumFormat       => baseRequest("format") = enumFormat.schema
      case JsonObjectFormat             => baseRequest("format") = "json"
    }

    // Add options if parameters are specified
    val options = ujson.Obj()
    var hasOptions = false

    request.temperature.foreach { temp =>
      options("temperature") = temp
      hasOptions = true
    }

    request.maxTokens.foreach { maxTokens =>
      options("num_predict") = maxTokens
      hasOptions = true
    }

    if (hasOptions) {
      baseRequest("options") = options
    }

    baseRequest
  }

  override protected def parseProviderResponse(responseBody: String, model: String): ChatResponse = {
    try {
      val parsed = ujson.read(responseBody)

      val id = s"ollama-${System.currentTimeMillis()}"
      val created = System.currentTimeMillis() / 1000

      val message = parsed("message")
      val role = message("role").str
      val content = message("content").str
      val doneReason = if (parsed("done").bool) "stop" else "length"

      // Extract thinking text if present (Ollama returns it in message.thinking)
      val thinking = if (scala.util.Try(message.obj.contains("thinking")).getOrElse(false)) {
        scala.util.Try(message("thinking").str).toOption.orElse {
          System.err.println(s"WARNING: Ollama response has 'thinking' field but it could not be parsed as string")
          None
        }.filter(_.nonEmpty)
      } else None

      val choices = Array(
        org.nlogo.extensions.llm.models.Choice(
          index = 0,
          message = ChatMessage(role, content),
          finishReason = doneReason
        )
      )

      ChatResponse(id, created, model, choices, thinking = thinking)
    } catch {
      case e: Exception =>
        throw new RuntimeException(s"Failed to parse Ollama response: ${e.getMessage}\nResponse: $responseBody", e)
    }
  }

  /**
   * Check if Ollama server is accessible
   */
  def checkServerConnection(): Future[Boolean] = {
    val baseUrl = configStore.get(ConfigStore.OLLAMA_BASE_URL)
      .getOrElse("http://localhost:11434")
    val apiUrl = uri"$baseUrl/api/tags"

    val httpRequest = basicRequest.get(apiUrl)

    httpRequest.send(backend).map { response =>
      response.isSuccess
    }.recover {
      case ex =>
        System.err.println(s"WARNING: Ollama server connection check failed: ${ex.getMessage}")
        false
    }
  }

  /**
   * List installed models from Ollama server
   * 
   * @return Future containing set of installed model names
   */
  def listInstalledModels(): Future[Set[String]] = {
    val baseUrl = configStore.get(ConfigStore.OLLAMA_BASE_URL)
      .getOrElse("http://localhost:11434")
    val apiUrl = uri"$baseUrl/api/tags"

    val httpRequest = basicRequest.get(apiUrl)

    httpRequest.send(backend).map { response =>
      response.body match {
        case Right(responseBody) =>
          try {
            val parsed = ujson.read(responseBody)
            val models = parsed("models").arr
            models.map { model =>
              model("name").str
            }.toSet
          } catch {
            case ex: Exception =>
              System.err.println(s"WARNING: Failed to parse Ollama model list response: ${ex.getMessage}")
              Set.empty[String]
          }
        case Left(error) =>
          System.err.println(s"WARNING: Ollama model list request returned error: $error")
          Set.empty[String]
      }
    }.recover {
      case ex =>
        System.err.println(s"WARNING: Ollama model list request failed: ${ex.getMessage}")
        Set.empty[String]
    }
  }
}
