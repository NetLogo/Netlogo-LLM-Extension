package org.nlogo.extensions.llm.providers

import org.nlogo.extensions.llm.config.ConfigStore
import org.nlogo.extensions.llm.models.{ChatMessage, ChatRequest, ChatResponse, Choice}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Try}

/**
 * Deterministic provider used only by tests.
 *
 * It avoids external API/network calls and returns predictable outputs
 * based on the latest user message. Supports special prefixes to control
 * response behavior for testing edge cases.
 */
class DeterministicTestProvider(implicit ec: ExecutionContext) extends LLMProvider {
  private val configStore = new ConfigStore()
  private val firstOptionRegex = """(?m)^(.+?)$""".r
  private val testRespondRegex = """__TEST_RESPOND:(.+)""".r
  private val testThinkingRegex = """__TEST_THINKING:(.+)""".r
  private val testDelayRegex = """__TEST_DELAY:(\d+):(.*)""".r.unanchored

  override def chat(request: ChatRequest): Future[ChatResponse] = {
    chat(request.messages).map { message =>
      ChatResponse.simple(
        id = "deterministic-test-response",
        model = request.model,
        message = message
      )
    }
  }

  override def chatWithFullResponse(messages: Seq[ChatMessage]): Future[ChatResponse] = {
    val lastUserMessage = messages.reverseIterator
      .find(_.role == "user")
      .map(_.content)
      .getOrElse("")

    // Check for __TEST_THINKING: prefix — return empty content + thinking field
    lastUserMessage match {
      case msg if msg.contains("__TEST_THINKING:") =>
        val thinking = testThinkingRegex.findFirstMatchIn(msg).map(_.group(1).trim).getOrElse("")
        Future.successful(ChatResponse(
          id = "deterministic-test-response",
          created = System.currentTimeMillis() / 1000,
          model = "deterministic-model",
          choices = Array(Choice(0, ChatMessage.assistant(""), "stop")),
          thinking = Some(thinking)
        ))
      case _ =>
        // Default: delegate to chat() and wrap as ChatResponse
        chat(messages).map { message =>
          ChatResponse.simple(
            id = "deterministic-test-response",
            model = "deterministic-model",
            message = message
          )
        }
    }
  }

  override def chat(messages: Seq[ChatMessage]): Future[ChatMessage] = {
    val lastUserMessage = messages.reverseIterator
      .find(_.role == "user")
      .map(_.content)
      .getOrElse("")

    // Simulate a provider failure so failure-path history handling can be tested
    if (lastUserMessage.contains("__TEST_FAIL")) {
      return Future.failed(new RuntimeException("simulated provider failure"))
    }

    // Simulate latency so overlapping async calls can be tested deterministically.
    // __TEST_DELAY:<ms>:<rest> sleeps <ms> then responds as if prompted with <rest>.
    lastUserMessage match {
      case testDelayRegex(ms, rest) =>
        Future { Thread.sleep(ms.toLong) }.map(_ => respond(rest))
      case _ =>
        Future.successful(respond(lastUserMessage))
    }
  }

  private def respond(lastUserMessage: String): ChatMessage = {
    val content = if (lastUserMessage.contains("__TEST_RESPOND:")) {
      // Return exactly the specified text
      testRespondRegex.findFirstMatchIn(lastUserMessage).map(_.group(1).trim).getOrElse("")
    } else if (lastUserMessage.contains("__TEST_EMPTY_CONTENT")) {
      // Return empty content (simulates thinking model with no content)
      ""
    } else if (lastUserMessage.contains("Your choice (one option, no other text):")) {
      // Choose prompt — extract and return the first option from the Options block
      val optionsIdx = lastUserMessage.indexOf("Options:\n")
      if (optionsIdx >= 0) {
        val optionsBlock = lastUserMessage.substring(optionsIdx + "Options:\n".length)
        val choiceEnd = optionsBlock.indexOf("\n\n")
        val firstLine = if (choiceEnd >= 0) {
          optionsBlock.substring(0, choiceEnd).split("\n").headOption.getOrElse("")
        } else {
          optionsBlock.split("\n").headOption.getOrElse("")
        }
        firstLine.trim
      } else {
        "1"
      }
    } else {
      s"stub:$lastUserMessage"
    }

    ChatMessage.assistant(content)
  }

  override def setConfig(key: String, value: String): Unit = {
    configStore.set(key, value)
  }

  override def getConfig(key: String): Option[String] = {
    configStore.get(key)
  }

  override def validateConfig(): Try[Unit] = Success(())

  override def providerName: String = "deterministic-test"

  override def defaultModel: String = "deterministic-model"

  override def supportsModel(model: String): Boolean = true
}
