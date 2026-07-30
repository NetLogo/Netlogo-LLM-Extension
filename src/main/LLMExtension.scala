package org.nlogo.extensions.llm

import org.nlogo.api._
import org.nlogo.core.{LogoList, Syntax}
import org.nlogo.extensions.llm.config.{ConfigLoader, ConfigStore}
import org.nlogo.extensions.llm.providers.{LLMProvider, ProviderFactory, ProviderRegistry, ProviderRegistrations, ModelRegistry, OllamaProvider, ReadinessCheck}
import org.nlogo.extensions.llm.models.{ChatMessage, ChatResponse}
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
  
  // Per-agent conversation history
  private val messageHistory: WeakHashMap[Agent, ArrayBuffer[ChatMessage]] = WeakHashMap()

  // Guards messageHistory and every contained buffer. Never held across Await
  // or provider calls; critical sections are small snapshots/appends only.
  private val historyLock = new Object

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
          Await.result(future, getTimeoutSeconds.seconds)
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
  }
  
  /**
   * Called when NetLogo calls clear-all or when the model is reset
   */
  override def clearAll(): Unit = {
    historyLock.synchronized { messageHistory.clear() }
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
   * Check if a provider has an API key configured
   */
  private def hasApiKey(providerName: String): Boolean = {
    val providerKeyName = ConfigStore.getProviderApiKeyName(providerName)
    configStore.get(providerKeyName).orElse(configStore.get(ConfigStore.API_KEY)) match {
      case Some(key) => key.trim.nonEmpty
      case None => false
    }
  }
  
  /**
   * Check if Ollama is reachable (synchronous with short timeout)
   */
  private def isOllamaReachable: Boolean = {
    try {
      val provider = new OllamaProvider()
      val baseUrl = configStore.get(ConfigStore.OLLAMA_BASE_URL)
        .orElse(configStore.get(ConfigStore.BASE_URL))
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
            desc.readinessCheck match {
              case ReadinessCheck.ServerReachable =>
                if (!isOllamaReachable) {
                  val baseUrl = configStore.get(desc.baseUrlConfigKey)
                    .orElse(configStore.get(ConfigStore.BASE_URL))
                    .getOrElse(desc.defaultBaseUrl)
                  throw new ExtensionException(
                    s"Config loaded but ${desc.displayName} not reachable at $baseUrl. Please start the server or change ${desc.baseUrlConfigKey} in config. For help: print llm:provider-help \"${desc.name}\""
                  )
                }
              case ReadinessCheck.ApiKey =>
                if (!hasApiKey(providerName)) {
                  throw new ExtensionException(
                    s"Config loaded but ${desc.displayName} provider requires an API key. Set '${desc.apiKeyConfigKey}' in config. For help: print llm:provider-help \"${desc.name}\""
                  )
                }
            }
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
        val provider = ensureProvider()

        val userMessage = ChatMessage.user(inputText)

        // Send chat request with user message included, but don't mutate history yet
        val responseFuture = provider.chat(snapshotHistory(agent) :+ userMessage)
        val responseMessage = Await.result(responseFuture, getTimeoutSeconds.seconds)

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
        val provider = ensureProvider()

        val userMessage = ChatMessage.user(inputText)

        // Snapshot on the NetLogo thread; commit the pair atomically on success
        // from the completion thread so overlapping async calls can't interleave.
        val responseFuture = provider.chat(snapshotHistory(agent) :+ userMessage).map { responseMessage =>
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
        val provider = ensureProvider()

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
        val responseFuture = provider.chat(tempHistory.toSeq)
        val responseMessage = Await.result(responseFuture, getTimeoutSeconds.seconds)

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
    override def getSyntax: Syntax = Syntax.reporterSyntax(
      right = List(Syntax.StringType, Syntax.ListType),
      ret = Syntax.StringType
    )

    override def report(args: Array[Argument], context: Context): AnyRef = {
      val prompt = args(0).getString
      val choicesList = args(1).getList
      val agent = context.getAgent

      try {
        val provider = ensureProvider()

        val choices = choicesList.map(_.toString).toList

        if (choices.isEmpty) {
          throw new ExtensionException("Choice list cannot be empty")
        }

        val systemPrompt = "You are a decision-making assistant. " +
          "When given options, respond with EXACTLY one option from the list. " +
          "Rules: Reply with ONLY the option text. " +
          "No explanation, no numbering, no punctuation, no quotes, no extra words. " +
          "Copy the option exactly as written."

        val userPrompt = s"$prompt\n\nOptions:\n${choices.mkString("\n")}\n\n" +
          "Your choice (one option, no other text):"

        // Build temp history with system prompt — don't mutate permanent history
        val tempHistory = ArrayBuffer.from(snapshotHistory(agent))
        tempHistory.prepend(ChatMessage.system(systemPrompt))
        tempHistory += ChatMessage.user(userPrompt)

        // Use chatWithFullResponse to access thinking field for thinking models
        val responseFuture = provider.chatWithFullResponse(tempHistory.toSeq)
        val response = Await.result(responseFuture, getTimeoutSeconds.seconds)

        // Extract text: prefer content, fall back to thinking field
        val text = response.firstContent.filter(_.nonEmpty)
          .orElse(response.thinking)
          .getOrElse("")
          .trim

        // Exact match only (case-insensitive)
        val chosenOption = choices.find(_.equalsIgnoreCase(text))
          .getOrElse {
            throw new ExtensionException(
              s"llm:choose: response '$text' did not match any choice. " +
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
        val provider = ensureProvider()

        val userMessage = ChatMessage.user(inputText)

        // Send with user message included, but don't mutate history yet
        val responseFuture = provider.chatWithFullResponse(snapshotHistory(agent) :+ userMessage)
        val response = Await.result(responseFuture, getTimeoutSeconds.seconds)

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
      if (!Set("none", "low", "medium", "high", "xhigh").contains(effort)) {
        throw new ExtensionException(
          s"Invalid reasoning effort: '$effort'. Must be one of: none, low, medium, high, xhigh"
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

      val banned = args(1).getList.toVector.collect {
        case s: String => s.trim.toLowerCase
      }.filter(_.nonEmpty)

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
  
  object ActiveReporter extends Reporter {
    override def getSyntax: Syntax = Syntax.reporterSyntax(ret = Syntax.ListType)
    
    override def report(args: Array[Argument], context: Context): AnyRef = {
      try {
        val provider = configStore.getOrElse(ConfigStore.PROVIDER, ConfigStore.DEFAULT_PROVIDER)
        val model = configStore.getOrElse(ConfigStore.MODEL, ModelRegistry.defaultModel(provider))
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
        configStore.summary
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
