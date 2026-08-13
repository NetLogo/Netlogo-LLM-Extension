// ABOUTME: Deterministic tests for ClaudeProvider request shaping across Claude generations
// ABOUTME: Asserts extended vs adaptive thinking and that temperature is omitted on 4.7+ models
package org.nlogo.extensions.llm.providers

import org.nlogo.extensions.llm.models.{ChatMessage, ChatRequest, ThinkingConfig}
import org.scalatest.funsuite.AnyFunSuite

/**
 * Exposes the protected request builder so the JSON body can be asserted
 * without performing any network I/O.
 */
class InspectableClaudeProvider extends ClaudeProvider()(using scala.concurrent.ExecutionContext.global) {
  def buildRequest(request: ChatRequest): ujson.Value = createProviderRequest(request)
}

class ClaudeRequestSpec extends AnyFunSuite {

  private val provider = new InspectableClaudeProvider

  private def request(
    model: String,
    thinking: Option[ThinkingConfig] = None,
    temperature: Option[Double] = None,
    maxTokens: Option[Int] = Some(4000)
  ): ChatRequest =
    ChatRequest(
      model = model,
      messages = Seq(ChatMessage.user("hi")),
      maxTokens = maxTokens,
      temperature = temperature,
      thinkingConfig = thinking
    )

  private def keys(v: ujson.Value): Set[String] = v.obj.keys.toSet

  // --- Extended-thinking generation (Claude 4.5 and earlier) ---

  test("extended-thinking model uses enabled+budget_tokens and forces temperature 1.0") {
    val body = provider.buildRequest(
      request("claude-haiku-4-5-20251001", thinking = Some(ThinkingConfig(enabled = true)))
    )

    assert(body("thinking")("type").str == "enabled")
    assert(body("thinking")("budget_tokens").num > 0)
    assert(body("temperature").num == 1.0)
    assert(!keys(body).contains("output_config"), "extended-thinking models must not receive output_config.effort")
  }

  test("extended-thinking budget is clamped below max_tokens") {
    val body = provider.buildRequest(
      request(
        "claude-haiku-4-5-20251001",
        thinking = Some(ThinkingConfig(enabled = true, budgetTokens = Some(99999))),
        maxTokens = Some(2000)
      )
    )
    assert(body("thinking")("budget_tokens").num == 1999)
  }

  test("extended-thinking rejects max_tokens at or below 1024") {
    val ex = intercept[RuntimeException] {
      provider.buildRequest(
        request("claude-haiku-4-5-20251001", thinking = Some(ThinkingConfig(enabled = true)), maxTokens = Some(1024))
      )
    }
    assert(ex.getMessage.contains("max_tokens > 1024"))
  }

  // --- Adaptive-thinking generation (Claude 4.7+) ---

  test("adaptive-thinking model uses type adaptive with no budget_tokens and no temperature") {
    val body = provider.buildRequest(
      request("claude-opus-4-7", thinking = Some(ThinkingConfig(enabled = true)))
    )

    assert(body("thinking")("type").str == "adaptive")
    assert(!keys(body("thinking")).contains("budget_tokens"), "adaptive thinking must not send budget_tokens")
    assert(!keys(body).contains("temperature"), "adaptive-thinking models reject temperature")
  }

  test("reasoning effort maps onto output_config.effort for adaptive models") {
    val body = provider.buildRequest(
      request("claude-opus-5", thinking = Some(ThinkingConfig(enabled = true, reasoningEffort = Some("xhigh"))))
    )
    assert(body("output_config")("effort").str == "xhigh")
  }

  test("reasoning effort 'none' sends no output_config so the API default applies") {
    val body = provider.buildRequest(
      request("claude-opus-5", thinking = Some(ThinkingConfig(enabled = true, reasoningEffort = Some("none"))))
    )
    assert(!keys(body).contains("output_config"))
  }

  test("adaptive model ignores budget_tokens config rather than sending it") {
    val body = provider.buildRequest(
      request("claude-sonnet-5", thinking = Some(ThinkingConfig(enabled = true, budgetTokens = Some(2048))))
    )
    assert(body("thinking")("type").str == "adaptive")
    assert(!keys(body("thinking")).contains("budget_tokens"))
  }

  // --- Non-thinking requests ---

  test("non-thinking request to a 4.7+ model sends NO temperature key") {
    val body = provider.buildRequest(request("claude-opus-4-7", temperature = Some(0.7)))

    assert(!keys(body).contains("temperature"), s"temperature must be suppressed on 4.7+, got: $body")
    assert(!keys(body).contains("thinking"))
  }

  test("non-thinking request to Opus 5 and Sonnet 5 sends NO temperature key") {
    Seq("claude-opus-5", "claude-sonnet-5", "claude-opus-4-8", "claude-fable-5").foreach { model =>
      val body = provider.buildRequest(request(model, temperature = Some(0.3)))
      assert(!keys(body).contains("temperature"), s"temperature must be suppressed on $model")
    }
  }

  test("non-thinking request to an older model still honors temperature") {
    val body = provider.buildRequest(request("claude-haiku-4-5-20251001", temperature = Some(0.7)))
    assert(body("temperature").num == 0.7)
  }

  // --- Capability table ---

  test("thinking mode classification matches Anthropic generations") {
    import ClaudeThinkingMode._
    assert(ClaudeModelCapabilities.thinkingMode("claude-haiku-4-5-20251001") == Extended)
    assert(ClaudeModelCapabilities.thinkingMode("claude-sonnet-4-5-20250929") == Extended)
    assert(ClaudeModelCapabilities.thinkingMode("claude-opus-4-5-20251101") == Extended)
    assert(ClaudeModelCapabilities.thinkingMode("claude-3-7-sonnet-20250219") == Extended)

    assert(ClaudeModelCapabilities.thinkingMode("claude-opus-4-7") == Adaptive)
    assert(ClaudeModelCapabilities.thinkingMode("claude-opus-4-8") == Adaptive)
    assert(ClaudeModelCapabilities.thinkingMode("claude-opus-5") == Adaptive)
    assert(ClaudeModelCapabilities.thinkingMode("claude-sonnet-5") == Adaptive)
    assert(ClaudeModelCapabilities.thinkingMode("claude-fable-5") == Adaptive)
    // Unknown/newer identifiers default forward to adaptive.
    assert(ClaudeModelCapabilities.thinkingMode("claude-something-new") == Adaptive)
  }

  test("sampling-parameter support matches Anthropic generations") {
    assert(ClaudeModelCapabilities.supportsSamplingParams("claude-haiku-4-5-20251001"))
    assert(ClaudeModelCapabilities.supportsSamplingParams("claude-opus-4-6"))
    assert(ClaudeModelCapabilities.supportsSamplingParams("claude-sonnet-4-6"))

    assert(!ClaudeModelCapabilities.supportsSamplingParams("claude-opus-4-7"))
    assert(!ClaudeModelCapabilities.supportsSamplingParams("claude-opus-4-8"))
    assert(!ClaudeModelCapabilities.supportsSamplingParams("claude-opus-5"))
    assert(!ClaudeModelCapabilities.supportsSamplingParams("claude-sonnet-5"))
    assert(!ClaudeModelCapabilities.supportsSamplingParams("claude-fable-5"))
  }

  test("unknown models default forward on BOTH capability checks") {
    import ClaudeThinkingMode._
    // A denylist of "no sampling params" models had to enumerate every future
    // ID, so these fell through as permitted and were sent a temperature they
    // reject with a 400. Both checks must default the same direction.
    for (m <- Seq("claude-sonnet-4-7", "claude-haiku-4-7", "claude-opus-4-9",
                  "claude-something-new")) {
      assert(ClaudeModelCapabilities.thinkingMode(m) == Adaptive, s"$m thinking mode")
      assert(!ClaudeModelCapabilities.supportsSamplingParams(m), s"$m sampling params")
    }
  }

  test("effort values outside Anthropic's accepted set are dropped") {
    assert(ClaudeModelCapabilities.effortValue(Some("high")).contains("high"))
    assert(ClaudeModelCapabilities.effortValue(Some("HIGH")).contains("high"))
    assert(ClaudeModelCapabilities.effortValue(Some("none")).isEmpty)
    assert(ClaudeModelCapabilities.effortValue(Some("bogus")).isEmpty)
    assert(ClaudeModelCapabilities.effortValue(None).isEmpty)
  }

  test("api version header is the published one and does not vary with thinking") {
    assert(ClaudeProvider.ApiVersion == "2023-06-01")
  }
}
