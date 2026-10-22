package org.nlogo.extensions.llm.providers

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Try}

import org.nlogo.extensions.llm.config.ConfigStore
import org.nlogo.extensions.llm.models.{ChatMessage, ChatRequest, ChatResponse, Choice, DecisionQuestion, DecisionResult, EnumFormat, JsonObjectFormat, JsonSchemaFormat, ResponseFormat, Usage}

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
  // Captures to end of line so a raw choice may contain spaces, braces, or quotes.
  private val testRawChoiceRegex = """__TEST_RAW_CHOICE:(.*)""".r
  // __TEST_USAGE:<in>,<out>[,<reasoning>] attaches a usage record to whatever
  // response the rest of the stub produces, so accounting can be asserted
  // independently of which primitive made the call.
  private val testUsageRegex = """__TEST_USAGE:(\d+),(\d+)(?:,(\d+))?""".r.unanchored
  // __TEST_REFUSE:<text> answers with empty content and a refusal, the shape a
  // provider returns when the model declines. Captures to end of line.
  private val testRefuseRegex = """__TEST_REFUSE:(.+)""".r
  // __TEST_TRUNCATE answers with empty content and finish_reason "length", the
  // shape a reasoning model returns when it spends the whole budget thinking.

  private def lastUserContent(messages: Seq[ChatMessage]): String =
    messages.reverseIterator.find(_.role == "user").map(_.content).getOrElse("")

  private def withTestUsage(lastUserMessage: String, response: ChatResponse): ChatResponse =
    lastUserMessage match {
      case testUsageRegex(in, out, reasoning) =>
        val i = in.toLong
        val o = out.toLong
        response.copy(usage = Some(Usage(
          inputTokens = i,
          outputTokens = o,
          totalTokens = i + o,
          reasoningTokens = Option(reasoning).map(_.toLong)
        )))
      case _ => response
    }

  override def chat(request: ChatRequest): Future[ChatResponse] = {
    chat(request.messages).map { message =>
      withTestUsage(lastUserContent(request.messages), ChatResponse.simple(
        id = "deterministic-test-response",
        model = request.model,
        message = message
      ))
    }
  }

  override def chatWithFullResponse(messages: Seq[ChatMessage]): Future[ChatResponse] =
    fullResponse(messages).map(withTestUsage(lastUserContent(messages), _))

  private def fullResponse(messages: Seq[ChatMessage]): Future[ChatResponse] = {
    val lastUserMessage = messages.reverseIterator
      .find(_.role == "user")
      .map(_.content)
      .getOrElse("")

    // Check for __TEST_THINKING: prefix — return empty content + thinking field
    lastUserMessage match {
      case msg if msg.contains("__TEST_TRUNCATE") =>
        Future.successful(ChatResponse(
          id = "deterministic-test-response",
          created = System.currentTimeMillis() / 1000,
          model = "deterministic-model",
          choices = Array(Choice(0, ChatMessage.assistant(""), "length"))
        ))
      case msg if msg.contains("__TEST_REFUSE:") =>
        val refusal = testRefuseRegex.findFirstMatchIn(msg).map(_.group(1).trim).getOrElse("")
        Future.successful(ChatResponse(
          id = "deterministic-test-response",
          created = System.currentTimeMillis() / 1000,
          model = "deterministic-model",
          choices = Array(Choice(0, ChatMessage.assistant(""), "", refusal = Some(refusal)))
        ))
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

  /**
   * Stands in for a provider that honours the requested format.
   *
   * An explicit `__TEST_RESPOND:` still wins, so a test can supply the exact
   * reply text — including a deliberately malformed one. Otherwise an enum
   * request answers in the constrained `{"choice": ...}` shape, which is what
   * a real provider enforcing the constraint returns.
   */
  override def chatWithFormat(messages: Seq[ChatMessage], format: ResponseFormat): Future[ChatResponse] =
    formatResponse(messages, format).map(withTestUsage(lastUserContent(messages), _))

  private def formatResponse(messages: Seq[ChatMessage], format: ResponseFormat): Future[ChatResponse] = {
    val lastUserMessage = messages.reverseIterator
      .find(_.role == "user")
      .map(_.content)
      .getOrElse("")

    if (lastUserMessage.contains("__TEST_RESPOND:") || lastUserMessage.contains("__TEST_FAIL") ||
        lastUserMessage.contains("__TEST_REFUSE:")) {
      return chatWithFullResponse(messages)
    }

    // __TEST_ECHO_FORMAT* markers report which format actually arrived, so a
    // primitive that stopped constraining its request fails a test instead of
    // silently degrading. The enum echo answers with the LAST choice because
    // the unconstrained prompt-only path answers with the first.
    // __TEST_ECHO_PROMPT_TAIL answers with the prompt's final line so a test can
    // pin the instruction the model actually sees next to the schema it is sent.
    if (lastUserMessage.contains("__TEST_ECHO_PROMPT_TAIL")) {
      return Future.successful(structured(lastUserMessage.linesIterator.toSeq.last))
    }

    if (lastUserMessage.contains("__TEST_ECHO_FORMAT_ENUM")) {
      return Future.successful(structured(format match {
        case EnumFormat(choices) => choices.last
        case _                   => "__NO_ENUM_FORMAT_RECEIVED__"
      }))
    }

    if (lastUserMessage.contains("__TEST_ECHO_FORMAT")) {
      return Future.successful(structured(format match {
        case JsonObjectFormat => ujson.Obj("format" -> "json_object").toString
        case JsonSchemaFormat(schema, name) =>
          // Echo the schema exactly as handed over. Normalizing here would make
          // the assertion test this stub rather than the primitive, and it would
          // stay green even if every real provider stopped normalizing.
          ujson.Obj(
            "format" -> "json_schema",
            "name" -> name,
            "topLevelType" -> schema.value.get("type").getOrElse(ujson.Null)
          ).toString
        case EnumFormat(_) => ujson.Obj("format" -> "enum").toString
      }))
    }

    // __TEST_IGNORE_FORMAT simulates a provider that cannot enforce the
    // requested format and answers in free text anyway — the graceful
    // degradation path every caller has to survive. __TEST_RAW_CHOICE:<text>
    // pins that free-text reply so a specific shape can be asserted.
    if (lastUserMessage.contains("__TEST_IGNORE_FORMAT")) {
      val raw = testRawChoiceRegex.findFirstMatchIn(lastUserMessage)
        .map(_.group(1).trim)
        .getOrElse(respond(lastUserMessage).content)
      return Future.successful(structured(raw))
    }

    format match {
      case EnumFormat(choices) =>
        val body = ujson.Obj(EnumFormat.ChoiceKey -> choices.head).toString
        Future.successful(structured(body))
      case JsonSchemaFormat(_, _) =>
        Future.successful(structured("{}"))
      case JsonObjectFormat =>
        Future.successful(structured("{}"))
    }
  }

  private def structured(content: String): ChatResponse =
    ChatResponse.simple(
      id = "deterministic-test-response",
      model = "deterministic-model",
      message = ChatMessage.assistant(content)
    )

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
    } else if (lastUserMessage.contains("__TEST_ECHO_MODEL")) {
      // Reveal which configuration served this call, so profile routing can
      // be asserted: the model is the one field every profile fixture sets.
      configStore.get(ConfigStore.MODEL).getOrElse("__NO_MODEL_CONFIGURED__")
    } else if (lastUserMessage.contains("Options:\n") && lastUserMessage.contains("Your choice as {")) {
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

/**
 * Deterministic stand-in for a decision provider (TypeSafe), used by tests
 * whenever the configured provider is "typesafe".
 *
 * Kept separate from DeterministicTestProvider so llm:choose on every other
 * provider still exercises the chat path. Answers are prompt-driven:
 *  - __TEST_DECIDE:<answer>:<p> puts probability <p> on <answer> and spreads
 *    the rest evenly over the other outcomes. <answer> is an option for a
 *    choice, a 0-based level index for a score, and true/false for yes-no.
 *  - With no marker the first option, level 0, or yes gets probability 1.
 *  - __TEST_FAIL fails the call; __TEST_DELAY:<ms>: delays it; __TEST_USAGE
 *    attaches usage, as for chat.
 * Free-text chat is refused through textUnsupportedMessage, as on TypeSafe.
 */
class DeterministicDecisionTestProvider(implicit ec: ExecutionContext)
  extends DeterministicTestProvider with DecisionProvider {

  private val testDecideRegex = """__TEST_DECIDE:([^:\s]+):([0-9.]+)""".r.unanchored
  private val testDecisionDelayRegex = """__TEST_DELAY:(\d+):""".r.unanchored
  private val testDecisionUsageRegex = """__TEST_USAGE:(\d+),(\d+)""".r.unanchored

  override def providerName: String = "typesafe"

  override def defaultModel: String = "jev-latest"

  override def textUnsupportedMessage: Option[String] = Some(TypeSafeProvider.TextUnsupportedMessage)

  /** `p` on outcome `picked` of `n`, the remainder shared evenly by the others. */
  private def spread(n: Int, picked: Int, p: Double): Seq[Double] =
    (0 until n).map(i => if (i == picked) p else if (n > 1) (1.0 - p) / (n - 1) else 0.0)

  private def answer(prompt: String, question: DecisionQuestion): DecisionResult = {
    val marker = prompt match {
      case testDecideRegex(a, p) => Some((a, p.toDouble))
      case _                     => None
    }
    question match {
      case DecisionQuestion.Choice(options) =>
        val (picked, p) = marker.getOrElse((options.head, 1.0))
        val probs = spread(options.size, options.indexOf(picked), p)
        DecisionResult.choice(options, picked, options.zip(probs).toMap)
      case DecisionQuestion.Score(levels) =>
        val (level, p) = marker.map { case (a, q) => (a.toInt, q) }.getOrElse((0, 1.0))
        val probs = spread(levels.size, level, p)
        val expected = probs.zipWithIndex.map { case (q, i) => q * i }.sum
        DecisionResult.score(levels, expected, p, probs)
      case DecisionQuestion.YesNo =>
        val (yes, p) = marker.map { case (a, q) => (a.toBoolean, q) }.getOrElse((true, 1.0))
        DecisionResult.yesNo(if (yes) p else 1.0 - p)
    }
  }

  override def decide(prompt: String, question: DecisionQuestion): Future[(DecisionResult, Usage)] = {
    val usage = prompt match {
      case testDecisionUsageRegex(in, out) => Usage(in.toLong, out.toLong, in.toLong + out.toLong)
      case _                               => Usage.empty
    }
    if (prompt.contains("__TEST_FAIL")) Future.failed(new RuntimeException("simulated provider failure"))
    else prompt match {
      case testDecisionDelayRegex(ms) =>
        Future { Thread.sleep(ms.toLong) }.map(_ => (answer(prompt, question), usage))
      case _ =>
        Future.successful((answer(prompt, question), usage))
    }
  }
}
