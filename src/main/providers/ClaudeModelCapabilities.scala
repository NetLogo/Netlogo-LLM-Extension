// ABOUTME: Per-generation Anthropic API capabilities (thinking mode, sampling-parameter support)
// ABOUTME: Single place encoding which Claude models take extended thinking vs adaptive thinking + effort
package org.nlogo.extensions.llm.providers

/**
 * Which thinking request shape a Claude model accepts.
 *
 * Anthropic changed the thinking API across generations, and the two shapes are
 * mutually exclusive -- sending the wrong one is an HTTP 400:
 *
 *  - Extended: `thinking: {type: "enabled", budget_tokens: N}`.
 *    Claude 4.5 and earlier. Rejected by 4.7 and later.
 *  - Adaptive: `thinking: {type: "adaptive"}` with depth steered by
 *    `output_config.effort`. Claude 4.6 and later; the only mode on 4.7+.
 *
 * Claude 4.6 accepts both; adaptive is preferred there because extended
 * thinking is deprecated on that generation.
 */
enum ClaudeThinkingMode:
  case Extended
  case Adaptive

/**
 * Capability lookup for Anthropic model identifiers.
 *
 * Kept separate from ReasoningModelDetector on purpose: that object answers the
 * cross-provider question "should thinking be on at all", while these are
 * Anthropic request-shape details that only ClaudeProvider needs. Folding them
 * in would grow the shared cross-provider string-matching surface.
 *
 * Matching is on model-name substrings because Anthropic model IDs are
 * versioned strings and the extension accepts user-supplied and override-config
 * model names that are not in the bundled registry.
 */
object ClaudeModelCapabilities {

  /**
   * Generations that predate adaptive thinking, and so must use the legacy
   * `{type: "enabled", budget_tokens: N}` shape.
   *
   * Claude 3.x is included for completeness: those models are retired, but a
   * user pinning one via override config should still get the shape their
   * model expects rather than a guaranteed 400 from the adaptive shape.
   */
  private val ExtendedThinkingMarkers = Seq(
    "claude-3-5", "claude-3-7", "claude-3-opus", "claude-3-haiku", "claude-3-sonnet",
    "claude-opus-4-0", "claude-opus-4-1", "claude-opus-4-5",
    "claude-sonnet-4-0", "claude-sonnet-4-5",
    "claude-haiku-4-5",
    "claude-opus-4-20", "claude-sonnet-4-20"
  )

  /**
   * Generations that still ACCEPT `temperature`/`top_p`/`top_k`.
   *
   * Claude 4.7 and later, plus the Fable/Mythos line, reject a non-default
   * `temperature` with a 400 on EVERY request, thinking or not — which is why
   * the non-thinking path has to honour this too.
   *
   * This is deliberately an allowlist of older generations rather than a denylist
   * of newer ones. A denylist has to enumerate every future model ID, so
   * `claude-sonnet-4-7` or `claude-opus-4-9` would match nothing, fall through as
   * permitted, and be sent a temperature they reject. Listing what is known to
   * accept sampling params instead makes the unknown case default FORWARD — the
   * same direction `thinkingMode` already defaults, so the two stay consistent.
   */
  private val SamplingParamsMarkers = Seq(
    "claude-3-5", "claude-3-7", "claude-3-opus", "claude-3-haiku", "claude-3-sonnet",
    "claude-opus-4-0", "claude-opus-4-1", "claude-opus-4-5", "claude-opus-4-6",
    "claude-sonnet-4-0", "claude-sonnet-4-5", "claude-sonnet-4-6",
    "claude-haiku-4-5",
    "claude-opus-4-20", "claude-sonnet-4-20"
  )

  private def matches(model: String, markers: Seq[String]): Boolean = {
    val m = model.toLowerCase
    markers.exists(m.contains)
  }

  /**
   * Thinking request shape for a model.
   *
   * Defaults to Adaptive for unrecognized names: new Anthropic models move
   * forward, not back, so an unknown identifier is far more likely to be a
   * newer adaptive-only model than a pre-4.6 one.
   */
  def thinkingMode(model: String): ClaudeThinkingMode =
    if (matches(model, ExtendedThinkingMarkers)) ClaudeThinkingMode.Extended
    else ClaudeThinkingMode.Adaptive

  /**
   * Whether a `temperature` value may be sent for this model at all.
   *
   * False for 4.7+ regardless of thinking state.
   */
  def supportsSamplingParams(model: String): Boolean =
    matches(model, SamplingParamsMarkers)

  /**
   * Map the extension's reasoning_effort config onto Anthropic's
   * `output_config.effort` value.
   *
   * The extension accepts none|low|medium|high|xhigh. Anthropic accepts
   * low|medium|high|xhigh|max -- there is no "none", so it is treated as
   * "no explicit effort" and the API default (high) applies.
   */
  def effortValue(reasoningEffort: Option[String]): Option[String] =
    reasoningEffort.map(_.toLowerCase.trim).collect {
      case e @ ("low" | "medium" | "high" | "xhigh" | "max") => e
    }
}
