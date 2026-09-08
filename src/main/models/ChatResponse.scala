package org.nlogo.extensions.llm.models

import upickle.default.{ReadWriter => RW, macroRW}

/**
 * Represents a response choice from an LLM provider
 *
 * @param index The index of this choice (usually 0 for single responses)
 * @param message The message content of the response
 * @param finishReason The reason the response ended (e.g., "stop", "length")
 */
case class Choice(
  index: Int,
  message: ChatMessage,
  finishReason: String
)

object Choice {
  implicit val rw: RW[Choice] = macroRW
}

/**
 * Token accounting for one provider call, normalised across providers.
 *
 * Field names follow the OpenTelemetry GenAI conventions (gen_ai.usage.*):
 *  - inputTokens counts every prompt token the provider processed, including
 *    cached ones, so the number is comparable across providers that report
 *    cached tokens as a subset (OpenAI, Gemini) and one that reports them
 *    separately (Anthropic).
 *  - outputTokens counts every generated token including reasoning, for the
 *    same reason: OpenAI folds reasoning into completion tokens, Gemini does
 *    not.
 *  - totalTokens is taken from the provider when reported, else computed.
 *  - reasoning / cache counts are absent when the provider does not report them.
 *  - cost is only present when a provider reports it directly (OpenRouter);
 *    nothing is ever estimated.
 *  - latencyMs is the wall time of the whole call as the modeler experiences
 *    it, including throttle queueing and rate-limit retries.
 */
case class Usage(
  inputTokens: Long,
  outputTokens: Long,
  totalTokens: Long,
  reasoningTokens: Option[Long] = None,
  cacheReadTokens: Option[Long] = None,
  cacheWriteTokens: Option[Long] = None,
  cost: Option[Double] = None,
  latencyMs: Option[Long] = None
) {
  /** Field-wise sum. An optional field stays absent only when neither side has it. */
  def plus(other: Usage): Usage = {
    def sumOpt[A](a: Option[A], b: Option[A])(add: (A, A) => A): Option[A] =
      (a, b) match {
        case (Some(x), Some(y)) => Some(add(x, y))
        case (Some(x), None)    => Some(x)
        case (None, Some(y))    => Some(y)
        case (None, None)       => None
      }
    Usage(
      inputTokens = inputTokens + other.inputTokens,
      outputTokens = outputTokens + other.outputTokens,
      totalTokens = totalTokens + other.totalTokens,
      reasoningTokens = sumOpt(reasoningTokens, other.reasoningTokens)(_ + _),
      cacheReadTokens = sumOpt(cacheReadTokens, other.cacheReadTokens)(_ + _),
      cacheWriteTokens = sumOpt(cacheWriteTokens, other.cacheWriteTokens)(_ + _),
      cost = sumOpt(cost, other.cost)(_ + _),
      latencyMs = sumOpt(latencyMs, other.latencyMs)(_ + _)
    )
  }
}

object Usage {
  implicit val rw: RW[Usage] = macroRW

  val empty: Usage = Usage(0L, 0L, 0L)

  /** Read an optional integer field from a JSON object; absence and null read alike. */
  def longField(obj: ujson.Value, key: String): Option[Long] =
    obj match {
      case o: ujson.Obj => o.value.get(key).collect { case ujson.Num(n) => n.toLong }
      case _            => None
    }

  /** Read a nested optional integer, e.g. usage.prompt_tokens_details.cached_tokens. */
  def nestedLongField(obj: ujson.Value, path: String*): Option[Long] =
    path.dropRight(1).foldLeft(Option(obj)) { (cur, key) =>
      cur.flatMap {
        case o: ujson.Obj => o.value.get(key)
        case _            => None
      }
    }.flatMap(longField(_, path.last))
}

/**
 * Represents a complete chat response from an LLM provider
 *
 * @param id Unique identifier for this response
 * @param created Timestamp when the response was created
 * @param model The model that generated the response
 * @param choices Array of response choices (usually contains one choice)
 * @param thinking Reasoning text returned separately from the answer, if any
 * @param usage Token accounting for this call, when the provider reported it
 */
case class ChatResponse(
  id: String,
  created: Long,
  model: String,
  choices: Array[Choice],
  thinking: Option[String] = None,
  usage: Option[Usage] = None
) {
  /**
   * Get the first (and usually only) response message
   */
  def firstMessage: Option[ChatMessage] = {
    choices.headOption.map(_.message)
  }

  /**
   * Get the content of the first response message
   */
  def firstContent: Option[String] = {
    firstMessage.map(_.content)
  }
}

object ChatResponse {
  implicit val rw: RW[ChatResponse] = macroRW

  /**
   * Create a simple response with a single message
   */
  def simple(id: String, model: String, message: ChatMessage): ChatResponse = {
    ChatResponse(
      id = id,
      created = System.currentTimeMillis() / 1000,
      model = model,
      choices = Array(Choice(0, message, "stop"))
    )
  }
}
