// (C) Uri Wilensky. https://github.com/NetLogo/LLM-Extension

package org.nlogo.extensions.llm

import org.scalatest.BeforeAndAfterAll

import java.io.File
import org.nlogo.headless.TestLanguage
import org.nlogo.extensions.llm.config.ConfigStore
import org.nlogo.extensions.llm.providers.{DeterministicDecisionTestProvider, DeterministicTestProvider}
import scala.util.Success

object Tests {
  val testFileNames = Seq("tests.txt")
  val testFiles     = testFileNames.map( (f) => (new File(f)).getCanonicalFile )
}

class Tests extends TestLanguage(Tests.testFiles) with BeforeAndAfterAll {
  System.setProperty("org.nlogo.preferHeadless", "true")

  override def beforeAll(): Unit = {
    super.beforeAll()

    LLMExtension.setProviderFactoryOverride { (configStore, ec) =>
      // "typesafe" gets the decision stand-in so llm:decide runs end to end;
      // every other provider stays a plain chat provider with no decision API.
      val provider =
        if (configStore.get(ConfigStore.PROVIDER).contains("typesafe"))
          new DeterministicDecisionTestProvider()(using ec)
        else new DeterministicTestProvider()(using ec)
      configStore.toMap.foreach { case (key, value) =>
        provider.setConfig(key, value)
      }
      Success(provider)
    }
  }

  override def afterAll(): Unit = {
    LLMExtension.clearProviderFactoryOverride()

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
