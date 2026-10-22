// ABOUTME: Capability trait for providers with a typed decision API (choice, score, yes/no)
// ABOUTME: llm:decide, llm:decide-all and llm:choose route through it; any decision-style API implements only this
package org.nlogo.extensions.llm.providers

import scala.concurrent.Future

import org.nlogo.extensions.llm.models.{DecisionQuestion, DecisionResult, Usage}

/**
 * A provider that answers typed questions with calibrated probabilities
 * instead of generating text.
 *
 * Mixed into an LLMProvider so the provider still has config, a model and a
 * name. Implementations must send through their request throttle, as chat does.
 */
trait DecisionProvider { self: LLMProvider =>

  /**
   * Ask `question` about `prompt`. No history is involved: the prompt is the
   * whole context.
   *
   * @return the decision and the usage the call was billed for
   */
  def decide(prompt: String, question: DecisionQuestion): Future[(DecisionResult, Usage)]

  /**
   * Why free-text chat is unavailable on this provider, or None when it serves
   * text as well as decisions. The text primitives raise this message as is.
   */
  def textUnsupportedMessage: Option[String] = None
}
