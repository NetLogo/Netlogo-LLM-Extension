package org.nlogo.extensions.llm

import org.nlogo.api._
import org.nlogo.core.{LogoList, Syntax}
import org.nlogo.extensions.llm.config.{ConfigLoader, ConfigStore, ProfileStore}
import org.nlogo.extensions.llm.providers.{LLMProvider, ProviderDescriptor, ProviderFactory, ProviderRegistry, ProviderRegistrations, ModelRegistry, OllamaProvider, ReadinessCheck, RetryPolicy}
import org.nlogo.extensions.llm.models.{ChatMessage, ChatResponse, EnumFormat, JsonObjectFormat, ResponseFormat, Usage}
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

  // Execution context for async operations
  implicit private val ec: ExecutionContext = ExecutionContext.global
  
  /**
   * Create an AwaitableReporter that wraps a Future to provide truly async behavior
   * The Future starts immediately but execution defers until runresult is called
   */
  private def createAwaitableReporter(future: Future[String]): AnonymousReporter = {
    new AnonymousReporter {
      override def syntax: Syntax = Syntax.reporterSyntax(right = List(), ret = Syntax.StringType)
      
      override def report(context: Context, args: Array[AnyRef]): AnyRef = {
        try {
          Await.result(future, getAwaitTimeout)
        } catch {
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
    manager.addPrimitive("chat-with-template", ChatWithTemplateReporter)
    manager.addPrimitive("chat-with-thinking", ChatWithThinkingReporter)
    manager.addPrimitive("choose", ChooseReporter)

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
   * the current buffer, not a detached stale one.
   */
  private def commitExchange(agent: Agent, user: ChatMessage, assistant: ChatMessage): Unit =
    historyLock.synchronized {
      val h = getAgentHistory(agent)
      h += user
      h += assistant
    }

  /**
   * Credit a provider reply to the calling agent and to the run.
   *
   * Called as soon as a response arrives, before the caller decides whether
   * the reply is acceptable: tokens were billed either way, so a reply the
   * extension then rejects (schema parse failure, unmatched choice) still
   * counts. A call that never produced a response records nothing.
   */
  private def recordUsage(agent: Agent, response: ChatResponse): Unit = {
    val delta = UsageTotals(response.usage.getOrElse(Usage.empty), 1L)
    usageLock.synchronized {
      agentUsage.update(agent, agentUsage.getOrElse(agent, UsageTotals.empty).plus(delta))
      runUsage = runUsage.plus(delta)
    }
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
  private def getTimeoutSeconds: Int =
    configStore.get(ConfigStore.TIMEOUT_SECONDS).map { s =>
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
  private def getAwaitTimeout: FiniteDuration = {
    val retryBudget = configStore.get(RetryPolicy.MAX_ELAPSED_SECONDS)
      .flatMap(s => scala.util.Try(s.trim.toDouble).toOption)
      .filter(d => d >= 0.0 && d.isFinite)
      .map(d => (d * 1000.0).toLong.millis)
      .getOrElse(RetryPolicy.DefaultMaxElapsed)
    getTimeoutSeconds.seconds + retryBudget
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
          val baseUrl = config.get(desc.baseUrlConfigKey)
            .orElse(config.get(ConfigStore.BASE_URL))
            .getOrElse(desc.defaultBaseUrl)
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

  private def isOllamaReachable(config: ConfigStore): Boolean = {
    try {
      val provider = new OllamaProvider()
      val baseUrl = config.get(ConfigStore.OLLAMA_BASE_URL)
        .orElse(config.get(ConfigStore.BASE_URL))
        .getOrElse(ConfigStore.DEFAULT_OLLAMA_BASE_URL)
      provider.setConfig(ConfigStore.BASE_URL, baseUrl)

      val checkFuture = provider.checkServerConnection()
      Await.result(checkFuture, 1.second)
    } catch {
      case ex: Exception =>
        System.err.println(s"WARNING: Ollama reachability check failed: ${ex.getMessage}")
        false
    }
  }
  
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
      
      // Apply provider defaults
      val defaults = ProviderFactory.getDefaultConfig(providerName)
      defaults.foreach { case (key, value) =>
        if (!configStore.contains(key) || key == ConfigStore.MODEL) {
          configStore.set(key, value)
        }
      }
      
      // Set provider
      configStore.set(ConfigStore.PROVIDER, providerName)
      
      // Validate immediately using descriptor
      ProviderRegistry.get(providerName).foreach { desc =>
        desc.readinessCheck match {
          case ReadinessCheck.ServerReachable =>
            if (!isOllamaReachable) {
              val baseUrl = configStore.get(desc.baseUrlConfigKey)
                .orElse(configStore.get(ConfigStore.BASE_URL))
                .getOrElse(desc.defaultBaseUrl)
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
          configStore.loadFromMap(config)

          // Load model override file if available
          modelDir.foreach { dir =>
            ModelRegistry.loadOverride(dir).foreach { message =>
              println(message)
            }
          }

          // Readiness check uses the now-loaded config (needs apiKeyConfigKey lookup).
          // Roll back on failure so llm:active and friends keep the prior state.
          try {
            checkReadiness(desc, providerName, configStore)
          } catch {
            case e: ExtensionException =>
              configStore.loadFromMap(previousConfig)
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
        val provider = ensureProvider(agent)

        val userMessage = ChatMessage.user(inputText)

        // Send chat request with user message included, but don't mutate history yet
        val responseFuture = provider.chatWithFullResponse(snapshotHistory(agent) :+ userMessage)
        val response = Await.result(responseFuture, getAwaitTimeout)
        recordUsage(agent, response)
        val responseMessage = replyMessage(response)

        // Only commit both messages after success
        commitExchange(agent, userMessage, responseMessage)

        responseMessage.content

      } catch {
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
        val provider = ensureProvider(agent)

        val userMessage = ChatMessage.user(inputText)

        // Snapshot on the NetLogo thread; commit the pair atomically on success
        // from the completion thread so overlapping async calls can't interleave.
        val responseFuture = provider.chatWithFullResponse(snapshotHistory(agent) :+ userMessage).map { response =>
          recordUsage(agent, response)
          val responseMessage = replyMessage(response)
          commitExchange(agent, userMessage, responseMessage)
          responseMessage.content
        }

        // Return AnonymousReporter that wraps the Future
        createAwaitableReporter(responseFuture)

      } catch {
        case e: Exception =>
          throw new ExtensionException(s"Failed to start async LLM chat: ${e.getMessage}")
      }
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
        val provider = ensureProvider(agent)

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
        val response = Await.result(responseFuture, getAwaitTimeout)
        recordUsage(agent, response)
        val responseMessage = replyMessage(response)

        // Commit both template message and response to permanent history on success
        commitExchange(agent, userMessage, responseMessage)

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
        val provider = ensureProvider(agent)

        val choices = choicesList.map(_.toString).toList

        if (choices.isEmpty) {
          throw new ExtensionException("Choice list cannot be empty")
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
        val response = Await.result(responseFuture, getAwaitTimeout)
        recordUsage(agent, response)

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
        commitExchange(agent, ChatMessage.user(prompt), ChatMessage.assistant(chosenOption))

        chosenOption

      } catch {
        case e: Exception if !e.isInstanceOf[ExtensionException] =>
          throw new ExtensionException(s"LLM choice failed: ${e.getMessage}")
      }
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
      val format =
        try ResponseFormat.parseSchema(schemaText)
        catch {
          case e: IllegalArgumentException =>
            throw new ExtensionException(s"llm:chat-with-schema: ${e.getMessage}")
        }

      // This reporter's syntax promises a list, and only a JSON object converts
      // to the [key value] pairs llm:get reads. A top-level scalar or array
      // schema would report a bare string or a flat list instead, breaking that
      // promise, so it is rejected here rather than at the provider.
      val topLevelType = format.schema.value.get("type").collect { case ujson.Str(s) => s }
      if (!topLevelType.contains("object")) {
        throw new ExtensionException(
          s"llm:chat-with-schema: schema must have type 'object' at the top level, but got " +
            s"'${topLevelType.getOrElse("none")}'. llm:chat-with-schema reports a list of " +
            "[key value] pairs, so the reply has to be a JSON object. Wrap it, e.g. " +
            """{"type":"object","properties":{"value":{"type":"string"}}}"""
        )
      }

      try {
        val provider = ensureProvider(agent)

        val userMessage = ChatMessage.user(inputText)

        val responseFuture = provider.chatWithFormat(snapshotHistory(agent) :+ userMessage, format)
        val response = Await.result(responseFuture, getAwaitTimeout)
        recordUsage(agent, response)

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

        commitExchange(agent, userMessage, ChatMessage.assistant(content))

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
        val provider = ensureProvider(agent)

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
        val response = Await.result(responseFuture, getAwaitTimeout)
        recordUsage(agent, response)

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
              s"llm:chat-json: Response was not valid JSON: ${e.getMessage}. Response text: $content"
            )
        }

        // Store the clean exchange only — the JSON instruction is prompt
        // scaffolding, not part of the conversation.
        commitExchange(agent, userMessage, ChatMessage.assistant(content))

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
        val provider = ensureProvider(agent)

        val userMessage = ChatMessage.user(inputText)

        // Send with user message included, but don't mutate history yet
        val responseFuture = provider.chatWithFullResponse(snapshotHistory(agent) :+ userMessage)
        val response = Await.result(responseFuture, getAwaitTimeout)
        recordUsage(agent, response)

        val answerText = response.firstContent.getOrElse("")
        val thinkingText = response.thinking.getOrElse("")

        // Only commit both messages after success (clean answer only, not thinking text)
        commitExchange(agent, userMessage, ChatMessage.assistant(answerText))

        // Return [answer thinking] list — always 2 elements
        LogoList(answerText, thinkingText)

      } catch {
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
              val baseUrl = configStore.get(ConfigStore.OLLAMA_BASE_URL)
                .orElse(configStore.get(ConfigStore.BASE_URL))
                .getOrElse(ConfigStore.DEFAULT_OLLAMA_BASE_URL)
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

      val candidate = ConfigStore.withDefaults()
      candidate.updateFromMap(config)
      checkReadiness(desc, providerName, candidate)

      try profiles.load(name, candidate.toMap)
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
        }.foreach { modelDir =>
          ModelRegistry.loadOverride(modelDir)
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
