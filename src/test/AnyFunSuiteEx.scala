// ABOUTME: Copy of NetLogo's org.nlogo.util.AnyFunSuiteEx, which netlogo-7.0.4-tests.jar omits.
// ABOUTME: TestLanguage extends it, so without this copy the Tests suite cannot compile (issue #45).

package org.nlogo.util

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.{ Failed, Outcome, Succeeded }

// Copied verbatim from NetLogo v7.0.4 parser-jvm/src/test/util/AnyFunSuiteEx.scala.
// Remove once NetLogo publishes it in the tests artifact.
// this wrapper trait provides a mechanism for ensuring that exceptions in stray threads
// result in a failed test, when they would otherwise silently print to stdout (Isaac B 11/18/25)
trait AnyFunSuiteEx extends AnyFunSuite {
  private var outcome: Outcome = Succeeded

  Thread.setDefaultUncaughtExceptionHandler { (_, t) =>
    if (outcome == Succeeded)
      outcome = Failed(t)
  }

  override def withFixture(test: NoArgTest): Outcome = {
    outcome = Succeeded

    super.withFixture(test) match {
      case Succeeded => outcome
      case o => o
    }
  }
}
