// ABOUTME: Request/response mapping tests for TypeSafeProvider's decision API (choice, score, yes/no)
// ABOUTME: A BackendStub captures URL, headers and body and scripts System One replies; no real HTTP
package org.nlogo.extensions.llm.providers

import org.nlogo.extensions.llm.config.ConfigStore
import org.nlogo.extensions.llm.models.{ChatMessage, ChatRequest, DecisionAnswer, DecisionQuestion, DecisionResult, Usage}
import org.scalatest.funsuite.AnyFunSuite
import sttp.client4._
import sttp.client4.testing.{BackendStub, ResponseStub}
import sttp.model.StatusCode
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

/** Records the last request sent and answers every request with `status` / `body`. */
class SystemOneStub(status: StatusCode, body: String) {
  val last = new AtomicReference[GenericRequest[?, ?]]()
  val backend: BackendStub[Future] = BackendStub.asynchronousFuture
    .whenRequestMatches { req => last.set(req); true }
    .thenRespond(ResponseStub.adjust(body, status))

  def url: String = last.get.uri.toString
  def header(name: String): Option[String] = last.get.header(name)
  def json: ujson.Value = last.get.body match {
    case StringBody(s, _, _) => ujson.read(s)
    case other               => throw new AssertionError(s"unexpected body $other")
  }
}

class StubbedTypeSafe(stub: SystemOneStub) extends TypeSafeProvider()(using global) {
  override lazy val backend: Backend[Future] = stub.backend
  override protected def retryBaseDelayMs: Long = 1L
}

class TypeSafeProviderSpec extends AnyFunSuite {

  ProviderRegistrations.registerAll()

  private def provider(stub: SystemOneStub, key: Option[String] = Some("ts-key")): TypeSafeProvider = {
    val p = new StubbedTypeSafe(stub)
    key.foreach(k => p.setConfig("typesafe_api_key", k))
    p
  }

  private def decide(p: TypeSafeProvider, prompt: String, q: DecisionQuestion): (DecisionResult, Usage) =
    Await.result(p.decide(prompt, q), 5.seconds)

  private def reply(answer: ujson.Value, in: Int = 120, out: Int = 12): String =
    ujson.Obj(
      "model" -> "jev-1.13.0",
      "answers" -> ujson.Obj("decision" -> answer),
      "usage" -> ujson.Obj("input_tokens" -> in, "output_tokens" -> out)
    ).toString

  private val choiceReply = reply(ujson.Obj(
    "type" -> "choice", "choice" -> "stay", "confidence" -> 0.4,
    // Deliberately not in request order: the distribution must follow the options.
    "probabilities" -> ujson.Obj("stay" -> 0.7, "move" -> 0.3)
  ))

  test("choice request goes to /v1/systemone with a bearer key and the documented body") {
    val stub = new SystemOneStub(StatusCode.Ok, choiceReply)
    decide(provider(stub), "Go to the bar tonight?", DecisionQuestion.Choice(Seq("move", "stay")))

    assert(stub.url == "https://api.typesafe.ai/v1/systemone")
    assert(stub.header("Authorization").contains("Bearer ts-key"))
    assert(stub.header("Content-Type").exists(_.startsWith("application/json")))

    val body = stub.json
    assert(body("model").str == "jev-latest")
    assert(body("state").str == "Go to the bar tonight?")
    val q = body("questions")("decision")
    assert(q("type").str == "choice")
    assert(q("instructions").str.nonEmpty)
    assert(q("criteria").obj.keys.toSeq == Seq("move", "stay"))
    assert(q("criteria").obj.values.forall(_ == ujson.Null))
    assert(body.obj.keySet == Set("model", "state", "questions"))
  }

  test("choice reply maps to the picked option, its probability, and options-ordered distribution") {
    val stub = new SystemOneStub(StatusCode.Ok, choiceReply)
    val (result, _) = decide(provider(stub), "p", DecisionQuestion.Choice(Seq("move", "stay")))
    assert(result.answer == DecisionAnswer.Picked("stay"))
    assert(result.probability == 0.7)
    assert(result.distribution == Seq("move" -> 0.3, "stay" -> 0.7))
  }

  test("score request sends the levels as an ordered list and maps the reply by level index") {
    val stub = new SystemOneStub(StatusCode.Ok, reply(ujson.Obj(
      "type" -> "score", "score" -> 1.43, "confidence" -> 0.62,
      "legend" -> ujson.Obj("0" -> "low", "1" -> "medium", "2" -> "high"),
      "probabilities" -> ujson.Obj("2" -> 0.53, "0" -> 0.1, "1" -> 0.37)
    )))
    val levels = Seq("low", "medium", "high")
    val (result, _) = decide(provider(stub), "How crowded?", DecisionQuestion.Score(levels))

    val q = stub.json("questions")("decision")
    assert(q("type").str == "score")
    assert(q("criteria").arr.map(_.str).toSeq == levels)

    assert(result.answer == DecisionAnswer.Expected(1.43))
    assert(result.probability == 0.62)
    assert(result.distribution == Seq("low" -> 0.1, "medium" -> 0.37, "high" -> 0.53))
  }

  test("yes-no request is a noul question without criteria, answered by the likelier side") {
    val stub = new SystemOneStub(StatusCode.Ok, reply(ujson.Obj("type" -> "noul", "noul" -> 0.82)))
    val (yes, _) = decide(provider(stub), "Will it be crowded?", DecisionQuestion.YesNo)

    val q = stub.json("questions")("decision")
    assert(q("type").str == "noul")
    assert(!q.obj.contains("criteria"))

    assert(yes.answer == DecisionAnswer.Verdict(true))
    assert(yes.probability == 0.82)
    assert(yes.distribution.map(_._1) == Seq("true", "false"))

    val stubNo = new SystemOneStub(StatusCode.Ok, reply(ujson.Obj("type" -> "noul", "noul" -> 0.25)))
    val (no, _) = decide(provider(stubNo), "p", DecisionQuestion.YesNo)
    assert(no.answer == DecisionAnswer.Verdict(false))
    assert(no.probability == 0.75)
    assert(no.distribution == Seq("true" -> 0.25, "false" -> 0.75))
  }

  test("usage maps input and output tokens, prices input tokens, and stamps latency") {
    val stub = new SystemOneStub(StatusCode.Ok, reply(ujson.Obj("type" -> "noul", "noul" -> 0.9), in = 1000, out = 3))
    val (_, usage) = decide(provider(stub), "p", DecisionQuestion.YesNo)
    assert(usage.inputTokens == 1000L)
    assert(usage.outputTokens == 3L)
    assert(usage.totalTokens == 1003L)
    assert(usage.cost.exists(c => math.abs(c - 1000 * 0.042e-6) < 1e-15))
    assert(usage.latencyMs.isDefined)
  }

  test("the configured model and typesafe_base_url are honored") {
    val stub = new SystemOneStub(StatusCode.Ok, choiceReply)
    val p = provider(stub)
    p.setConfig(ConfigStore.MODEL, "jev-preview")
    p.setConfig("typesafe_base_url", "http://local.test")
    decide(p, "p", DecisionQuestion.Choice(Seq("move", "stay")))
    assert(stub.url == "http://local.test/v1/systemone")
    assert(stub.json("model").str == "jev-preview")
  }

  test("a 401 fails with the friendly invalid-key message") {
    val stub = new SystemOneStub(StatusCode.Unauthorized, """{"detail":"Invalid API key"}""")
    val e = intercept[HttpStatusException](decide(provider(stub), "p", DecisionQuestion.YesNo))
    assert(e.statusCode == 401)
    assert(e.getMessage.contains("TypeSafe (Jev) rejected the request: HTTP 401 (invalid API key)"))
    assert(e.getMessage.contains("typesafe_api_key"))
  }

  test("a missing key fails before any request is sent") {
    val stub = new SystemOneStub(StatusCode.Ok, choiceReply)
    val e = intercept[IllegalStateException](decide(provider(stub, key = None), "p", DecisionQuestion.YesNo))
    assert(e.getMessage.contains("typesafe requires an API key"))
    assert(stub.last.get == null)
  }

  test("a reply missing the answer fails with a clear message") {
    val stub = new SystemOneStub(StatusCode.Ok,
      """{"model":"jev-latest","answers":{},"usage":{"input_tokens":1,"output_tokens":0}}""")
    val e = intercept[RuntimeException](decide(provider(stub), "p", DecisionQuestion.YesNo))
    assert(e.getMessage.contains("TypeSafe (Jev)"))
  }

  test("free-text chat is refused with the decisions-not-text message") {
    val stub = new SystemOneStub(StatusCode.Ok, choiceReply)
    val p = provider(stub)
    val e = intercept[UnsupportedOperationException](
      Await.result(p.chat(ChatRequest(model = "jev-latest", messages = Seq(ChatMessage.user("hi")))), 5.seconds))
    assert(e.getMessage == TypeSafeProvider.TextUnsupportedMessage)
    assert(p.textUnsupportedMessage.contains(TypeSafeProvider.TextUnsupportedMessage))
    assert(TypeSafeProvider.TextUnsupportedMessage ==
      "typesafe (Jev) returns decisions, not text; use llm:decide, llm:decide-all or llm:choose")
    assert(stub.last.get == null)
  }

  test("typesafe is registered with its keys, defaults and models") {
    val d = ProviderRegistry.get("typesafe").get
    assert(d.displayName == "TypeSafe (Jev)")
    assert(d.apiKeyConfigKey == "typesafe_api_key")
    assert(d.baseUrlConfigKey == "typesafe_base_url")
    assert(d.defaultBaseUrl == "https://api.typesafe.ai")
    assert(d.defaultModel == "jev-latest")
    assert(d.requiresApiKey)
    assert(!d.exposesThinking)
    assert(Set("jev-latest", "jev-preview", "jev-1.13.0", "jev-1.14.0").subsetOf(ModelRegistry.getSupportedModels("typesafe")))
  }
}
