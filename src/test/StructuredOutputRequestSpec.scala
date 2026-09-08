// ABOUTME: Deterministic tests asserting each provider wraps a response format in its own request shape
// ABOUTME: No network I/O — only the JSON body each provider would have posted
package org.nlogo.extensions.llm.providers

import org.nlogo.extensions.llm.models._
import org.scalatest.funsuite.AnyFunSuite
import scala.concurrent.ExecutionContext.Implicits.global

/** Exposes each provider's protected request builder for body assertions. */
private class InspectableOpenAI extends OpenAIProvider {
  def body(r: ChatRequest): ujson.Value = createProviderRequest(r)
}
private class InspectableGroq extends GroqProvider {
  def body(r: ChatRequest): ujson.Value = createProviderRequest(r)
}
private class InspectableTogether extends TogetherProvider {
  def body(r: ChatRequest): ujson.Value = createProviderRequest(r)
}
private class InspectableOpenRouter extends OpenRouterProvider {
  def body(r: ChatRequest): ujson.Value = createProviderRequest(r)
}
private class InspectableClaude extends ClaudeProvider {
  def body(r: ChatRequest): ujson.Value = createProviderRequest(r)
}
private class InspectableGemini extends GeminiProvider {
  def body(r: ChatRequest): ujson.Value = createProviderRequest(r)
}
private class InspectableOllama extends OllamaProvider {
  def body(r: ChatRequest): ujson.Value = createProviderRequest(r)
}

class StructuredOutputRequestSpec extends AnyFunSuite {

  // Constructing a provider reads its default model from the registry, which is
  // normally populated by LLMExtension.load(). Register here so the suite is
  // self-contained and order-independent.
  ProviderRegistry.reset()
  ProviderRegistrations.registerAll()

  private val schemaText =
    """{"type":"object","properties":{"action":{"type":"string"},"confidence":{"type":"number"}},"required":["action"]}"""

  private val schemaFormat = ResponseFormat.parseSchema(schemaText)

  private def request(
    model: String,
    format: Option[ResponseFormat],
    thinking: Option[ThinkingConfig] = None,
    temperature: Option[Double] = None,
    maxTokens: Option[Int] = Some(4000)
  ): ChatRequest =
    ChatRequest(
      model = model,
      messages = Seq(ChatMessage.user("hi")),
      maxTokens = maxTokens,
      temperature = temperature,
      thinkingConfig = thinking,
      responseFormat = format
    )

  private def keys(v: ujson.Value): Set[String] = v.obj.keys.toSet

  // --- OpenAI-compatible family: openai, groq, together, openrouter ---

  private val openAiCompatible: Seq[(String, ChatRequest => ujson.Value)] = Seq(
    "openai"     -> (new InspectableOpenAI).body,
    "groq"       -> (new InspectableGroq).body,
    "together"   -> (new InspectableTogether).body,
    "openrouter" -> (new InspectableOpenRouter).body
  )

  test("every OpenAI-compatible provider nests the schema under response_format.json_schema") {
    openAiCompatible.foreach { case (name, build) =>
      val body = build(request("some-model", Some(schemaFormat)))
      val rf = body("response_format")
      assert(rf("type").str == "json_schema", s"$name response_format.type")
      assert(rf("json_schema")("strict").bool, s"$name must request strict mode")
      assert(rf("json_schema")("name").str.nonEmpty, s"$name must send a schema name")
      assert(
        rf("json_schema")("schema")("properties")("action")("type").str == "string",
        s"$name must carry the caller's schema"
      )
    }
  }

  test("OpenAI-compatible providers normalize the schema to strict mode") {
    openAiCompatible.foreach { case (name, build) =>
      val body = build(request("some-model", Some(schemaFormat)))
      val schema = body("response_format")("json_schema")("schema")
      assert(schema("additionalProperties").bool == false, s"$name additionalProperties")
      assert(
        schema("required").arr.map(_.str).toSet == Set("action", "confidence"),
        s"$name must list every property as required"
      )
    }
  }

  test("OpenAI-compatible providers send response_format json_object for JSON mode") {
    openAiCompatible.foreach { case (name, build) =>
      val body = build(request("some-model", Some(JsonObjectFormat)))
      assert(body("response_format")("type").str == "json_object", s"$name json mode")
      assert(!keys(body("response_format")).contains("json_schema"), s"$name json mode carries no schema")
    }
  }

  test("OpenAI-compatible providers send an enum as a constrained json_schema") {
    openAiCompatible.foreach { case (name, build) =>
      val body = build(request("some-model", Some(EnumFormat(List("north", "south")))))
      val schema = body("response_format")("json_schema")("schema")
      assert(
        schema("properties")(EnumFormat.ChoiceKey)("enum").arr.map(_.str) == Seq("north", "south"),
        s"$name enum values"
      )
    }
  }

  test("no response format leaves the OpenAI-compatible body unchanged") {
    openAiCompatible.foreach { case (name, build) =>
      val body = build(request("some-model", None))
      assert(!keys(body).contains("response_format"), s"$name must omit response_format when unset")
    }
  }

  test("structured output survives alongside OpenAI reasoning fields") {
    val body = (new InspectableOpenAI).body(
      request("o3-mini", Some(schemaFormat), thinking = Some(ThinkingConfig(enabled = true, reasoningEffort = Some("high"))))
    )
    assert(body("reasoning_effort").str == "high")
    assert(body("response_format")("type").str == "json_schema")
  }

  // --- Anthropic ---

  test("Claude nests the schema under output_config.format") {
    val body = (new InspectableClaude).body(request("claude-sonnet-4-5-20250929", Some(schemaFormat)))
    val format = body("output_config")("format")
    assert(format("type").str == "json_schema")
    assert(format("schema")("properties")("action")("type").str == "string")
    assert(format("schema")("additionalProperties").bool == false)
  }

  test("Claude keeps output_config.effort when a schema is also present") {
    // effort and format share one output_config object; writing the schema must
    // merge into it, not replace the thinking-depth setting.
    val body = (new InspectableClaude).body(
      request(
        "claude-opus-5",
        Some(schemaFormat),
        thinking = Some(ThinkingConfig(enabled = true, reasoningEffort = Some("high")))
      )
    )
    assert(body("output_config")("effort").str == "high", "effort must survive a schema")
    assert(body("output_config")("format")("type").str == "json_schema", "schema must survive effort")
    assert(body("thinking")("type").str == "adaptive")
  }

  test("Claude extended-thinking request still carries a schema") {
    val body = (new InspectableClaude).body(
      request("claude-haiku-4-5-20251001", Some(schemaFormat), thinking = Some(ThinkingConfig(enabled = true)))
    )
    assert(body("thinking")("type").str == "enabled")
    assert(body("output_config")("format")("type").str == "json_schema")
  }

  test("Claude JSON mode with no schema sends no output_config.format") {
    // Anthropic has no schemaless JSON mode, so there is nothing truthful to
    // send; the prompt-level instruction carries it instead.
    val body = (new InspectableClaude).body(request("claude-sonnet-4-5-20250929", Some(JsonObjectFormat)))
    val hasFormat = keys(body).contains("output_config") && keys(body("output_config")).contains("format")
    assert(!hasFormat, s"Claude must not invent a json_object format: $body")
  }

  test("no response format leaves the Claude body without output_config.format") {
    val body = (new InspectableClaude).body(request("claude-sonnet-4-5-20250929", None))
    assert(!keys(body).contains("output_config"))
  }

  // --- Gemini ---

  test("Gemini sets responseJsonSchema and the JSON mime type") {
    val body = (new InspectableGemini).body(request("gemini-2.5-flash", Some(schemaFormat)))
    val gc = body("generationConfig")
    assert(gc("responseMimeType").str == "application/json")
    assert(gc("responseJsonSchema")("properties")("action")("type").str == "string")
  }

  test("Gemini JSON mode sets the mime type with no schema") {
    val gc = (new InspectableGemini).body(request("gemini-2.5-flash", Some(JsonObjectFormat)))("generationConfig")
    assert(gc("responseMimeType").str == "application/json")
    assert(!keys(gc).contains("responseJsonSchema"))
  }

  test("Gemini keeps thinkingConfig alongside a response schema") {
    val body = (new InspectableGemini).body(
      request("gemini-2.5-flash", Some(schemaFormat), thinking = Some(ThinkingConfig(enabled = true, budgetTokens = Some(2048))))
    )
    val gc = body("generationConfig")
    assert(gc("thinkingConfig")("thinkingBudget").num == 2048)
    assert(gc("responseMimeType").str == "application/json")
  }

  test("no response format leaves the Gemini body without responseMimeType") {
    val body = (new InspectableGemini).body(request("gemini-2.5-flash", None, temperature = Some(0.7)))
    assert(!keys(body("generationConfig")).contains("responseMimeType"))
    assert(!keys(body("generationConfig")).contains("responseJsonSchema"))
  }

  // --- Ollama ---

  test("Ollama puts the raw schema in format") {
    val body = (new InspectableOllama).body(request("llama3.1", Some(schemaFormat)))
    assert(body("format")("type").str == "object")
    assert(body("format")("properties")("action")("type").str == "string")
  }

  test("Ollama JSON mode sends the string format json") {
    val body = (new InspectableOllama).body(request("llama3.1", Some(JsonObjectFormat)))
    assert(body("format").str == "json")
  }

  test("Ollama keeps think alongside a schema") {
    val body = (new InspectableOllama).body(
      request("llama3.1", Some(schemaFormat), thinking = Some(ThinkingConfig(enabled = true)))
    )
    assert(body("think").bool)
    assert(body("format")("type").str == "object")
  }

  test("no response format leaves the Ollama body without format") {
    val body = (new InspectableOllama).body(request("llama3.1", None))
    assert(!keys(body).contains("format"))
  }
}
