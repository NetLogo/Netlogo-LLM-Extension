package org.nlogo.extensions.llm

import org.nlogo.api._
import org.nlogo.core.{LogoList, Syntax}
import org.nlogo.extensions.llm.config.{ConfigLoader, ConfigStore, ProfileStore}
import org.nlogo.extensions.llm.providers.{DecisionProvider, LLMProvider, ProviderDescriptor, ProviderFactory, ProviderRegistry, ProviderRegistrations, ModelRegistry, OllamaProvider, ReadinessCheck, RetryPolicy}
import org.nlogo.extensions.llm.models.{ChatMessage, ChatResponse, DecisionAnswer, DecisionQuestion, DecisionResult, EnumFormat, JsonObjectFormat, JsonSchemaFormat, ResponseFormat, Usage}
import org.nlogo.extensions.llm.utils.JsonToNetLogo
import scala.collection.mutable.{ArrayBuffer, WeakHashMap}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration._
import scala.util.{Try, Success, Failure}
import scala.jdk.CollectionConverters._
import io.circe.yaml.parser
import io.circe.{Json, HCursor}
import java.nio.file.{Files, Paths}
import java.nio.charset.StandardCharsets

object LLMExtension {
  @volatile private var providerFactoryOverride:
    Option[(ConfigStore, ExecutionContext) => Try[LLMProvider]] = None

  def setProviderFactoryOverride(
    factory: (ConfigStore, ExecutionContext) => Try[LLMProvider]
  ): Unit = {
    providerFactoryOverride = Some(factory)
  }

  def clearProviderFactoryOverride(): Unit = {
    providerFactoryOverride = None
  }

  /**
   * Probe an Ollama server for reachability (synchronous, short timeout).
   * Takes the provider so tests can hand in one backed by a stub.
   */
  private[llm] def probeOllama(config: ConfigStore, provider: OllamaProvider): Boolean = {
    try {
      val baseUrl = ConfigStore.resolveBaseUrl(config, ConfigStore.OLLAMA_BASE_URL, ConfigStore.DEFAULT_OLLAMA_BASE_URL)
      provider.setConfig(ConfigStore.OLLAMA_BASE_URL, baseUrl)

      val checkFuture = provider.checkServerConnection()
      Await.result(checkFuture, 1.second)
    } catch {
      case ex: Exception =>
        System.err.println(s"WARNING: Ollama reachability check failed: ${ex.getMessage}")
        false
    }
  }

  /**
   * The error for a reply that is empty because the model stopped at its token
   * limit (finish_reason "length") before writing any answer, or None.
   */
  private[llm] def emptyReplyMessage(response: ChatResponse): Option[String] =
    response.choices.headOption.collect {
      case c if c.finishReason == "length" && c.message.content.trim.isEmpty && c.refusal.isEmpty &&
          response.thinking.forall(_.trim.isEmpty) =>
        val used = response.usage.map(u => s" (${u.outputTokens} output tokens)").getOrElse("")
        s"${response.model} returned an empty reply: it used its whole token budget$used before " +
          "answering, and reasoning tokens count against that budget. Set max_tokens in your config " +
          "above that, e.g. max_tokens=8000."
    }

  private[llm] def createProvider(configStore: ConfigStore)(
    implicit ec: ExecutionContext
  ): Try[LLMProvider] = {
    providerFactoryOverride match {
      case Some(factory) => factory(configStore, ec)
      case None => ProviderFactory.createProviderFromConfig(configStore)
    }
  }
}

/**
 * Raised when the model declines to answer. Primitives let it through
 * unwrapped so the modeler sees exactly "model refused: <reason>".
 */
final class ModelRefusedException(val refusal: String)
  extends ExtensionException(s"model refused: $refusal")

/** Raised by a free-text primitive on a provider that only returns decisions. */
final class TextUnsupportedException(message: String) extends ExtensionException(message)

/** Raised by llm:decide / llm:decide-all on a provider without a decision API. */
final class NoDecisionApiException(message: String) extends ExtensionException(message)

/**
 * Main extension class for NetLogo Multi-LLM Extension
 * 
 * This extension provides a unified interface for multiple LLM providers
 * including OpenAI, Anthropic, Gemini, and Ollama.
 */
class LLMExtension extends DefaultClassManager {
  
  // Global configuration store
  private val configStore = ConfigStore.withDefaults()
  
  // Current provider instance
  private var currentProvider: Option[LLMProvider] = None

  // Named configurations loaded with llm:load-profile, each with its own
  // provider, and which agent is bound to which. An agent with no binding
  // uses the global configStore/currentProvider pair above, unchanged.
  private val profiles: ProfileStore = new ProfileStore(cs => LLMExtension.createProvider(cs))
  private val agentProfile: WeakHashMap[Agent, String] = WeakHashMap()
  private val profileLock = new Object
  
  // Per-agent conversation history
  private val messageHistory: WeakHashMap[Agent, ArrayBuffer[ChatMessage]] = WeakHashMap()

  // Guards messageHistory and every contained buffer. Never held across Await
  // or provider calls; critical sections are small snapshots/appends only.
  private val historyLock = new Object

  // Token accounting. Per-agent totals live in a WeakHashMap like history so a
  // dead turtle's counters are collected with it; the run total is kept
  // separately so it survives agent death. Both reset on clear-all.
  private val agentUsage: WeakHashMap[Agent, UsageTotals] = WeakHashMap()
  private var runUsage: UsageTotals = UsageTotals.empty
  private val usageLock = new Object

  // Run generation, bumped by clear-all. Each primitive captures it at launch
  // and its history/usage writes are dropped if a clear-all has happened
  // since, so a reply still in flight cannot leak into the next run. Volatile
  // so the check inside historyLock/usageLock sees clear-all's bump.
  @volatile private var generation: Long = 0L

  // Execution context for async operations
  implicit private val ec: ExecutionContext = ExecutionContext.global
  
  /**
   * Create an AwaitableReporter that wraps a Future to provide truly async behavior
   * The Future starts immediately but execution defers until runresult is called
   */
  private def createAwaitableReporter(future: Future[String], timeout: FiniteDuration): AnonymousReporter = {
    new AnonymousReporter {
      override def syntax: Syntax = Syntax.reporterSyntax(right = List(), ret = Syntax.StringType)
      
      override def report(context: Context, args: Array[AnyRef]): AnyRef = {
        try {
          // The budget in force for the calling agent when the request was
          // launched: a later config change or profile rebinding cannot alter
          // how long an already-pending call may take.
          Await.result(future, timeout)
        } catch {
          case e: ModelRefusedException => throw e
          case e: Exception =>
            throw new ExtensionException(s"Async LLM operation failed: ${e.getMessage}")
        }
      }
    }
  }
  
  /**
   * Called when the extension is loaded to register primitives
   */
  override def load(manager: PrimitiveManager): Unit = {
    // Initialize provider registry before anything else
    ProviderRegistrations.registerAll()

    // Configuration primitives
    manager.addPrimitive("set-provider", SetProviderCommand)
    manager.addPrimitive("set-api-key", SetApiKeyCommand)
    manager.addPrimitive("set-model", SetModelCommand)
    manager.addPrimitive("load-config", LoadConfigCommand)
    
    // Core chat primitives
    manager.addPrimitive("chat", ChatReporter)
    manager.addPrimitive("chat-async", ChatAsyncReporter)
    manager.addPrimitive("chat-all", ChatAllReporter)
    manager.addPrimitive("chat-with-template", ChatWithTemplateReporter)
    manager.addPrimitive("chat-with-thinking", ChatWithThinkingReporter)
    manager.addPrimitive("choose", ChooseReporter)

    // Decision primitives (providers with a DecisionProvider API)
    manager.addPrimitive("decide", DecideReporter)
    manager.addPrimitive("decide-all", DecideAllReporter)

    // Structured output primitives
    manager.addPrimitive("chat-with-schema", ChatWithSchemaReporter)
    manager.addPrimitive("chat-json", ChatJsonReporter)
    manager.addPrimitive("get", GetReporter)

    // Code validation primitive
    manager.addPrimitive("compile-error", CompileErrorReporter)

    // Thinking/reasoning configuration primitives
    manager.addPrimitive("set-thinking", SetThinkingCommand)
    manager.addPrimitive("set-reasoning-effort", SetReasoningEffortCommand)
    manager.addPrimitive("set-thinking-budget", SetThinkingBudgetCommand)
    
    // History management primitives
    manager.addPrimitive("history", HistoryReporter)
    manager.addPrimitive("set-history", SetHistoryCommand)
    manager.addPrimitive("clear-history", ClearHistoryCommand)
    
    // Provider information primitives
    manager.addPrimitive("providers", ProvidersReporter)
    manager.addPrimitive("providers-all", ProvidersAllReporter)
    manager.addPrimitive("provider-status", ProviderStatusReporter)
    manager.addPrimitive("provider-help", ProviderHelpReporter)
    manager.addPrimitive("list-models", ListModelsReporter)
    manager.addPrimitive("active", ActiveReporter)
    manager.addPrimitive("config", ConfigReporter)

    // Token accounting primitives
    manager.addPrimitive("usage", UsageReporter)
    manager.addPrimitive("usage-total", UsageTotalReporter)

    // Per-agent profile primitives
    manager.addPrimitive("load-profile", LoadProfileCommand)
    manager.addPrimitive("use-profile", UseProfileCommand)
    manager.addPrimitive("profile", ProfileReporter)
    manager.addPrimitive("profiles", ProfilesReporter)
  }
  
  /**
   * Called when NetLogo calls clear-all or when the model is reset
   */
  override def clearAll(): Unit = {
    // Bump before clearing so any write that lands after the clear is stale.
    historyLock.synchronized { usageLock.synchronized { generation += 1 } }
    historyLock.synchronized { messageHistory.clear() }
    usageLock.synchronized {
      agentUsage.clear()
      runUsage = UsageTotals.empty
    }
    // Bindings die with the agents; loaded profiles persist like the global config does.
    profileLock.synchronized { agentProfile.clear() }
  }
  
  /**
   * Initialize or update the current provider based on configuration
   */
  private def ensureProvider(): LLMProvider = {
    currentProvider match {
      case Some(provider) => provider
      case None =>
        LLMExtension.createProvider(configStore) match {
          case Success(provider) =>
            currentProvider = Some(provider)
            provider
          case Failure(e) =>
            throw new ExtensionException(s"Failed to initialize LLM provider: ${e.getMessage}")
        }
    }
  }
  
  /** The profile name an agent is bound to, or the reserved default name. */
  private def profileNameFor(agent: Agent): String =
    profileLock.synchronized { agentProfile.getOrElse(agent, ProfileStore.DefaultName) }

  /**
   * The provider a call from this agent goes through: the bound profile's
   * provider, or the global one. The instance is resolved once per call and
   * captured by the caller, so an async request keeps its provider even if
   * the agent is rebound before the reply arrives.
   */
  private def ensureProvider(agent: Agent): LLMProvider =
    profileNameFor(agent) match {
      case ProfileStore.DefaultName => ensureProvider()
      case name =>
        profiles.provider(name) match {
          case Success(provider) => provider
          case Failure(e) =>
            throw new ExtensionException(s"Failed to initialize LLM provider for profile '$name': ${e.getMessage}")
        }
    }

  /** The configuration an agent's calls are shaped by. */
  private def effectiveConfig(agent: Agent): ConfigStore =
    profileNameFor(agent) match {
      case ProfileStore.DefaultName => configStore
      case name => profiles.get(name).map(_.config).getOrElse(configStore)
    }

  /**
   * Get or create conversation history for an agent.
   * Callers must hold historyLock — the buffer must not escape a locked section.
   */
  private def getAgentHistory(agent: Agent): ArrayBuffer[ChatMessage] = {
    messageHistory.getOrElseUpdate(agent, ArrayBuffer.empty[ChatMessage])
  }

  /**
   * Immutable snapshot of an agent's history, safe to build a request from
   * while other threads mutate the live buffer.
   */
  private def snapshotHistory(agent: Agent): Seq[ChatMessage] =
    historyLock.synchronized { getAgentHistory(agent).toSeq }

  /**
   * Append a user/assistant exchange to an agent's history atomically.
   * Re-resolves the buffer by agent so a commit racing set-history lands in
   * the current buffer, not a detached stale one. Dropped if clear-all ran
   * since the call launched in generation `gen`.
   */
  private def commitExchange(agent: Agent, gen: Long, user: ChatMessage, assistant: ChatMessage): Unit =
    historyLock.synchronized {
      if (gen == generation) {
        val h = getAgentHistory(agent)
        h += user
        h += assistant
      }
    }

  /**
   * Credit a provider reply to the calling agent and to the run.
   *
   * Called as soon as a response arrives, before the caller decides whether
   * the reply is acceptable: tokens were billed either way, so a reply the
   * extension then rejects (schema parse failure, unmatched choice) still
   * counts. A call that never produced a response records nothing, and a
   * call launched before the latest clear-all (generation `gen`) is dropped.
   */
  private def recordUsage(agent: Agent, gen: Long, response: ChatResponse): Unit =
    recordUsage(agent, gen, response.usage.getOrElse(Usage.empty))

  /** As above, for calls that report usage without a ChatResponse (decisions). */
  private def recordUsage(agent: Agent, gen: Long, usage: Usage): Unit = {
    val delta = UsageTotals(usage, 1L)
    usageLock.synchronized {
      if (gen == generation) {
        agentUsage.update(agent, agentUsage.getOrElse(agent, UsageTotals.empty).plus(delta))
        runUsage = runUsage.plus(delta)
      }
    }
  }

  /**
   * Fail a call the model declined to answer.
   *
   * Called after recordUsage, so the billed tokens still count, and before any
   * commitExchange, so the refused exchange never enters history.
   */
  private def rejectRefusal(response: ChatResponse): Unit =
    response.refusal.foreach(text => throw new ModelRefusedException(text))

  /**
   * Fail a call whose reply came back empty because the model hit its token
   * limit (a reasoning model can spend the whole budget thinking). Called
   * after recordUsage like rejectRefusal, so the billed tokens still count.
   */
  private def rejectEmptyReply(response: ChatResponse): Unit =
    LLMExtension.emptyReplyMessage(response).foreach(m => throw new ExtensionException(m))

  /**
   * Fail a free-text call on a provider that only returns decisions, before
   * anything is sent. Raised unwrapped so the modeler sees which primitives
   * to use instead.
   */
  private def requireText(provider: LLMProvider): Unit =
    provider match {
      case d: DecisionProvider => d.textUnsupportedMessage.foreach(m => throw new TextUnsupportedException(m))
      case _ => ()
    }

  /** The agent's provider as a DecisionProvider, or a clear error naming the configured provider. */
  private def decisionProviderFor(agent: Agent): DecisionProvider =
    ensureProvider(agent) match {
      case d: DecisionProvider => d
      case _ =>
        val name = effectiveConfig(agent).getOrElse(ConfigStore.PROVIDER, ConfigStore.DEFAULT_PROVIDER)
        throw new NoDecisionApiException(
          s"""provider "$name" has no decision API; use llm:choose, or llm:set-provider "typesafe"""")
    }

  /**
   * Read a llm:decide spec: ["choice" [options]], ["score" [levels]] or
   * ["yes-no"]. Checked before any request so a bad spec costs nothing.
   */
  private def parseDecisionSpec(spec: LogoList, primName: String): DecisionQuestion = {
    def fail(msg: String): Nothing = throw new ExtensionException(s"$primName: $msg")
    spec.toVector match {
      case (kind: String) +: rest =>
        def strings(items: LogoList, what: String): Seq[String] =
          items.toVector.map {
            case s: String if s.nonEmpty => s
            case _ => fail(s""""$kind" $what must be non-empty strings""")
          }
        kind match {
          case "choice" =>
            rest match {
              case Vector(opts: LogoList) if opts.size >= 1 && opts.size <= DecisionQuestion.MaxChoiceOptions =>
                val options = strings(opts, "options")
                options.diff(options.distinct).headOption.foreach { dup =>
                  fail(s""""choice" options must be distinct, but "$dup" appears more than once""")
                }
                DecisionQuestion.Choice(options)
              case _ => fail(s""""choice" needs a list of 1 to ${DecisionQuestion.MaxChoiceOptions} options""")
            }
          case "score" =>
            rest match {
              case Vector(levels: LogoList)
                  if levels.size >= DecisionQuestion.MinScoreLevels && levels.size <= DecisionQuestion.MaxScoreLevels =>
                DecisionQuestion.Score(strings(levels, "levels"))
              case _ =>
                fail(s""""score" needs a list of ${DecisionQuestion.MinScoreLevels} to ${DecisionQuestion.MaxScoreLevels} levels""")
            }
          case "yes-no" =>
            if (rest.isEmpty) DecisionQuestion.YesNo else fail(""""yes-no" takes no list""")
          case other =>
            fail(s"""unknown decision type "$other"; use "choice", "score" or "yes-no"""")
        }
      case _ =>
        fail("""spec must be ["choice" [options]], ["score" [levels]] or ["yes-no"]""")
    }
  }

  /**
   * A decision as [answer probability distribution]. A yes-no answer and its
   * distribution labels are NetLogo booleans; a score's answer is a number.
   */
  private def decisionToLogo(result: DecisionResult): LogoList = {
    val (answer, yesNo): (AnyRef, scala.Boolean) = result.answer match {
      case DecisionAnswer.Picked(option)  => (option, false)
      case DecisionAnswer.Expected(value) => (Double.box(value), false)
      case DecisionAnswer.Verdict(yes)    => (Boolean.box(yes), true)
    }
    val distribution = result.distribution.map { case (label, p) =>
      val l: AnyRef = if (yesNo) Boolean.box(label == "true") else label
      LogoList(l, Double.box(p))
    }
    LogoList(answer, Double.box(result.probability), LogoList.fromVector(distribution.toVector))
  }

  private def usageFor(agent: Agent): UsageTotals =
    usageLock.synchronized { agentUsage.getOrElse(agent, UsageTotals.empty) }

  private def usageForRun: UsageTotals =
    usageLock.synchronized { runUsage }

  /** The assistant message to store in history, mirroring what chat(messages) returned before. */
  private def replyMessage(response: ChatResponse): ChatMessage =
    response.firstMessage.getOrElse(
      throw new RuntimeException("No response message received from provider")
    )

  /**
   * Get timeout from config, falling back to 30 seconds
   */
  private def getTimeoutSeconds(config: ConfigStore): Int =
    config.get(ConfigStore.TIMEOUT_SECONDS).map { s =>
      scala.util.Try(s.toInt).getOrElse {
        System.err.println(s"WARNING: Invalid timeout_seconds value '$s' (not a valid integer), using default 30")
        30
      }
    }.getOrElse(30)

  /**
   * How long to wait on an LLM future.
   *
   * `timeout_seconds` is the budget for the request itself. Time spent sitting out
   * a provider's rate-limit window is not the request being slow, so it gets its own
   * budget rather than eating the request's: waiting out an 18s quota window must not
   * trip a 30s request timeout. The await bound is therefore the request timeout plus
   * the retry budget, so `timeout_seconds` keeps its meaning and modelers who lower it
   * do not thereby lose the ability to recover from a rate limit.
   *
   * Queue time behind a concurrency cap is NOT added to this bound, and deliberately
   * so. Queue depth is set by how many agents call in a tick, not by the cap: with 200
   * turtles and a cap of 4, the last request waits out ~50 waves. No fixed formula
   * covers that, and one that pretended to would fail precisely where fan-out is
   * widest — the case throttling exists for.
   *
   * So a heavily throttled model can still exceed this bound while queued, and that is
   * a documented, intentional limitation rather than a solved problem: a modeler who
   * sets a cap far below their fan-out should raise `timeout_seconds` accordingly. The
   * bound is unchanged from before throttling existed, so an unthrottled model — the
   * default — behaves exactly as it did.
   */
  private def awaitTimeoutFor(config: ConfigStore): FiniteDuration = {
    val retryBudget = config.get(RetryPolicy.MAX_ELAPSED_SECONDS)
      .flatMap(s => scala.util.Try(s.trim.toDouble).toOption)
      .filter(d => d >= 0.0 && d.isFinite)
      .map(d => (d * 1000.0).toLong.millis)
      .getOrElse(RetryPolicy.DefaultMaxElapsed)
    getTimeoutSeconds(config).seconds + retryBudget
  }
  
  /**
   * Check if a provider has an API key configured
   */
  private def hasApiKey(providerName: String): Boolean = hasApiKey(providerName, configStore)

  private def hasApiKey(providerName: String, config: ConfigStore): Boolean = {
    val providerKeyName = ConfigStore.getProviderApiKeyName(providerName)
    config.get(providerKeyName).orElse(config.get(ConfigStore.API_KEY)) match {
      case Some(key) => key.trim.nonEmpty
      case None => false
    }
  }

  /**
   * The readiness check llm:load-config and llm:load-profile share: a cloud
   * provider needs a key in this config, a local one needs a reachable server.
   */
  private def checkReadiness(desc: ProviderDescriptor, providerName: String, config: ConfigStore): Unit =
    desc.readinessCheck match {
      case ReadinessCheck.ServerReachable =>
        if (!isOllamaReachable(config)) {
          val baseUrl = ConfigStore.resolveBaseUrl(config, desc.baseUrlConfigKey, desc.defaultBaseUrl)
          throw new ExtensionException(
            s"Config loaded but ${desc.displayName} not reachable at $baseUrl. Please start the server or change ${desc.baseUrlConfigKey} in config. For help: print llm:provider-help \"${desc.name}\""
          )
        }
      case ReadinessCheck.ApiKey =>
        if (!hasApiKey(providerName, config)) {
          throw new ExtensionException(
            s"Config loaded but ${desc.displayName} provider requires an API key. Set '${desc.apiKeyConfigKey}' in config. For help: print llm:provider-help \"${desc.name}\""
          )
        }
    }
  
  /**
   * Check if Ollama is reachable (synchronous with short timeout)
   */
  private def isOllamaReachable: Boolean = isOllamaReachable(configStore)

  private def isOllamaReachable(config: ConfigStore): Boolean =
    LLMExtension.probeOllama(config, new OllamaProvider())
  
  /**
   * Check if a provider is ready to use.
   * Uses the readinessCheck from the provider's descriptor.
   */
  private def isProviderReady(providerName: String): Boolean = {
    ProviderRegistry.get(providerName) match {
      case Some(desc) => desc.readinessCheck match {
        case ReadinessCheck.ServerReachable => isOllamaReachable
        case ReadinessCheck.ApiKey => hasApiKey(providerName)
      }
      case None => false
    }
  }
  
  /**
   * Case class for YAML template structure
   */
  case class Template(system: String, template: String)
  
  /**
   * Load a YAML template file and parse it
   *
   * @param filename Path to the template file
   * @param modelDir Optional directory of the currently-open NetLogo model
   */
  private def loadTemplate(filename: String, modelDir: Option[String] = None): Try[Template] = {
    Try {
      val possiblePaths = modelDir.map(dir =>
        Paths.get(dir, filename)
      ).toSeq ++ Seq(
        Paths.get(filename),
        Paths.get(System.getProperty("user.dir"), filename)
      )

      val path = possiblePaths.find(Files.exists(_)).getOrElse {
        throw new IllegalArgumentException(
          s"Template file not found: $filename. Place the file in the same directory as your NetLogo model or in the current working directory."
        )
      }

      val content = Files.readString(path, StandardCharsets.UTF_8)
      parser.parse(content) match {
        case Right(json) =>
          val cursor: HCursor = json.hcursor
          val system = cursor.downField("system").as[String].getOrElse("")
          val template = cursor.downField("template").as[String].getOrElse {
            throw new RuntimeException(s"Template file '$filename' is missing required 'template' field")
          }
          Template(system, template)
        case Left(error) =>
          throw new RuntimeException(s"Failed to parse YAML: ${error.getMessage}")
      }
    }
  }
  
  /**
   * Substitute variables in a template string
   */
  private def substituteVariables(template: String, variables: Map[String, String]): String = {
    variables.foldLeft(template) { case (text, (key, value)) =>
      text.replace(s"{$key}", value)
    }
  }
  
  /**
   * Convert NetLogo variable list to Scala Map
   */
  private def parseVariables(variablesList: LogoList): Map[String, String] = {
    variablesList.map {
      case varList: LogoList if varList.size == 2 =>
        varList(0).toString -> varList(1).toString
      case _ =>
        throw new ExtensionException("Variables must be lists of [key value] pairs")
    }.toMap
  }
  
  // Configuration Commands
  
  object SetProviderCommand extends Command {
    override def getSyntax: Syntax = Syntax.commandSyntax(right = List(Syntax.StringType))
    
    override def perform(args: Array[Argument], context: Context): Unit = {
      val providerName = args(0).getString.toLowerCase.trim
      
      // Check if provider is supported
      if (!ProviderFactory.isSupported(providerName)) {
        throw new ExtensionException(
          s"Unknown provider: '$providerName'. Supported providers: ${ProviderFactory.getSupportedProviders.mkString(", ")}"
        )
      }
      
      // Apply provider defaults and set provider
      ProviderFactory.applyProviderSwitch(configStore, providerName)
      
      // Validate immediately using descriptor
      ProviderRegistry.get(providerName).foreach { desc =>
        desc.readinessCheck match {
          case ReadinessCheck.ServerReachable =>
            if (!isOllamaReachable) {
              val baseUrl = ConfigStore.resolveBaseUrl(configStore, desc.baseUrlConfigKey, desc.defaultBaseUrl)
              throw new ExtensionException(
                s"${desc.displayName} not reachable at $baseUrl. Please start the server or change ${desc.baseUrlConfigKey}. For help: print llm:provider-help \"$providerName\""
              )
            }
          case ReadinessCheck.ApiKey =>
            if (!hasApiKey(providerName)) {
              throw new ExtensionException(
                s"${desc.displayName} provider requires an API key. Set '${desc.apiKeyConfigKey}' in config or call llm:set-api-key. For help: print llm:provider-help \"$providerName\""
              )
            }
        }
      }
      
      // Force re-initialization with new provider
      currentProvider = None
    }
  }
  
  object SetApiKeyCommand extends Command {
    override def getSyntax: Syntax = Syntax.commandSyntax(right = List(Syntax.StringType))
    
    override def perform(args: Array[Argument], context: Context): Unit = {
      val apiKey = args(0).getString
      val currentProviderName = configStore.getOrElse(ConfigStore.PROVIDER, ConfigStore.DEFAULT_PROVIDER)
      
      // Store in both provider-specific key and generic key (for backwards compatibility)
      val providerKeyName = ConfigStore.getProviderApiKeyName(currentProviderName)
      configStore.set(providerKeyName, apiKey)
      configStore.set(ConfigStore.API_KEY, apiKey)
      
      // Update current provider if initialized
      currentProvider.foreach(_.setConfig(ConfigStore.API_KEY, apiKey))
    }
  }
  
  object SetModelCommand extends Command {
    override def getSyntax: Syntax = Syntax.commandSyntax(right = List(Syntax.StringType))
    
    override def perform(args: Array[Argument], context: Context): Unit = {
      val model = args(0).getString
      val providerName = configStore.getOrElse(ConfigStore.PROVIDER, ConfigStore.DEFAULT_PROVIDER)
      
      // Warn if model is not in the known list, but allow it anyway
      if (!ModelRegistry.isValidModel(providerName, model)) {
        System.err.println(s"WARNING: Model '$model' is not in the known model list for '$providerName'. It will be used anyway — if the model name is wrong, the API will return an error.")
      }
      
      configStore.set(ConfigStore.MODEL, model)
      currentProvider.foreach(_.setConfig(ConfigStore.MODEL, model))
    }
  }
  
  object LoadConfigCommand extends Command {
    override def getSyntax: Syntax = Syntax.commandSyntax(right = List(Syntax.StringType))

    override def perform(args: Array[Argument], context: Context): Unit = {
      val filename = args(0).getString

      // Extract model directory from workspace
      val modelDir = Option(context.workspace.getModelPath).flatMap { path =>
        Option(new java.io.File(path).getParent)
      }

      ConfigLoader.loadFromFile(filename, modelDir) match {
        case Success(config) =>
          // Validate the provider name against the raw config BEFORE mutating
          // configStore, so a rejected load leaves the session state unchanged.
          val providerName = config.getOrElse(ConfigStore.PROVIDER, ConfigStore.DEFAULT_PROVIDER)
          val desc = ProviderRegistry.get(providerName.toLowerCase.trim).getOrElse {
            throw new ExtensionException(
              s"Unknown provider '$providerName' in config. Supported: ${ProviderRegistry.allNames.toList.sorted.mkString(", ")}"
            )
          }

          // Snapshot old config so we can roll back on readiness-check failure.
          val previousConfig = configStore.toMap
          val previousDefaults = configStore.defaultKeys
          configStore.loadFromMap(config)

          // Load model override file if available
          modelDir match {
            case Some(dir) =>
              ModelRegistry.loadOverride(dir).foreach { message =>
                println(message)
              }
            case None =>
              ModelRegistry.clearOverride()
          }

          // Readiness check uses the now-loaded config (needs apiKeyConfigKey lookup).
          // Roll back on failure so llm:active and friends keep the prior state.
          try {
            checkReadiness(desc, providerName, configStore)
          } catch {
            case e: ExtensionException =>
              configStore.loadFromMap(previousConfig, previousDefaults)
              throw e
          }

          currentProvider = None // Force re-initialization with new config
        case Failure(e) =>
          throw new ExtensionException(s"Failed to load configuration from '$filename': ${e.getMessage}")
      }
    }
  }
  
  // Chat Primitives
  
  object ChatReporter extends Reporter {
    override def getSyntax: Syntax = Syntax.reporterSyntax(
      right = List(Syntax.StringType),
      ret = Syntax.StringType
    )
    
    override def report(args: Array[Argument], context: Context): AnyRef = {
      val inputText = args(0).getString
      val agent = context.getAgent

      try {
        val gen = generation
        val provider = ensureProvider(agent)
        requireText(provider)

        val userMessage = ChatMessage.user(inputText)

        // Send chat request with user message included, but don't mutate history yet
        val responseFuture = provider.chatWithFullResponse(snapshotHistory(agent) :+ userMessage)
        val response = Await.result(responseFuture, awaitTimeoutFor(effectiveConfig(agent)))
        recordUsage(agent, gen, response)
        rejectRefusal(response)
        rejectEmptyReply(response)
        val responseMessage = replyMessage(response)

        // Only commit both messages after success
        commitExchange(agent, gen, userMessage, responseMessage)

        responseMessage.content

      } catch {
        case e: ModelRefusedException => throw e
        case e: TextUnsupportedException => throw e
        case e: Exception =>
          throw new ExtensionException(s"LLM chat failed: ${e.getMessage}")
      }
    }
  }
  
  object ChatAsyncReporter extends Reporter {
    override def getSyntax: Syntax = Syntax.reporterSyntax(
      right = List(Syntax.StringType),
      ret = Syntax.ReporterType
    )
    
    override def report(args: Array[Argument], context: Context): AnyRef = {
      val inputText = args(0).getString
      val agent = context.getAgent

      try {
        val gen = generation
        val provider = ensureProvider(agent)
        requireText(provider)

        val userMessage = ChatMessage.user(inputText)

        // Snapshot on the NetLogo thread; commit the pair atomically on success
        // from the completion thread so overlapping async calls can't interleave.
        val responseFuture = provider.chatWithFullResponse(snapshotHistory(agent) :+ userMessage).map { response =>
          recordUsage(agent, gen, response)
          rejectRefusal(response)
          rejectEmptyReply(response)
          val responseMessage = replyMessage(response)
          commitExchange(agent, gen, userMessage, responseMessage)
          responseMessage.content
        }

        // Return AnonymousReporter that wraps the Future
        createAwaitableReporter(responseFuture, awaitTimeoutFor(effectiveConfig(agent)))

      } catch {
        case e: TextUnsupportedException => throw e
        case e: Exception =>
          throw new ExtensionException(s"Failed to start async LLM chat: ${e.getMessage}")
      }
    }
  }
  
  /**
   * Batched chat: one request per agent, sent together, one wait.
   *
   *   llm:chat-all agentset [ -> prompt ]
   *   (llm:chat-all agentset [ -> prompt ] schema)
   *
   * Reports a list of [agent reply ok?] triples. The prompt reporter runs as
   * each agent, like `ask`, so it can read that agent's variables. Requests go
   * through the same provider path as llm:chat, so throttling, retry, profiles,
   * history and usage all apply per agent. One agent's failure is reported in
   * its own triple and never aborts the rest of the batch.
   */
  object ChatAllReporter extends Reporter {
    override def getSyntax: Syntax = Syntax.reporterSyntax(
      right = List(Syntax.AgentsetType, Syntax.ReporterType, Syntax.StringType | Syntax.RepeatableType),
      ret = Syntax.ListType,
      defaultOption = Some(2)
    )

    /** One agent's request; `result` and `abandoned` are only touched under historyLock. */
    private final class Slot(val agent: org.nlogo.agent.Agent, val prompt: String) {
      var future: Future[AnyRef] = Future.failed(new IllegalStateException("request not sent"))
      var result: Option[AnyRef] = None
      var abandoned = false
    }

    override def report(args: Array[Argument], context: Context): AnyRef = {
      val agents = args(0).getAgentSet
      val promptReporter = args(1).getReporter
      if (args.length > 3) {
        throw new ExtensionException("llm:chat-all takes at most one schema")
      }
      // Validate before building any prompt so a bad schema costs nothing.
      val format = if (args.length > 2) Some(parseObjectSchema(args(2).getString, "llm:chat-all")) else None

      val ext = context match {
        case ec: org.nlogo.nvm.ExtensionContext => ec
        case _ =>
          throw new ExtensionException("llm:chat-all requires a NetLogo workspace and is unavailable in this context")
      }
      val caller = ext.nvmContext

      // Build every prompt before sending anything: an error in the prompt
      // reporter is a bug in the model code, and should cost no requests.
      // The agentset is copied first, as `ask` does, so a prompt reporter that
      // kills agents cannot change the set while it is being walked.
      val slots = agents.agents.asScala.toList.collect {
        case a: org.nlogo.agent.Agent if a.id != -1 =>
          val agentContext = new org.nlogo.nvm.Context(
            caller.job, a, caller.agent, caller.ip, caller.activation, ext.workspace)
          promptReporter.report(agentContext, Array.empty[AnyRef]) match {
            case prompt: String => new Slot(a, prompt)
            case other =>
              throw new ExtensionException(
                s"llm:chat-all: the prompt reporter must report a string, but for " +
                  s"${Dump.logoObject(a)} it reported ${Dump.logoObject(other, true, false)}")
          }
      }

      // Time of the most recent reply from any provider in this batch.
      val lastReply = new java.util.concurrent.atomic.AtomicLong(System.nanoTime)

      // Send every request without waiting. The provider's throttle turns
      // "send all" into "queue all, at most N in flight". Agents killed by a
      // later agent's prompt reporter are dropped here rather than sent.
      val live = slots.filter(_.agent.id != -1)
      // A decision-only provider cannot serve any of the batch: raise before
      // sending. A provider that fails to initialise stays a per-agent failure.
      live.foreach(slot => Try(ensureProvider(slot.agent)).foreach(requireText))
      val gen = generation
      live.foreach { slot =>
        val userMessage = ChatMessage.user(slot.prompt)
        slot.future =
          try {
            val provider = ensureProvider(slot.agent)
            val messages = snapshotHistory(slot.agent) :+ userMessage
            val sent = format match {
              case Some(f) => provider.chatWithFormat(messages, f)
              case None    => provider.chatWithFullResponse(messages)
            }
            sent.onComplete(_ => lastReply.set(System.nanoTime))
            sent.map { response =>
              recordUsage(slot.agent, gen, response)
              rejectRefusal(response)
              rejectEmptyReply(response)
              val (value, assistant): (AnyRef, ChatMessage) = format match {
                case Some(_) =>
                  // Parse before committing, as llm:chat-with-schema does.
                  val content = response.firstContent.getOrElse("")
                  (JsonToNetLogo.parseObject(content), ChatMessage.assistant(content))
                case None =>
                  val m = replyMessage(response)
                  (m.content, m)
              }
              // A reply that lands after the wait gave up must not write
              // history for an agent the batch already reported as failed.
              historyLock.synchronized {
                if (slot.abandoned) {
                  throw new java.util.concurrent.TimeoutException("reply arrived after the wait gave up")
                }
                commitExchange(slot.agent, gen, userMessage, assistant)
                slot.result = Some(value)
              }
              value
            }
          } catch {
            case e: Exception => Future.failed(e)
          }
      }

      // Wait until no provider has replied for an agent's whole budget. A
      // batch that keeps making progress — the normal case behind a throttle,
      // where later agents queue for waves — keeps waiting, while a hung
      // provider costs one budget in total rather than one per agent.
      def settle(slot: Slot, budget: FiniteDuration): Try[AnyRef] = {
        while (!slot.future.isCompleted) {
          val remaining = lastReply.get + budget.toNanos - System.nanoTime
          if (remaining <= 0) {
            return historyLock.synchronized {
              slot.result match {
                case Some(v) => Success(v)
                case None =>
                  slot.abandoned = true
                  Failure(new java.util.concurrent.TimeoutException(
                    s"no reply within ${budget.toSeconds}s"))
              }
            }
          }
          try Await.ready(slot.future, remaining.nanos)
          catch { case _: java.util.concurrent.TimeoutException => () }
        }
        slot.future.value.get
      }

      val entries =
        try {
          live.map { slot =>
            settle(slot, awaitTimeoutFor(effectiveConfig(slot.agent))) match {
              case Success(v) => LogoList(slot.agent, v, java.lang.Boolean.TRUE)
              case Failure(e: ModelRefusedException) => LogoList(slot.agent, e.getMessage, java.lang.Boolean.FALSE)
              case Failure(e) => LogoList(slot.agent, s"LLM chat failed: ${e.getMessage}", java.lang.Boolean.FALSE)
            }
          }
        } finally {
          // If the wait is cut short (halt interrupts the job thread), replies
          // still in flight must not write history for calls that never
          // returned to the model.
          historyLock.synchronized {
            live.foreach(slot => if (slot.result.isEmpty) slot.abandoned = true)
          }
        }

      LogoList.fromVector(entries.toVector)
    }
  }

  object ChatWithTemplateReporter extends Reporter {
    override def getSyntax: Syntax = Syntax.reporterSyntax(
      right = List(Syntax.StringType, Syntax.ListType),
      ret = Syntax.StringType
    )

    override def report(args: Array[Argument], context: Context): AnyRef = {
      val templateFile = args(0).getString
      val variablesList = args(1).getList
      val agent = context.getAgent

      try {
        val gen = generation
        val provider = ensureProvider(agent)
        requireText(provider)

        // Extract model directory from workspace
        val modelDir = Option(context.workspace.getModelPath).flatMap { path =>
          Option(new java.io.File(path).getParent)
        }

        // Load and parse template
        val template = loadTemplate(templateFile, modelDir) match {
          case Success(t) => t
          case Failure(e) => throw new ExtensionException(s"Failed to load template '$templateFile': ${e.getMessage}")
        }
        
        // Parse variables from NetLogo list
        val variables = parseVariables(variablesList)
        
        // Substitute variables in template
        val processedTemplate = substituteVariables(template.template, variables)
        
        // Build a copy so temporary prompt assembly never mutates permanent history.
        val tempHistory = ArrayBuffer.from(snapshotHistory(agent))
        if (template.system.nonEmpty) {
          tempHistory.prepend(ChatMessage.system(template.system))
        }

        // Add user message with processed template
        val userMessage = ChatMessage.user(processedTemplate)
        tempHistory += userMessage

        // Send chat request
        val responseFuture = provider.chatWithFullResponse(tempHistory.toSeq)
        val response = Await.result(responseFuture, awaitTimeoutFor(effectiveConfig(agent)))
        recordUsage(agent, gen, response)
        rejectRefusal(response)
        rejectEmptyReply(response)
        val responseMessage = replyMessage(response)

        // Commit both template message and response to permanent history on success
        commitExchange(agent, gen, userMessage, responseMessage)

        responseMessage.content
        
      } catch {
        case e: Exception if !e.isInstanceOf[ExtensionException] =>
          throw new ExtensionException(s"Template chat failed: ${e.getMessage}")
      }
    }
  }
  
  object ChooseReporter extends Reporter {
    /** Final line of the choose prompt; names the shape the enum schema enforces. */
    val ChoiceInstruction: String =
      s"""Your choice as {"${EnumFormat.ChoiceKey}": "<option>"}:"""

    override def getSyntax: Syntax = Syntax.reporterSyntax(
      right = List(Syntax.StringType, Syntax.ListType),
      ret = Syntax.StringType
    )

    override def report(args: Array[Argument], context: Context): AnyRef = {
      val prompt = args(0).getString
      val choicesList = args(1).getList
      val agent = context.getAgent

      try {
        val gen = generation
        val provider = ensureProvider(agent)

        val choices = choicesList.map(_.toString).toList

        if (choices.isEmpty) {
          throw new ExtensionException("Choice list cannot be empty")
        }

        // A decision provider picks natively: ask it a choice question about
        // the prompt alone. Like llm:decide, this sends no history and writes
        // none; usage is still recorded.
        provider match {
          case d: DecisionProvider =>
            // Same option rules as llm:decide's choice spec, checked before sending.
            val question = parseDecisionSpec(
              LogoList("choice", LogoList.fromVector(choices.toVector)), "llm:choose")
            val (result, usage) = Await.result(
              d.decide(prompt, question),
              awaitTimeoutFor(effectiveConfig(agent)))
            recordUsage(agent, gen, usage)
            return result.answer match {
              case DecisionAnswer.Picked(option) if choices.contains(option) => option
              case DecisionAnswer.Picked(option) =>
                throw new ExtensionException(
                  s"""llm:choose: provider picked "$option", which is not one of the options: ${choices.mkString(", ")}""")
              case other => throw new ExtensionException(s"llm:choose: provider answered $other, not an option")
            }
          case _ => ()
        }

        // The wording must describe the same shape as the EnumFormat schema
        // sent with the request. A provider that validates the reply against
        // the schema server-side (Groq does) rejects the whole response with
        // HTTP 400 when the model follows a prompt asking for bare text.
        val systemPrompt = "You are a decision-making assistant. " +
          "When given options, choose EXACTLY one option from the list. " +
          s"""Reply with a JSON object of the form {"${EnumFormat.ChoiceKey}": "<option>"} """ +
          "where <option> is copied exactly as written. " +
          "No explanation, no extra keys, no extra text."

        val userPrompt = s"$prompt\n\nOptions:\n${choices.mkString("\n")}\n\n" +
          ChooseReporter.ChoiceInstruction

        // Build temp history with system prompt — don't mutate permanent history
        val tempHistory = ArrayBuffer.from(snapshotHistory(agent))
        tempHistory.prepend(ChatMessage.system(systemPrompt))
        tempHistory += ChatMessage.user(userPrompt)

        // Constrain the reply to the choice list where the provider supports it.
        // The prompt still spells out the options, so a provider that ignores
        // the constraint behaves exactly as it did before.
        val responseFuture = provider.chatWithFormat(tempHistory.toSeq, EnumFormat(choices))
        val response = Await.result(responseFuture, awaitTimeoutFor(effectiveConfig(agent)))
        recordUsage(agent, gen, response)
        rejectRefusal(response)
        rejectEmptyReply(response)

        // Extract text: prefer content, fall back to thinking field
        val text = response.firstContent.filter(_.nonEmpty)
          .orElse(response.thinking)
          .getOrElse("")
          .trim

        // A constrained reply arrives as {"choice": "..."} while an unconstrained
        // one is the bare option text. Accept both: which shape comes back
        // depends on provider support, and the modeler asked for neither.
        val candidate = extractEnumChoice(text).getOrElse(text)

        // Exact match only (case-insensitive)
        val chosenOption = choices.find(_.equalsIgnoreCase(candidate))
          .getOrElse {
            throw new ExtensionException(
              s"llm:choose: response '$candidate' did not match any choice. " +
              s"Choices: ${choices.mkString(", ")}"
            )
          }

        // Only on success: store clean messages in permanent history
        commitExchange(agent, gen, ChatMessage.user(prompt), ChatMessage.assistant(chosenOption))

        chosenOption

      } catch {
        case e: Exception if !e.isInstanceOf[ExtensionException] =>
          throw new ExtensionException(s"LLM choice failed: ${e.getMessage}")
      }
    }
  }
  
  /**
   * A typed decision about a prompt, with probabilities.
   *
   *   llm:decide prompt ["choice" [options]]
   *   llm:decide prompt ["score" [levels]]
   *   llm:decide prompt ["yes-no"]
   *
   * Reports [answer probability distribution]. Prompt only: no history is
   * sent and none is written. Usage is recorded for the calling agent, and
   * the request goes through the provider's throttle and retry path. Needs a
   * provider with a decision API (DecisionProvider).
   */
  object DecideReporter extends Reporter {
    override def getSyntax: Syntax = Syntax.reporterSyntax(
      right = List(Syntax.StringType, Syntax.ListType),
      ret = Syntax.ListType
    )

    override def report(args: Array[Argument], context: Context): AnyRef = {
      val prompt = args(0).getString
      // Validate before touching the provider so a bad spec costs nothing.
      val question = parseDecisionSpec(args(1).getList, "llm:decide")
      val agent = context.getAgent
      val gen = generation
      val provider = decisionProviderFor(agent)

      try {
        val (result, usage) = Await.result(provider.decide(prompt, question), awaitTimeoutFor(effectiveConfig(agent)))
        recordUsage(agent, gen, usage)
        decisionToLogo(result)
      } catch {
        case e: Exception if !e.isInstanceOf[ExtensionException] =>
          throw new ExtensionException(s"llm:decide failed: ${e.getMessage}")
      }
    }
  }

  /**
   * Batched decisions: one request per agent, sent together, one wait.
   *
   *   llm:decide-all agentset [ -> prompt ] spec
   *
   * Mirrors llm:chat-all: the prompt reporter runs as each agent, requests go
   * out concurrently through the throttle, and one agent's failure is reported
   * in its own [agent result ok?] triple. `result` is the [answer probability
   * distribution] list, or the error text when ok? is false. Nothing is
   * written to history; usage is recorded per agent.
   */
  object DecideAllReporter extends Reporter {
    override def getSyntax: Syntax = Syntax.reporterSyntax(
      right = List(Syntax.AgentsetType, Syntax.ReporterType, Syntax.ListType),
      ret = Syntax.ListType
    )

    private final class Slot(val agent: org.nlogo.agent.Agent, val prompt: String) {
      var future: Future[AnyRef] = Future.failed(new IllegalStateException("request not sent"))
    }

    override def report(args: Array[Argument], context: Context): AnyRef = {
      val agents = args(0).getAgentSet
      val promptReporter = args(1).getReporter
      // Validate before building any prompt so a bad spec costs nothing.
      val question = parseDecisionSpec(args(2).getList, "llm:decide-all")

      val ext = context match {
        case ec: org.nlogo.nvm.ExtensionContext => ec
        case _ =>
          throw new ExtensionException("llm:decide-all requires a NetLogo workspace and is unavailable in this context")
      }
      val caller = ext.nvmContext

      // Build every prompt before sending anything, on a copy of the agentset,
      // exactly as llm:chat-all does.
      val slots = agents.agents.asScala.toList.collect {
        case a: org.nlogo.agent.Agent if a.id != -1 =>
          val agentContext = new org.nlogo.nvm.Context(
            caller.job, a, caller.agent, caller.ip, caller.activation, ext.workspace)
          promptReporter.report(agentContext, Array.empty[AnyRef]) match {
            case prompt: String => new Slot(a, prompt)
            case other =>
              throw new ExtensionException(
                s"llm:decide-all: the prompt reporter must report a string, but for " +
                  s"${Dump.logoObject(a)} it reported ${Dump.logoObject(other, true, false)}")
          }
      }

      val lastReply = new java.util.concurrent.atomic.AtomicLong(System.nanoTime)

      val live = slots.filter(_.agent.id != -1)
      // A provider without a decision API cannot serve any of the batch: raise
      // before sending. A provider that fails to initialise stays per agent.
      val providers = live.map { slot =>
        Try(decisionProviderFor(slot.agent)) match {
          case Failure(e: NoDecisionApiException) => throw e
          case other => other
        }
      }
      val gen = generation
      live.zip(providers).foreach { case (slot, provider) =>
        slot.future =
          try {
            val sent = provider.get.decide(slot.prompt, question)
            sent.onComplete(_ => lastReply.set(System.nanoTime))
            sent.map { case (result, usage) =>
              recordUsage(slot.agent, gen, usage)
              decisionToLogo(result)
            }
          } catch {
            case e: Exception => Future.failed(e)
          }
      }

      // Same wait as llm:chat-all: give up only when no reply has arrived for
      // an agent's whole budget.
      def settle(slot: Slot, budget: FiniteDuration): Try[AnyRef] = {
        while (!slot.future.isCompleted) {
          val remaining = lastReply.get + budget.toNanos - System.nanoTime
          if (remaining <= 0) {
            return Failure(new java.util.concurrent.TimeoutException(s"no reply within ${budget.toSeconds}s"))
          }
          try Await.ready(slot.future, remaining.nanos)
          catch { case _: java.util.concurrent.TimeoutException => () }
        }
        slot.future.value.get
      }

      val entries = live.map { slot =>
        settle(slot, awaitTimeoutFor(effectiveConfig(slot.agent))) match {
          case Success(v) => LogoList(slot.agent, v, java.lang.Boolean.TRUE)
          case Failure(e) => LogoList(slot.agent, s"llm:decide failed: ${e.getMessage}", java.lang.Boolean.FALSE)
        }
      }

      LogoList.fromVector(entries.toVector)
    }
  }

  /**
   * Read the selected option out of an enum-constrained reply.
   *
   * A provider enforcing the constraint returns `{"choice": "north"}`; one that
   * ignores it returns `north`. Returns None for anything that is not the
   * constrained shape, so the caller can fall back to the raw text.
   */
  private def extractEnumChoice(text: String): Option[String] =
    scala.util.Try {
      ujson.read(text)(EnumFormat.ChoiceKey).str
    }.toOption

  /**
   * Parse and check a schema whose replies must convert to [key value] pairs.
   *
   * The reporters using it promise a list, and only a JSON object converts to
   * the [key value] pairs llm:get reads. A top-level scalar or array schema
   * would report a bare string or a flat list instead, breaking that promise,
   * so it is rejected here rather than at the provider.
   */
  private def parseObjectSchema(schemaText: String, primName: String): JsonSchemaFormat = {
    val format =
      try ResponseFormat.parseSchema(schemaText)
      catch {
        case e: IllegalArgumentException =>
          throw new ExtensionException(s"$primName: ${e.getMessage}")
      }

    val topLevelType = format.schema.value.get("type").collect { case ujson.Str(s) => s }
    if (!topLevelType.contains("object")) {
      throw new ExtensionException(
        s"$primName: schema must have type 'object' at the top level, but got " +
          s"'${topLevelType.getOrElse("none")}'. $primName reports a list of " +
          "[key value] pairs, so the reply has to be a JSON object. Wrap it, e.g. " +
          """{"type":"object","properties":{"value":{"type":"string"}}}"""
      )
    }
    format
  }

  // Structured Output Primitives

  /**
   * Chat with the reply constrained to a JSON Schema, reported as nested lists.
   *
   *   llm:chat-with-schema prompt schema-string
   *
   * NetLogo has no map type, so the parsed JSON object arrives as a list of
   * `[key value]` pairs — use `llm:get` to read fields out of it.
   *
   * The schema is validated before any request is sent: an unusable schema
   * otherwise surfaces as an opaque provider 400 after a network round-trip,
   * which a modeler cannot act on.
   */
  object ChatWithSchemaReporter extends Reporter {
    override def getSyntax: Syntax = Syntax.reporterSyntax(
      right = List(Syntax.StringType, Syntax.StringType),
      ret = Syntax.ListType
    )

    override def report(args: Array[Argument], context: Context): AnyRef = {
      val inputText = args(0).getString
      val schemaText = args(1).getString
      val agent = context.getAgent

      // Validate before touching the provider so a bad schema costs nothing.
      val format = parseObjectSchema(schemaText, "llm:chat-with-schema")

      try {
        val gen = generation
        val provider = ensureProvider(agent)
        requireText(provider)

        val userMessage = ChatMessage.user(inputText)

        val responseFuture = provider.chatWithFormat(snapshotHistory(agent) :+ userMessage, format)
        val response = Await.result(responseFuture, awaitTimeoutFor(effectiveConfig(agent)))
        recordUsage(agent, gen, response)
        rejectRefusal(response)
        rejectEmptyReply(response)

        val content = response.firstContent.getOrElse("")

        // Parse before committing: a reply that is not JSON means the call did
        // not deliver what was asked for, so history must not record it as a
        // successful exchange.
        val parsed =
          try JsonToNetLogo.parseObject(content)
          catch {
            case e: IllegalArgumentException =>
              throw new ExtensionException(s"llm:chat-with-schema: ${e.getMessage}")
          }

        commitExchange(agent, gen, userMessage, ChatMessage.assistant(content))

        parsed

      } catch {
        case e: Exception if !e.isInstanceOf[ExtensionException] =>
          throw new ExtensionException(s"llm:chat-with-schema failed: ${e.getMessage}")
      }
    }
  }

  /**
   * Chat with the reply constrained to valid JSON, reported as a raw string.
   *
   *   llm:chat-json prompt
   *
   * Reports the JSON text rather than parsed lists, for modelers who want to
   * hand it to another tool or inspect it directly. Use `llm:chat-with-schema`
   * when the shape matters and you want NetLogo values back.
   */
  object ChatJsonReporter extends Reporter {
    override def getSyntax: Syntax = Syntax.reporterSyntax(
      right = List(Syntax.StringType),
      ret = Syntax.StringType
    )

    override def report(args: Array[Argument], context: Context): AnyRef = {
      val inputText = args(0).getString
      val agent = context.getAgent

      try {
        val gen = generation
        val provider = ensureProvider(agent)
        requireText(provider)

        // Anthropic has no schemaless JSON mode and Gemini's mime type alone is
        // only a hint, so the instruction is also stated in the prompt. Providers
        // that do enforce JSON natively are unaffected by the extra sentence.
        val jsonInstruction = ChatMessage.system(
          "Respond with valid JSON only. No prose, no markdown code fences."
        )
        val userMessage = ChatMessage.user(inputText)

        val tempHistory = ArrayBuffer.from(snapshotHistory(agent))
        tempHistory.prepend(jsonInstruction)
        tempHistory += userMessage

        val responseFuture = provider.chatWithFormat(tempHistory.toSeq, JsonObjectFormat)
        val response = Await.result(responseFuture, awaitTimeoutFor(effectiveConfig(agent)))
        recordUsage(agent, gen, response)
        rejectRefusal(response)
        rejectEmptyReply(response)

        val content = response.firstContent.getOrElse("")

        // Not every provider can enforce schemaless JSON natively — Anthropic
        // has no such mode at all — so the reply is verified here rather than
        // trusted. Checking before the commit keeps a failed call out of
        // history, and stops prose being reported from a primitive whose whole
        // contract is that the result parses as JSON. Any JSON value is
        // accepted: an array or scalar is still valid JSON.
        try ujson.read(content)
        catch {
          case e: Exception =>
            throw new ExtensionException(
              s"llm:chat-json: Response was not valid JSON: ${JsonToNetLogo.parseErrorMessage(e)}. Response text: $content"
            )
        }

        // Store the clean exchange only — the JSON instruction is prompt
        // scaffolding, not part of the conversation.
        commitExchange(agent, gen, userMessage, ChatMessage.assistant(content))

        content

      } catch {
        case e: Exception if !e.isInstanceOf[ExtensionException] =>
          throw new ExtensionException(s"llm:chat-json failed: ${e.getMessage}")
      }
    }
  }

  /**
   * Look up a key in a list of `[key value]` pairs.
   *
   *   llm:get parsed-result "key"
   *
   * Throws on a missing key rather than reporting a sentinel: a silent "" would
   * be indistinguishable from a JSON null and would let a typo propagate as data
   * through the rest of a run.
   */
  object GetReporter extends Reporter {
    override def getSyntax: Syntax = Syntax.reporterSyntax(
      right = List(Syntax.ListType, Syntax.StringType),
      ret = Syntax.WildcardType
    )

    override def report(args: Array[Argument], context: Context): AnyRef = {
      val list = args(0).getList
      val key = args(1).getString

      JsonToNetLogo.lookup(list, key).getOrElse {
        val available = list.toVector.collect {
          case pair: LogoList if pair.size == 2 => pair(0).toString
        }
        val detail =
          if (list.size == 0) "The list is empty."
          else if (available.isEmpty) "Available keys: (none - the list is not a list of [key value] pairs)"
          else s"Available keys: ${available.mkString(", ")}"
        throw new ExtensionException(s"""llm:get: key "$key" not found. $detail""")
      }
    }
  }

  // Thinking/Reasoning Primitives

  object ChatWithThinkingReporter extends Reporter {
    override def getSyntax: Syntax = Syntax.reporterSyntax(
      right = List(Syntax.StringType),
      ret = Syntax.ListType
    )

    override def report(args: Array[Argument], context: Context): AnyRef = {
      val inputText = args(0).getString
      val agent = context.getAgent

      try {
        val gen = generation
        val provider = ensureProvider(agent)
        requireText(provider)

        val userMessage = ChatMessage.user(inputText)

        // Send with user message included, but don't mutate history yet
        val responseFuture = provider.chatWithFullResponse(snapshotHistory(agent) :+ userMessage)
        val response = Await.result(responseFuture, awaitTimeoutFor(effectiveConfig(agent)))
        recordUsage(agent, gen, response)
        rejectRefusal(response)
        rejectEmptyReply(response)

        val answerText = response.firstContent.getOrElse("")
        val thinkingText = response.thinking.getOrElse("")

        // Only commit both messages after success (clean answer only, not thinking text)
        commitExchange(agent, gen, userMessage, ChatMessage.assistant(answerText))

        // Return [answer thinking] list — always 2 elements
        LogoList(answerText, thinkingText)

      } catch {
        case e: ModelRefusedException => throw e
        case e: TextUnsupportedException => throw e
        case e: Exception =>
          throw new ExtensionException(s"LLM chat-with-thinking failed: ${e.getMessage}")
      }
    }
  }

  object SetThinkingCommand extends Command {
    override def getSyntax: Syntax = Syntax.commandSyntax(right = List(Syntax.BooleanType))

    override def perform(args: Array[Argument], context: Context): Unit = {
      val enabled = args(0).getBooleanValue
      configStore.set(ConfigStore.ENABLE_THINKING, enabled.toString)
      // Force re-initialization so provider picks up new config
      currentProvider = None
    }
  }

  object SetReasoningEffortCommand extends Command {
    override def getSyntax: Syntax = Syntax.commandSyntax(right = List(Syntax.StringType))

    override def perform(args: Array[Argument], context: Context): Unit = {
      val effort = args(0).getString.toLowerCase.trim

      // Validate against the ACTIVE provider, not a global set. Providers
      // disagree — Groq rejects "xhigh" with a 400 and accepts "default", the
      // inverse of OpenAI — so one shared list either lets a request through to
      // a hard failure or blocks a value the provider would have taken.
      val providerName = configStore.get(ConfigStore.PROVIDER).getOrElse("")
      val allowed = ProviderRegistry.get(providerName)
        .map(_.reasoningEffortValues)
        .getOrElse(ProviderDescriptor.DefaultReasoningEffortValues)

      if (!allowed.contains(effort)) {
        val forProvider = if (providerName.nonEmpty) s" for provider '$providerName'" else ""
        throw new ExtensionException(
          s"Invalid reasoning effort: '$effort'$forProvider. " +
            s"Must be one of: ${allowed.toSeq.sorted.mkString(", ")}"
        )
      }
      configStore.set(ConfigStore.REASONING_EFFORT, effort)
      // Force re-initialization so provider picks up new config
      currentProvider = None
    }
  }

  object SetThinkingBudgetCommand extends Command {
    override def getSyntax: Syntax = Syntax.commandSyntax(right = List(Syntax.NumberType))

    override def perform(args: Array[Argument], context: Context): Unit = {
      val budget = args(0).getIntValue
      if (budget < 1024) {
        throw new ExtensionException(
          s"Thinking budget must be at least 1024 tokens, got: $budget"
        )
      }
      configStore.set(ConfigStore.THINKING_BUDGET_TOKENS, budget.toString)
      // Force re-initialization so provider picks up new config
      currentProvider = None
    }
  }

  // Code Validation Primitive

  /**
   * Reports "" if the given string compiles as NetLogo turtle commands,
   * otherwise a message describing why it is unacceptable.
   *
   *   llm:compile-error code
   *   (llm:compile-error code ["die" "clear-all"])
   *
   * The optional second argument is a list of primitive names that must not
   * appear in the code. It is caller-supplied policy, not a built-in list:
   * what counts as dangerous depends on the model.
   *
   * This calls NetLogo's own compiler — the same one `run` uses — so a
   * non-empty result guarantees `run` would have failed. There is no separate
   * parser to drift out of sync with the language or with the model's symbol
   * table: turtle-own variables, globals, and breeds defined by the running
   * model are all in scope.
   *
   * Compiles in Turtle context because generated agent rules are executed
   * inside `ask turtles`. Observer context would reject `fd`/`rt` and reject
   * valid rules.
   *
   * Scope: this proves the code COMPILES, not that it cannot throw at runtime.
   * Bounds errors such as `item 3` on a 3-element list still surface only when
   * the code runs, so keep `carefully` around execution.
   */
  object CompileErrorReporter extends Reporter {
    override def getSyntax: Syntax = Syntax.reporterSyntax(
      right = List(Syntax.StringType, Syntax.ListType | Syntax.RepeatableType),
      ret = Syntax.StringType,
      defaultOption = Some(1)
    )

    override def report(args: Array[Argument], context: Context): AnyRef = {
      val code = args(0).getString

      // Empty or whitespace-only code is valid NetLogo — a no-op rule.
      if (code.trim.isEmpty) return ""

      val workspace = context match {
        case ec: org.nlogo.nvm.ExtensionContext => ec.workspace
        case _ =>
          throw new ExtensionException(
            "llm:compile-error requires a NetLogo workspace and is unavailable in this context"
          )
      }

      // Syntax first. Code that does not compile cannot run, so a banned-primitive
      // report would be noise — and tokenizing malformed code is unreliable anyway.
      try {
        workspace.compileCommands(code, org.nlogo.core.AgentKind.Turtle)
      } catch {
        case e: org.nlogo.core.CompilerException =>
          return s"${e.getMessage} (offset ${e.start})"
        case e: Exception =>
          // Never silently swallow an unexpected failure as "valid".
          throw new ExtensionException(s"llm:compile-error failed: ${e.getMessage}")
      }

      if (args.length < 2) return ""

      val rawList = args(1).getList.toVector

      // Reject a malformed list rather than silently ignoring it. Skipping
      // non-strings would report "" — a false all-clear — for a caller who
      // passed the wrong thing.
      val nonStrings = rawList.filterNot(_.isInstanceOf[String])
      if (nonStrings.nonEmpty) {
        throw new ExtensionException(
          "llm:compile-error expects a list of primitive names as strings, but got: " +
            nonStrings.map(v => Dump.logoObject(v)).mkString(", ")
        )
      }

      val banned = rawList.map(_.asInstanceOf[String].trim.toLowerCase).filter(_.nonEmpty)

      if (banned.isEmpty) return ""

      // Exact token match using NetLogo's own tokenizer. Substring matching would
      // flag `die` inside `diehard`, inside a comment, or inside a string literal —
      // false positives that would reject valid code.
      val found =
        try {
          org.nlogo.lex.Tokenizer.tokenizeString(code, "")
            .map(_.text.toLowerCase)
            .filter(banned.contains)
            .toVector
            .distinct
        } catch {
          case e: Exception =>
            throw new ExtensionException(s"llm:compile-error failed while scanning: ${e.getMessage}")
        }

      if (found.isEmpty) ""
      else s"Disallowed primitive(s) used: ${found.mkString(", ")}"
    }
  }

  // History Management Primitives

  object HistoryReporter extends Reporter {
    override def getSyntax: Syntax = Syntax.reporterSyntax(ret = Syntax.ListType)
    
    override def report(args: Array[Argument], context: Context): LogoList = {
      val agent = context.getAgent
      val history = snapshotHistory(agent)

      LogoList.fromIterator(
        history.map { message =>
          LogoList(message.role, message.content)
        }.iterator
      )
    }
  }
  
  object SetHistoryCommand extends Command {
    override def getSyntax: Syntax = Syntax.commandSyntax(right = List(Syntax.ListType))
    
    override def perform(args: Array[Argument], context: Context): Unit = {
      val agent = context.getAgent
      val historyList = args(0).getList
      
      try {
        val messages = historyList.map {
          case l: LogoList if l.size == 2 =>
            ChatMessage(l(0).toString, l(1).toString)
          case _ =>
            throw new ExtensionException("History items must be lists of [role content] pairs")
        }.to(ArrayBuffer)

        historyLock.synchronized { messageHistory.put(agent, messages) }

      } catch {
        case e: Exception if !e.isInstanceOf[ExtensionException] =>
          throw new ExtensionException(s"Invalid history format: ${e.getMessage}")
      }
    }
  }
  
  object ClearHistoryCommand extends Command {
    override def getSyntax: Syntax = Syntax.commandSyntax()
    
    override def perform(args: Array[Argument], context: Context): Unit = {
      val agent = context.getAgent
      historyLock.synchronized { messageHistory.remove(agent) }
    }
  }
  
  // Provider Information Reporters
  
  object ProvidersReporter extends Reporter {
    override def getSyntax: Syntax = Syntax.reporterSyntax(ret = Syntax.ListType)
    
    override def report(args: Array[Argument], context: Context): AnyRef = {
      try {
        // Return only READY providers
        val readyProviders = ProviderFactory.getSupportedProviders
          .filter(isProviderReady)
          .toList
          .sorted
        LogoList.fromJava(readyProviders.asJava)
      } catch {
        case e: Exception =>
          throw new ExtensionException(s"Failed to get ready providers: ${e.getMessage}")
      }
    }
  }

  object ProvidersAllReporter extends Reporter {
    override def getSyntax: Syntax = Syntax.reporterSyntax(ret = Syntax.ListType)

    override def report(args: Array[Argument], context: Context): AnyRef = {
      try {
        val allProviders = ProviderFactory.getSupportedProviders.toList.sorted
        LogoList.fromJava(allProviders.asJava)
      } catch {
        case e: Exception =>
          throw new ExtensionException(s"Failed to get all providers: ${e.getMessage}")
      }
    }
  }

  object ProviderStatusReporter extends Reporter {
    override def getSyntax: Syntax = Syntax.reporterSyntax(ret = Syntax.ListType)

    override def report(args: Array[Argument], context: Context): AnyRef = {
      try {
        val statusList = ProviderFactory.getSupportedProviders.toList.sorted.map { provider =>
          val ready = isProviderReady(provider)
          
          val details = provider.toLowerCase.trim match {
            case "ollama" =>
              val baseUrl = ConfigStore.resolveBaseUrl(configStore, ConfigStore.OLLAMA_BASE_URL, ConfigStore.DEFAULT_OLLAMA_BASE_URL)
              LogoList(
                provider,
                LogoList("ready", Boolean.box(ready)),
                LogoList("reachable", Boolean.box(ready)),
                LogoList("base-url", baseUrl)
              )
            case _ =>
              val hasKey = hasApiKey(provider)
              LogoList(
                provider,
                LogoList("ready", Boolean.box(ready)),
                LogoList("has-key", Boolean.box(hasKey))
              )
          }
          details
        }
        
        LogoList.fromJava(statusList.asJava)
      } catch {
        case e: Exception =>
          throw new ExtensionException(s"Failed to get provider status: ${e.getMessage}")
      }
    }
  }
  
  object ProviderHelpReporter extends Reporter {
    override def getSyntax: Syntax = Syntax.reporterSyntax(
      right = List(Syntax.StringType),
      ret = Syntax.StringType
    )

    override def report(args: Array[Argument], context: Context): AnyRef = {
      val providerName = args(0).getString.toLowerCase.trim
      ProviderRegistry.helpText(providerName)
    }
  }
  
  /**
   * llm:load-profile name file — load a config file under a name so agents
   * can be bound to it with llm:use-profile. Validated exactly like
   * llm:load-config; a rejected load leaves any existing profile of that
   * name untouched, because the store is only updated after every check.
   */
  object LoadProfileCommand extends Command {
    override def getSyntax: Syntax = Syntax.commandSyntax(right = List(Syntax.StringType, Syntax.StringType))

    override def perform(args: Array[Argument], context: Context): Unit = {
      val name = args(0).getString
      val filename = args(1).getString

      val modelDir = Option(context.workspace.getModelPath).flatMap { path =>
        Option(new java.io.File(path).getParent)
      }

      val config = ConfigLoader.loadFromFile(filename, modelDir) match {
        case Success(c) => c
        case Failure(e) =>
          throw new ExtensionException(s"llm:load-profile: Failed to load configuration from '$filename': ${e.getMessage}")
      }

      val providerName = config.getOrElse(ConfigStore.PROVIDER, ConfigStore.DEFAULT_PROVIDER)
      val desc = ProviderRegistry.get(providerName.toLowerCase.trim).getOrElse {
        throw new ExtensionException(
          s"llm:load-profile: Unknown provider '$providerName' in config. Supported: ${ProviderRegistry.allNames.toList.sorted.mkString(", ")}"
        )
      }

      // Mirror llm:load-config: the file is the whole configuration, with no
      // OpenAI-flavoured defaults layered underneath. A profile that names no
      // model gets its own provider's default, not the OpenAI default.
      val candidate = new ConfigStore()
      candidate.loadFromMap(config)
      checkReadiness(desc, providerName, candidate)

      try profiles.load(name, config)
      catch {
        case e: IllegalArgumentException =>
          throw new ExtensionException(s"llm:load-profile: ${e.getMessage}")
      }
    }
  }

  /**
   * llm:use-profile name — route the calling agent's calls through a loaded
   * profile. The reserved default name unbinds it.
   */
  object UseProfileCommand extends Command {
    override def getSyntax: Syntax = Syntax.commandSyntax(right = List(Syntax.StringType))

    override def perform(args: Array[Argument], context: Context): Unit = {
      val name = args(0).getString.trim.toLowerCase
      val agent = context.getAgent
      if (name == ProfileStore.DefaultName) {
        profileLock.synchronized { agentProfile.remove(agent) }
      } else if (profiles.contains(name)) {
        profileLock.synchronized { agentProfile.update(agent, name) }
      } else {
        throw new ExtensionException(
          s"llm:use-profile: no profile named '${args(0).getString.trim}'. Loaded profiles: ${profiles.describeLoaded}"
        )
      }
    }
  }

  /** llm:profile — the calling agent's profile name, or the default name. */
  object ProfileReporter extends Reporter {
    override def getSyntax: Syntax = Syntax.reporterSyntax(ret = Syntax.StringType)
    override def report(args: Array[Argument], context: Context): AnyRef = profileNameFor(context.getAgent)
  }

  /** llm:profiles — the loaded profile names, sorted. */
  object ProfilesReporter extends Reporter {
    override def getSyntax: Syntax = Syntax.reporterSyntax(ret = Syntax.ListType)
    override def report(args: Array[Argument], context: Context): AnyRef =
      LogoList.fromIterator(profiles.names.iterator.map(n => n: AnyRef))
  }

  /**
   * Token accounting as a `[[key value] ...]` list, the same shape structured
   * output uses so `llm:get` reads it. Counters are numbers; `cost` is a
   * number only when a provider reported one and `""` otherwise, matching how
   * JSON null is reported, so an unknown cost is never mistaken for free.
   */
  private def usageToLogo(totals: UsageTotals): LogoList = {
    val u = totals.usage
    def num(n: Long): AnyRef = Double.box(n.toDouble)
    LogoList(
      LogoList("input-tokens", num(u.inputTokens)),
      LogoList("output-tokens", num(u.outputTokens)),
      LogoList("total-tokens", num(u.totalTokens)),
      LogoList("reasoning-tokens", num(u.reasoningTokens.getOrElse(0L))),
      LogoList("cache-read-tokens", num(u.cacheReadTokens.getOrElse(0L))),
      LogoList("cache-write-tokens", num(u.cacheWriteTokens.getOrElse(0L))),
      LogoList("cost", u.cost.map(c => Double.box(c): AnyRef).getOrElse("")),
      LogoList("latency-ms", num(u.latencyMs.getOrElse(0L))),
      LogoList("calls", num(totals.calls))
    )
  }

  /** llm:usage — token accounting for the calling agent since clear-all. */
  object UsageReporter extends Reporter {
    override def getSyntax: Syntax = Syntax.reporterSyntax(ret = Syntax.ListType)

    override def report(args: Array[Argument], context: Context): AnyRef =
      usageToLogo(usageFor(context.getAgent))
  }

  /** llm:usage-total — token accounting for every agent in the run since clear-all. */
  object UsageTotalReporter extends Reporter {
    override def getSyntax: Syntax = Syntax.reporterSyntax(ret = Syntax.ListType)

    override def report(args: Array[Argument], context: Context): AnyRef =
      usageToLogo(usageForRun)
  }

  object ActiveReporter extends Reporter {
    override def getSyntax: Syntax = Syntax.reporterSyntax(ret = Syntax.ListType)
    
    override def report(args: Array[Argument], context: Context): AnyRef = {
      try {
        val config = effectiveConfig(context.getAgent)
        val provider = config.getOrElse(ConfigStore.PROVIDER, ConfigStore.DEFAULT_PROVIDER)
        val model = config.getOrElse(ConfigStore.MODEL, ModelRegistry.defaultModel(provider))
        LogoList(provider, model)
      } catch {
        case e: Exception =>
          throw new ExtensionException(s"Failed to get active configuration: ${e.getMessage}")
      }
    }
  }
  
  object ConfigReporter extends Reporter {
    override def getSyntax: Syntax = Syntax.reporterSyntax(ret = Syntax.StringType)
    
    override def report(args: Array[Argument], context: Context): AnyRef = {
      try {
        effectiveConfig(context.getAgent).summary
      } catch {
        case e: Exception =>
          throw new ExtensionException(s"Failed to get config summary: ${e.getMessage}")
      }
    }
  }
  
  object ListModelsReporter extends Reporter {
    override def getSyntax: Syntax = Syntax.reporterSyntax(ret = Syntax.StringType)

    override def report(args: Array[Argument], context: Context): AnyRef = {
      try {
        // Load override from model directory if available
        Option(context.workspace.getModelPath).flatMap { path =>
          Option(new java.io.File(path).getParent)
        } match {
          case Some(modelDir) => ModelRegistry.loadOverride(modelDir)
          case None => ModelRegistry.clearOverride()
        }

        val providerName = configStore.getOrElse(ConfigStore.PROVIDER, ConfigStore.DEFAULT_PROVIDER)
        val model = configStore.getOrElse(ConfigStore.MODEL, ModelRegistry.defaultModel(providerName))

        ModelRegistry.formatModelList(providerName, model)
      } catch {
        case e: Exception =>
          throw new ExtensionException(s"Failed to list models: ${e.getMessage}")
      }
    }
  }
}

/** Running totals: the summed Usage plus how many calls contributed to it. */
private[llm] case class UsageTotals(usage: Usage, calls: Long) {
  def plus(other: UsageTotals): UsageTotals =
    UsageTotals(usage.plus(other.usage), calls + other.calls)
}

private[llm] object UsageTotals {
  val empty: UsageTotals = UsageTotals(Usage.empty, 0L)
}
