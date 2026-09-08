// ABOUTME: Deterministic tests that each provider parser reads token usage from a response body
// ABOUTME: Asserts the cross-provider Usage record is filled consistently, or absent when not reported
package org.nlogo.extensions.llm.providers

import org.nlogo.extensions.llm.models.{ChatResponse, Usage}
import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.ExecutionContext.Implicits.global

class InspectableOpenAIProvider extends OpenAIProvider()(using global) {
  def parse(body: String): ChatResponse = parseProviderResponse(body, "test-model")
}

class InspectableOpenRouterProvider extends OpenRouterProvider()(using global) {
  def parse(body: String): ChatResponse = parseProviderResponse(body, "test-model")
}

class InspectableClaudeParser extends ClaudeProvider()(using global) {
  def parse(body: String): ChatResponse = parseProviderResponse(body, "test-model")
}

class InspectableGeminiProvider extends GeminiProvider()(using global) {
  def parse(body: String): ChatResponse = parseProviderResponse(body, "test-model")
}

class InspectableOllamaProvider extends OllamaProvider()(using global) {
  def parse(body: String): ChatResponse = parseProviderResponse(body, "test-model")
}

class UsageParsingSpec extends AnyFunSuite {

  ProviderRegistrations.registerAll()

  private val openAiBody =
    """{"id":"chatcmpl-1","created":1700000000,"model":"gpt-4o-mini",
      | "choices":[{"index":0,"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}],
      | "usage":{"prompt_tokens":12,"completion_tokens":7,"total_tokens":19,
      |   "prompt_tokens_details":{"cached_tokens":4},
      |   "completion_tokens_details":{"reasoning_tokens":3}}}""".stripMargin

  test("OpenAI-compatible parser reads prompt, completion, total, reasoning and cached tokens") {
    val usage = new InspectableOpenAIProvider().parse(openAiBody).usage
    assert(usage.contains(Usage(
      inputTokens = 12,
      outputTokens = 7,
      totalTokens = 19,
      reasoningTokens = Some(3),
      cacheReadTokens = Some(4)
    )))
  }

  test("OpenAI-compatible parser leaves usage absent when the provider omits it") {
    val body =
      """{"id":"chatcmpl-2","created":1700000000,"model":"m",
        | "choices":[{"index":0,"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}]}""".stripMargin
    assert(new InspectableOpenAIProvider().parse(body).usage.isEmpty)
  }

  test("OpenAI-compatible parser tolerates missing detail sub-objects") {
    val body =
      """{"id":"chatcmpl-3","created":1700000000,"model":"m",
        | "choices":[{"index":0,"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}],
        | "usage":{"prompt_tokens":5,"completion_tokens":2,"total_tokens":7}}""".stripMargin
    val usage = new InspectableOpenAIProvider().parse(body).usage
    assert(usage.contains(Usage(inputTokens = 5, outputTokens = 2, totalTokens = 7)))
  }

  test("OpenRouter parser exposes the reported dollar cost") {
    val body =
      """{"id":"gen-1","created":1700000000,"model":"openai/gpt-4o-mini",
        | "choices":[{"index":0,"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}],
        | "usage":{"prompt_tokens":10,"completion_tokens":4,"total_tokens":14,"cost":0.0000123}}""".stripMargin
    val usage = new InspectableOpenRouterProvider().parse(body).usage
    assert(usage.flatMap(_.cost).contains(0.0000123))
    assert(usage.map(_.inputTokens).contains(10))
  }

  test("Claude parser counts cached prompt tokens as input and computes the total") {
    val body =
      """{"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-4-5",
        | "content":[{"type":"text","text":"hi"}],"stop_reason":"end_turn",
        | "usage":{"input_tokens":20,"output_tokens":6,
        |   "cache_creation_input_tokens":8,"cache_read_input_tokens":30}}""".stripMargin
    val usage = new InspectableClaudeParser().parse(body).usage
    assert(usage.contains(Usage(
      inputTokens = 58,
      outputTokens = 6,
      totalTokens = 64,
      cacheReadTokens = Some(30),
      cacheWriteTokens = Some(8)
    )))
  }

  test("Claude parser handles a usage block with only the two required fields") {
    val body =
      """{"id":"msg_2","type":"message","role":"assistant","model":"m",
        | "content":[{"type":"text","text":"hi"}],"stop_reason":"end_turn",
        | "usage":{"input_tokens":3,"output_tokens":1}}""".stripMargin
    val usage = new InspectableClaudeParser().parse(body).usage
    assert(usage.contains(Usage(inputTokens = 3, outputTokens = 1, totalTokens = 4)))
  }

  test("Gemini parser counts thinking tokens as output and reads cached content tokens") {
    val body =
      """{"candidates":[{"content":{"parts":[{"text":"hi"}],"role":"model"},"finishReason":"STOP"}],
        | "usageMetadata":{"promptTokenCount":15,"candidatesTokenCount":5,"thoughtsTokenCount":9,
        |   "cachedContentTokenCount":2,"totalTokenCount":29}}""".stripMargin
    val usage = new InspectableGeminiProvider().parse(body).usage
    assert(usage.contains(Usage(
      inputTokens = 15,
      outputTokens = 14,
      totalTokens = 29,
      reasoningTokens = Some(9),
      cacheReadTokens = Some(2)
    )))
  }

  test("Gemini parser leaves usage absent when usageMetadata is missing") {
    val body =
      """{"candidates":[{"content":{"parts":[{"text":"hi"}],"role":"model"},"finishReason":"STOP"}]}"""
    assert(new InspectableGeminiProvider().parse(body).usage.isEmpty)
  }

  test("Ollama parser maps eval counts to input and output and computes the total") {
    val body =
      """{"model":"llama3","message":{"role":"assistant","content":"hi"},"done":true,
        | "total_duration":5000000000,"prompt_eval_count":11,"eval_count":4}""".stripMargin
    val usage = new InspectableOllamaProvider().parse(body).usage
    assert(usage.contains(Usage(inputTokens = 11, outputTokens = 4, totalTokens = 15)))
  }

  test("Ollama parser leaves usage absent when counts are missing") {
    val body = """{"model":"llama3","message":{"role":"assistant","content":"hi"},"done":true}"""
    assert(new InspectableOllamaProvider().parse(body).usage.isEmpty)
  }

  test("HTTP provider stamps wall-clock latency on every response, even without token usage") {
    val counter = new java.util.concurrent.atomic.AtomicInteger(0)
    val stub = sttp.client4.testing.BackendStub.asynchronousFuture.whenAnyRequest.thenRespond {
      counter.incrementAndGet()
      sttp.client4.testing.ResponseStub.adjust("hello", sttp.model.StatusCode.Ok)
    }
    val provider = new StubbedProvider(stub, counter)
    val request = org.nlogo.extensions.llm.models.ChatRequest(
      model = "stub-model",
      messages = Seq(org.nlogo.extensions.llm.models.ChatMessage.user("hi"))
    )
    val response = scala.concurrent.Await.result(provider.chat(request), scala.concurrent.duration.Duration(5, "s"))
    val latency = response.usage.flatMap(_.latencyMs)
    assert(latency.isDefined, "latency should be stamped even when the parser reports no usage")
    assert(latency.get >= 0L)
    assert(response.usage.map(_.totalTokens).contains(0L))
  }

  test("latency covers rate-limit retries, not just the final attempt") {
    val counter = new java.util.concurrent.atomic.AtomicInteger(0)
    val stub = sttp.client4.testing.BackendStub.asynchronousFuture.whenAnyRequest.thenRespond {
      if (counter.getAndIncrement() == 0)
        sttp.client4.testing.ResponseStub.adjust("rate limit exceeded", sttp.model.StatusCode.TooManyRequests)
      else
        sttp.client4.testing.ResponseStub.adjust("hello", sttp.model.StatusCode.Ok)
    }
    val provider = new StubbedProvider(stub, counter)
    val request = org.nlogo.extensions.llm.models.ChatRequest(
      model = "stub-model",
      messages = Seq(org.nlogo.extensions.llm.models.ChatMessage.user("hi"))
    )
    val response = scala.concurrent.Await.result(provider.chat(request), scala.concurrent.duration.Duration(5, "s"))
    val latency = response.usage.flatMap(_.latencyMs).getOrElse(-1L)
    assert(provider.delays.nonEmpty, "the stub should have slept once for the 429")
    assert(latency >= provider.delays.sum, s"latency $latency ms should include the ${provider.delays.sum} ms backoff")
  }

  test("Usage.plus sums every counter and keeps optional fields optional when both sides lack them") {
    val a = Usage(inputTokens = 1, outputTokens = 2, totalTokens = 3, reasoningTokens = Some(1), latencyMs = Some(100))
    val b = Usage(inputTokens = 10, outputTokens = 20, totalTokens = 30, cost = Some(0.5), latencyMs = Some(50))
    assert(a.plus(b) == Usage(
      inputTokens = 11,
      outputTokens = 22,
      totalTokens = 33,
      reasoningTokens = Some(1),
      cost = Some(0.5),
      latencyMs = Some(150)
    ))
    assert(Usage.empty.plus(Usage.empty) == Usage.empty)
  }
}
