// ABOUTME: Tests that a request whose response_format the model rejects (HTTP 400) is resent once unconstrained
// ABOUTME: Uses an sttp BackendStub behind an OpenAIProvider so the real wire format is exercised
package org.nlogo.extensions.llm.providers

import java.util.concurrent.ConcurrentLinkedQueue

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.jdk.CollectionConverters._

import org.scalatest.funsuite.AnyFunSuite
import sttp.client4._
import sttp.client4.testing.{BackendStub, ResponseStub}
import sttp.model.StatusCode

import org.nlogo.extensions.llm.models.{ChatMessage, EnumFormat}

/** OpenAI provider whose backend is a stub and whose fallback notices are recorded. */
class SchemaFallbackProvider(stub: BackendStub[Future]) extends OpenAIProvider()(using global) {
  override lazy val backend: Backend[Future] = stub

  val notices = new ConcurrentLinkedQueue[String]()
  override protected def notifyResponseFormatUnsupported(model: String): Unit = {
    notices.add(model)
    super.notifyResponseFormatUnsupported(model)
  }
}

class SchemaFallbackSpec extends AnyFunSuite {

  private val okBody =
    """{"id":"chatcmpl-1","created":1700000000,"model":"m",
      | "choices":[{"index":0,"message":{"role":"assistant","content":"{\"choice\": \"red\"}"},"finish_reason":"stop"}]}""".stripMargin

  private val unsupportedBody =
    """{"error":{"message":"response_format json_schema is not supported by this model","type":"invalid_request_error"}}"""

  private def bodyOf(request: GenericRequest[?, ?]): String = request.body match {
    case StringBody(s, _, _) => s
    case other               => other.toString
  }

  /** Stub that answers `rejectBody` with a 400 whenever response_format is sent, else 200. */
  private def stub(rejectBody: String, sent: ConcurrentLinkedQueue[String]): BackendStub[Future] =
    BackendStub.asynchronousFuture.whenAnyRequest.thenRespondF { (req: GenericRequest[?, ?]) =>
      val body = bodyOf(req)
      sent.add(body)
      Future.successful(
        if (body.contains("response_format")) ResponseStub.adjust(rejectBody, StatusCode.BadRequest)
        else ResponseStub.adjust(okBody, StatusCode.Ok)
      )
    }

  private def provider(rejectBody: String, sent: ConcurrentLinkedQueue[String], model: String): SchemaFallbackProvider = {
    val p = new SchemaFallbackProvider(stub(rejectBody, sent))
    p.setConfig("api_key", "sk-test")
    p.setConfig("model", model)
    p
  }

  private val messages = Seq(ChatMessage.user("pick a colour"))

  test("a 400 rejecting response_format is resent once without it, with a one-time notice") {
    val sent = new ConcurrentLinkedQueue[String]()
    val p = provider(unsupportedBody, sent, "fallback-model-a")

    val result = Await.result(p.chatWithFormat(messages, EnumFormat(List("red", "blue"))), 5.seconds)
    assert(result.firstMessage.map(_.content).contains("""{"choice": "red"}"""))
    val bodies = sent.asScala.toList
    assert(bodies.length == 2)
    assert(bodies.head.contains("response_format"))
    assert(!bodies(1).contains("response_format"))

    Await.result(p.chatWithFormat(messages, EnumFormat(List("red", "blue"))), 5.seconds)
    assert(sent.size == 4)
    assert(p.notices.asScala.toList == List("fallback-model-a"))
  }

  test("a 400 about something else still fails after a single send") {
    val sent = new ConcurrentLinkedQueue[String]()
    val p = provider(
      """{"error":{"message":"The model `nope` does not exist","type":"invalid_request_error"}}""",
      sent,
      "fallback-model-b"
    )

    val ex = intercept[RuntimeException] {
      Await.result(p.chatWithFormat(messages, EnumFormat(List("red", "blue"))), 5.seconds)
    }
    assert(ex.getMessage.contains("does not exist"))
    assert(sent.size == 1)
    assert(p.notices.isEmpty)
  }
}
