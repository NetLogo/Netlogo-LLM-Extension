// ABOUTME: Vendor-neutral model of a typed decision question and its probabilistic answer
// ABOUTME: Shared by llm:decide / llm:decide-all and every provider that implements DecisionProvider
package org.nlogo.extensions.llm.models

/**
 * A question with a fixed answer space, asked about a prompt.
 *
 * Unlike a chat request, the reply is not text: it is one point in the answer
 * space plus a probability for every point, so a model can sample from the
 * distribution or take the top pick.
 */
enum DecisionQuestion {
  /** Pick one of `options`. */
  case Choice(options: Seq[String])
  /** Rate on the ordered `levels`, numbered from 0. */
  case Score(levels: Seq[String])
  /** Answer yes or no. */
  case YesNo
}

object DecisionQuestion {
  val MaxChoiceOptions: Int = 255
  val MinScoreLevels: Int = 2
  val MaxScoreLevels: Int = 10
}

/** The answer to a DecisionQuestion; its shape follows the question type. */
enum DecisionAnswer {
  /** The selected option of a Choice. */
  case Picked(option: String)
  /** The probability-weighted level of a Score, on the 0-based scale. */
  case Expected(value: Double)
  /** The answer to a YesNo. */
  case Verdict(yes: Boolean)
}

/**
 * A decision and how sure the model is of it.
 *
 * @param answer       the decision itself
 * @param probability  the probability of `answer` for Choice and YesNo; for a
 *                     Score, whose answer can fall between levels, the
 *                     provider's confidence in the score
 * @param distribution every possible outcome with its probability, in question
 *                     order: options for Choice, level names for Score, and
 *                     "true" then "false" for YesNo
 */
case class DecisionResult(
  answer: DecisionAnswer,
  probability: Double,
  distribution: Seq[(String, Double)]
)

object DecisionResult {

  /**
   * A Choice result. The distribution follows `options` order rather than the
   * provider's, so a modeler's list lines up with what comes back; an option
   * the provider gave no probability gets 0.
   */
  def choice(options: Seq[String], picked: String, probabilities: Map[String, Double]): DecisionResult = {
    val distribution = options.map(o => o -> probabilities.getOrElse(o, 0.0))
    DecisionResult(DecisionAnswer.Picked(picked), probabilities.getOrElse(picked, 0.0), distribution)
  }

  /** A Score result; `probabilities(i)` is the probability of `levels(i)`. */
  def score(levels: Seq[String], expected: Double, confidence: Double, probabilities: Seq[Double]): DecisionResult =
    DecisionResult(
      DecisionAnswer.Expected(expected),
      confidence,
      levels.zipAll(probabilities, "", 0.0).take(levels.size)
    )

  /** A YesNo result from the probability of yes; the answer is whichever side is likelier. */
  def yesNo(probabilityYes: Double): DecisionResult = {
    val yes = probabilityYes >= 0.5
    val probabilityNo = complement(probabilityYes)
    DecisionResult(
      DecisionAnswer.Verdict(yes),
      if (yes) probabilityYes else probabilityNo,
      Seq("true" -> probabilityYes, "false" -> probabilityNo)
    )
  }

  /**
   * 1 - p in decimal rather than binary arithmetic, so a provider's 0.56 gives
   * 0.44 instead of 0.43999999999999995 in a modeler's monitors.
   */
  private def complement(p: Double): Double =
    (BigDecimal(1) - BigDecimal(p.toString)).toDouble
}
