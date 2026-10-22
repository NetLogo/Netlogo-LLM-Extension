// ABOUTME: Deterministic tests for the completion budget sent to OpenAI-format reasoning models
// ABOUTME: Covers lifting the default max_tokens and the clear error for an empty reply cut off at the limit
package org.nlogo.extensions.llm.providers

import scala.concurrent.ExecutionContext.Implicits.global

import org.scalatest.funsuite.AnyFunSuite

import org.nlogo.extensions.llm.LLMExtension
import org.nlogo.extensions.llm.config.ConfigStore
import org.nlogo.extensions.llm.models.{ChatMessage, ChatRequest, ChatResponse}

/** Builds requests from the provider's own config, as a real call would. */
private class BudgetInspectableOpenAI extends OpenAIProvider {
  def set(key: String, value: String): Unit = setConfig(key, value)
  def body(): ujson.Value = createProviderRequest(buildRequest(Seq(ChatMessage.user("hi"))))
  def parse(body: String): ChatResponse = parseProviderResponse(body, "gpt-6-luna")
}

class ReasoningBudgetSpec extends AnyFunSuite {

  ProviderRegistrations.registerAll()

  private def provider(model: String): BudgetInspectableOpenAI = {
    val p = new BudgetInspectableOpenAI
    p.set(ConfigStore.MODEL, model)
    p
  }

  test("a reasoning model left at the default max_tokens gets a larger completion budget") {
    val body = provider("gpt-6-luna").body()
    assert(body("max_completion_tokens").num.toInt == OpenAICompatibleProvider.MinReasoningCompletionTokens)
    assert(OpenAICompatibleProvider.MinReasoningCompletionTokens >= 4000)
    assert(!body.obj.contains("max_tokens"))
  }

  test("a max_tokens the modeler set is sent unchanged to a reasoning model") {
    Seq("1000", "2000", "12000").foreach { n =>
      val p = provider("gpt-6-luna")
      p.set(ConfigStore.MAX_TOKENS, n)
      assert(p.body()("max_completion_tokens").num.toInt == n.toInt, n)
    }
  }

  test("defaults copied from the extension's config still count as unset; user values do not") {
    val global = ConfigStore.withDefaults()
    global.set(ConfigStore.MODEL, "gpt-6-luna")
    val seeded = new BudgetInspectableOpenAI
    ProviderFactory.copyConfig(global, seeded)
    assert(seeded.body()("max_completion_tokens").num.toInt == OpenAICompatibleProvider.MinReasoningCompletionTokens)

    global.set(ConfigStore.MAX_TOKENS, ConfigStore.DEFAULT_MAX_TOKENS)
    val chosen = new BudgetInspectableOpenAI
    ProviderFactory.copyConfig(global, chosen)
    assert(chosen.body()("max_completion_tokens").num.toInt == ConfigStore.DEFAULT_MAX_TOKENS.toInt)
  }

  test("a config file that sets max_tokens to the default value counts as set") {
    val global = ConfigStore.withDefaults()
    global.loadFromMap(Map(ConfigStore.PROVIDER -> "openai", ConfigStore.MODEL -> "gpt-6-luna",
      ConfigStore.MAX_TOKENS -> "1000"))
    assert(!global.isDefault(ConfigStore.MAX_TOKENS))
    val restored = ConfigStore.withDefaults()
    restored.loadFromMap(ConfigStore.withDefaults().toMap, ConfigStore.withDefaults().defaultKeys)
    assert(restored.isDefault(ConfigStore.MAX_TOKENS))
  }

  test("a non-reasoning model keeps the default max_tokens") {
    val body = provider("gpt-4o-mini").body()
    assert(body("max_tokens").num.toInt == 1000)
    assert(!body.obj.contains("max_completion_tokens"))
  }

  private def reply(content: String, finish: String): String =
    s"""{"id":"c","created":1700000000,"model":"gpt-6-luna",
       | "choices":[{"index":0,"message":{"role":"assistant","content":${ujson.write(content)}},"finish_reason":"$finish"}],
       | "usage":{"prompt_tokens":5,"completion_tokens":4000,"total_tokens":4005,
       |   "completion_tokens_details":{"reasoning_tokens":4000}}}""".stripMargin

  test("an empty reply cut off at the token limit parses with its usage, and gets an error naming max_tokens") {
    val response = provider("gpt-6-luna").parse(reply("", "length"))
    assert(response.usage.map(_.outputTokens).contains(4000L))
    val message = LLMExtension.emptyReplyMessage(response).getOrElse(fail("expected an empty-reply error"))
    assert(message.contains("max_tokens"), message)
    assert(message.contains("empty"), message)
    assert(message.contains("4000 output tokens"), message)
  }

  test("a non-empty reply cut off at the limit, or an empty one that stopped normally, is not an error") {
    val partial = provider("gpt-6-luna").parse(reply("partial", "length"))
    assert(partial.firstContent.contains("partial"))
    assert(LLMExtension.emptyReplyMessage(partial).isEmpty)
    assert(LLMExtension.emptyReplyMessage(provider("gpt-6-luna").parse(reply("", "stop"))).isEmpty)
  }
}
