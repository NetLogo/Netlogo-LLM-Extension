// ABOUTME: Tests that a requested response format reaches the provider's request body unchanged
// ABOUTME: Covers the LLMProvider entry point used by llm:chat-with-schema and llm:chat-json
package org.nlogo.extensions.llm.providers

import org.nlogo.extensions.llm.config.ConfigStore
import org.nlogo.extensions.llm.models._
import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.ExecutionContext.Implicits.global

/**
 * Captures the ChatRequest that would have been sent, without any network I/O.
 */
private class CapturingProvider extends OllamaProvider {
  var captured: Option[ChatRequest] = None
  override def chat(request: ChatRequest): scala.concurrent.Future[ChatResponse] = {
    captured = Some(request)
    scala.concurrent.Future.successful(
      ChatResponse.simple("id", request.model, ChatMessage.assistant("{}"))
    )
  }
}

class StructuredChatPlumbingSpec extends AnyFunSuite {

  ProviderRegistry.reset()
  ProviderRegistrations.registerAll()

  private val messages = Seq(ChatMessage.user("hi"))
  private val schema = ResponseFormat.parseSchema(
    """{"type":"object","properties":{"a":{"type":"string"}}}"""
  )

  test("chatWithFormat forwards the requested format to the provider request") {
    val provider = new CapturingProvider
    provider.chatWithFormat(messages, schema)
    assert(provider.captured.flatMap(_.responseFormat).contains(schema))
  }

  test("chatWithFormat still applies configured model and token settings") {
    val provider = new CapturingProvider
    provider.setConfig(ConfigStore.MODEL, "llama3.1")
    provider.setConfig(ConfigStore.MAX_TOKENS, "1234")
    provider.chatWithFormat(messages, JsonObjectFormat)

    val request = provider.captured.get
    assert(request.model == "llama3.1")
    assert(request.maxTokens.contains(1234))
  }

  test("the ordinary chat path still sends no response format") {
    val provider = new CapturingProvider
    provider.chat(messages)
    assert(provider.captured.get.responseFormat.isEmpty)
  }

  test("chatWithFormat returns the full response so callers can read content") {
    val provider = new CapturingProvider
    val response = scala.concurrent.Await.result(
      provider.chatWithFormat(messages, JsonObjectFormat),
      scala.concurrent.duration.Duration(5, "seconds")
    )
    assert(response.firstContent.contains("{}"))
  }
}
