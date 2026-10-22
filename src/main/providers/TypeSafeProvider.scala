// ABOUTME: TypeSafe provider — the Jev System One API, which answers typed questions with probabilities
// ABOUTME: Implements DecisionProvider (choice, score, noul) over BaseHttpProvider's retry and throttle path
package org.nlogo.extensions.llm.providers

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

import sttp.client4._
import sttp.model.Uri

import org.nlogo.extensions.llm.config.ConfigStore
import org.nlogo.extensions.llm.models.{ChatMessage, ChatRequest, ChatResponse, Choice, DecisionQuestion, DecisionResult, Usage}

object TypeSafeProvider {
  /** Raised by every free-text primitive while TypeSafe is the active provider. */
  val TextUnsupportedMessage: String =
    "typesafe (Jev) returns decisions, not text; use llm:decide, llm:decide-all or llm:choose"

  /** USD per input token for every current Jev model ($0.042 per million); output tokens are free. */
  val InputTokenPriceUsd: Double = 0.042e-6

  /** Name of the single question each request asks; answers come back under it. */
  private[providers] val QuestionId: String = "decision"
}

/**
 * TypeSafe provider implementation.
 *
 * Jev, TypeSafe's System One model, does not generate text. A request sends
 * `state` (here, the prompt) and named, typed questions; the reply is a typed
 * answer per question with calibrated probabilities. Choice, Score and Noul
 * questions map onto DecisionQuestion's Choice, Score and YesNo.
 *
 * Requests go through BaseHttpProvider.executeWithRetry, so the throttle, the
 * 429/529 retry, friendly HTTP errors and latency stamping all apply as for
 * chat. That path carries a ChatResponse, so parseProviderResponse puts the raw
 * reply body in the message content and decide() reads the answer from it.
 *
 * Docs: https://docs.typesafe.ai/api/system-one.md
 */
class TypeSafeProvider(implicit ec: ExecutionContext) extends BaseHttpProvider with DecisionProvider {
  import TypeSafeProvider._

  override def providerName: String = "typesafe"

  override def defaultModel: String = "jev-latest"

  override protected def defaultBaseUrl: String = "https://api.typesafe.ai"

  override protected def baseUrlConfigKey: String = "typesafe_base_url"

  override protected def apiKeyConfigKey: String = "typesafe_api_key"

  // Unused: Jev takes no max-token setting. BaseHttpProvider stores a default.
  override protected def defaultMaxTokens: String = "1000"

  override protected def requiresApiKey: Boolean = true

  override def textUnsupportedMessage: Option[String] = Some(TextUnsupportedMessage)

  override protected def buildApiUrl(baseUrl: String): Uri = uri"$baseUrl/v1/systemone"

  override protected def buildHeaders(apiKey: Option[String]): Map[String, String] =
    Map("Content-Type" -> "application/json") ++
      apiKey.map(key => "Authorization" -> s"Bearer $key")

  /** Free-text chat is not offered; fails before any request is sent. */
  override def chat(request: ChatRequest): Future[ChatResponse] =
    Future.failed(new UnsupportedOperationException(TextUnsupportedMessage))

  override protected def createProviderRequest(request: ChatRequest): ujson.Value =
    throw new UnsupportedOperationException(TextUnsupportedMessage)

  /** Wrap the raw body so it can travel the shared retry path; decide() parses it. */
  override protected def parseProviderResponse(responseBody: String, model: String): ChatResponse = {
    val usage = scala.util.Try(ujson.read(responseBody)).toOption.flatMap(_.obj.get("usage")).map { u =>
      val in = Usage.longField(u, "input_tokens").getOrElse(0L)
      val out = Usage.longField(u, "output_tokens").getOrElse(0L)
      Usage(in, out, in + out, cost = Some(in * InputTokenPriceUsd))
    }
    ChatResponse(
      id = "typesafe-systemone",
      created = System.currentTimeMillis() / 1000,
      model = model,
      choices = Array(Choice(0, ChatMessage.assistant(responseBody), "stop")),
      usage = usage
    )
  }

  /** The System One request body for one question about `prompt`. */
  private[providers] def decisionRequestBody(prompt: String, question: DecisionQuestion, model: String): ujson.Obj = {
    val q = question match {
      case DecisionQuestion.Choice(options) =>
        ujson.Obj(
          "type" -> "choice",
          "instructions" -> "Choose the option that best fits the state.",
          // An option without a description is interpreted by its name alone.
          "criteria" -> ujson.Obj.from(options.map(o => o -> ujson.Null))
        )
      case DecisionQuestion.Score(levels) =>
        ujson.Obj(
          "type" -> "score",
          "instructions" -> "Rate the state on these levels.",
          "criteria" -> ujson.Arr.from(levels.map(ujson.Str(_)))
        )
      case DecisionQuestion.YesNo =>
        ujson.Obj(
          "type" -> "noul",
          "instructions" -> "Answer the question or judge the statement in the state: yes (true) or no (false)."
        )
    }
    ujson.Obj("model" -> model, "state" -> prompt, "questions" -> ujson.Obj(QuestionId -> q))
  }

  /** Read the answer to `question` out of a System One reply body. */
  private[providers] def parseDecision(body: String, question: DecisionQuestion): DecisionResult = {
    val answer =
      try ujson.read(body)("answers")(QuestionId)
      catch {
        case e: Exception =>
          throw new RuntimeException(s"$displayName reply had no answer to the question: ${e.getMessage}")
      }
    def probabilities: Map[String, Double] =
      answer.obj.get("probabilities").map(_.obj.view.mapValues(_.num).toMap).getOrElse(Map.empty)

    try question match {
      case DecisionQuestion.Choice(options) =>
        DecisionResult.choice(options, answer("choice").str, probabilities)
      case DecisionQuestion.Score(levels) =>
        val probs = probabilities
        DecisionResult.score(
          levels,
          answer("score").num,
          answer.obj.get("confidence").map(_.num).getOrElse(0.0),
          levels.indices.map(i => probs.getOrElse(i.toString, 0.0))
        )
      case DecisionQuestion.YesNo =>
        DecisionResult.yesNo(answer("noul").num)
    } catch {
      case e: Exception =>
        throw new RuntimeException(s"$displayName reply could not be read: ${e.getMessage}. Reply: ${body.take(300)}")
    }
  }

  override def decide(prompt: String, question: DecisionQuestion): Future[(DecisionResult, Usage)] =
    validateConfig() match {
      case Failure(e) => Future.failed(e)
      case Success(_) =>
        val apiKey = configStore.get(apiKeyConfigKey).orElse(configStore.get(ConfigStore.API_KEY))
        val model = configStore.getOrElse(ConfigStore.MODEL, defaultModel)
        val httpRequest = basicRequest
          .headers(buildHeaders(apiKey))
          .body(decisionRequestBody(prompt, question, model).toString())
          .post(buildApiUrl(resolvedBaseUrl))
        executeWithRetry(httpRequest, model).map { response =>
          (parseDecision(response.firstContent.getOrElse(""), question), response.usage.getOrElse(Usage.empty))
        }
    }
}
