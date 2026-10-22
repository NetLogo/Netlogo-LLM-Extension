// ABOUTME: Integration tests that run against a real local Ollama server
// ABOUTME: Run separately via: sbt "testOnly org.nlogo.extensions.llm.OllamaIntegrationTests"

package org.nlogo.extensions.llm

import java.io.File

import org.scalatest.BeforeAndAfterAll

import org.nlogo.headless.TestLanguage

object OllamaIntegrationTests {
  val testFileNames = Seq("tests-ollama.txt")
  val testFiles     = testFileNames.map( (f) => (new File(f)).getCanonicalFile )
}

class OllamaIntegrationTests extends TestLanguage(OllamaIntegrationTests.testFiles) with BeforeAndAfterAll {
  System.setProperty("org.nlogo.preferHeadless", "true")

  override def afterAll(): Unit = {
    val file = new File("tmp/llm")
    def deleteRec(f: File): Unit = {
      if (f.isDirectory) {
        f.listFiles().foreach(deleteRec)
      }
      f.delete()
    }
    deleteRec(file)
    super.afterAll()
  }
}
