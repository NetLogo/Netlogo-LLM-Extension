// ABOUTME: Serializes tests that mutate the global ModelRegistry (overrides, user catalog reloads)
// ABOUTME: Mix into any suite whose tests load or clear catalog layers, so parallel suites never interleave
package org.nlogo.extensions.llm.providers

import org.scalatest.{Args, Status, Suite, SuiteMixin}

/**
 * ModelRegistry is one global object, and sbt runs suites in parallel. A suite
 * that loads a models-override.yaml or a user catalog and then asserts on the
 * result can be undone midway by another suite clearing it. Every such suite
 * mixes this in, so their tests take turns.
 */
trait RegistryLock extends SuiteMixin { this: Suite =>
  abstract override protected def runTest(testName: String, args: Args): Status =
    RegistryLock.synchronized { super.runTest(testName, args) }
}

object RegistryLock
