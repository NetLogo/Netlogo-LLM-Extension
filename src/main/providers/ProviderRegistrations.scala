// ABOUTME: Registers all built-in provider descriptors with the ProviderRegistry
// ABOUTME: Adding a new provider = adding one block here (plus the provider class and models.yaml)
package org.nlogo.extensions.llm.providers

import scala.concurrent.ExecutionContext

/**
 * Central registration of all built-in providers.
 *
 * Called once during LLMExtension.load() to populate the ProviderRegistry.
 * To add a new provider, add one register() block here.
 */
object ProviderRegistrations {

  def registerAll(): Unit = {

    ProviderRegistry.register(ProviderDescriptor(
      name = "openai",
      displayName = "OpenAI",
      apiKeyConfigKey = "openai_api_key",
      baseUrlConfigKey = "openai_base_url",
      defaultBaseUrl = "https://api.openai.com/v1",
      defaultModel = "gpt-4o-mini",
      defaultMaxTokens = "1000",
      requiresApiKey = true,
      apiKeyPrefix = Some("sk-"),
      readinessCheck = ReadinessCheck.ApiKey,
      exposesThinking = false,
      helpText =
        """OpenAI Setup Instructions:
          |
          |1. Get an API key:
          |   - Visit https://platform.openai.com/api-keys
          |   - Create a new API key
          |
          |2. Set the key:
          |   - In config file: openai_api_key=sk-your-key-here
          |   - Or at runtime: llm:set-api-key "sk-your-key-here"
          |
          |3. Verify:
          |   - Check llm:provider-status for "has-key: true"""".stripMargin,
      factory = ec => new OpenAIProvider()(using ec)
    ))

    ProviderRegistry.register(ProviderDescriptor(
      name = "anthropic",
      displayName = "Anthropic (Claude)",
      apiKeyConfigKey = "anthropic_api_key",
      baseUrlConfigKey = "anthropic_base_url",
      defaultBaseUrl = "https://api.anthropic.com/v1",
      defaultModel = "claude-haiku-4-5-20251001",
      defaultMaxTokens = "4000",
      requiresApiKey = true,
      apiKeyPrefix = None,
      readinessCheck = ReadinessCheck.ApiKey,
      exposesThinking = true,
      helpText =
        """Anthropic (Claude) Setup Instructions:
          |
          |1. Get an API key:
          |   - Visit https://console.anthropic.com/
          |   - Create a new API key
          |
          |2. Set the key:
          |   - In config file: anthropic_api_key=sk-ant-your-key-here
          |   - Or at runtime: llm:set-api-key "sk-ant-your-key-here"
          |
          |3. Verify:
          |   - Check llm:provider-status for "has-key: true"""".stripMargin,
      factory = ec => new ClaudeProvider()(using ec)
    ))

    ProviderRegistry.register(ProviderDescriptor(
      name = "gemini",
      displayName = "Google Gemini",
      apiKeyConfigKey = "gemini_api_key",
      baseUrlConfigKey = "gemini_base_url",
      defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta",
      defaultModel = "gemini-2.5-flash",
      defaultMaxTokens = "2048",
      requiresApiKey = true,
      apiKeyPrefix = None,
      readinessCheck = ReadinessCheck.ApiKey,
      exposesThinking = true,
      helpText =
        """Google Gemini Setup Instructions:
          |
          |1. Get an API key:
          |   - Visit https://makersuite.google.com/app/apikey
          |   - Create a new API key
          |
          |2. Set the key:
          |   - In config file: gemini_api_key=your-key-here
          |   - Or at runtime: llm:set-api-key "your-key-here"
          |
          |3. Verify:
          |   - Check llm:provider-status for "has-key: true"""".stripMargin,
      factory = ec => new GeminiProvider()(using ec)
    ))

    ProviderRegistry.register(ProviderDescriptor(
      name = "ollama",
      displayName = "Ollama",
      apiKeyConfigKey = "ollama_api_key",
      baseUrlConfigKey = "ollama_base_url",
      defaultBaseUrl = "http://localhost:11434",
      defaultModel = "llama3.2:3b",
      defaultMaxTokens = "2048",
      requiresApiKey = false,
      apiKeyPrefix = None,
      readinessCheck = ReadinessCheck.ServerReachable,
      exposesThinking = true,
      helpText =
        """Ollama Setup Instructions:
          |
          |1. Install Ollama:
          |   - Visit https://ollama.ai/download
          |   - Download and install for your platform
          |
          |2. Start Ollama server:
          |   - Open terminal and run: ollama serve
          |   - Or start Ollama app (it runs in background)
          |
          |3. Pull a model:
          |   - Run: ollama pull llama3.2:3b
          |   - Or try: ollama pull deepseek-r1:1.5b (smaller)
          |
          |4. Verify installation:
          |   - Check llm:provider-status for "reachable: true"
          |
          |5. Custom server URL:
          |   - In config: ollama_base_url=http://your-server:11434
          |   - Default: http://localhost:11434
          |
          |For more models: ollama.ai/library""".stripMargin,
      factory = ec => new OllamaProvider()(using ec)
    ))

    ProviderRegistry.register(ProviderDescriptor(
      name = "openrouter",
      displayName = "OpenRouter",
      apiKeyConfigKey = "openrouter_api_key",
      baseUrlConfigKey = "openrouter_base_url",
      defaultBaseUrl = "https://openrouter.ai/api/v1",
      defaultModel = "openai/gpt-4o-mini",
      defaultMaxTokens = "1000",
      requiresApiKey = true,
      apiKeyPrefix = None,
      readinessCheck = ReadinessCheck.ApiKey,
      exposesThinking = true,
      helpText =
        """OpenRouter Setup Instructions:
          |
          |1. Get an API key:
          |   - Visit https://openrouter.ai/keys
          |   - Create a new API key
          |
          |2. Set the key:
          |   - In config file: openrouter_api_key=sk-or-your-key-here
          |   - Or at runtime: llm:set-api-key "sk-or-your-key-here"
          |
          |3. Set a model (vendor-prefixed):
          |   - llm:set-model "openai/gpt-4o"
          |   - llm:set-model "anthropic/claude-3.5-sonnet"
          |   - llm:set-model "deepseek/deepseek-r1"
          |
          |4. Verify:
          |   - Check llm:provider-status for "has-key: true"
          |
          |Browse 200+ models: https://openrouter.ai/models""".stripMargin,
      factory = ec => new OpenRouterProvider()(using ec)
    ))

    ProviderRegistry.register(ProviderDescriptor(
      name = "together",
      displayName = "Together AI",
      apiKeyConfigKey = "together_api_key",
      baseUrlConfigKey = "together_base_url",
      defaultBaseUrl = "https://api.together.xyz/v1",
      defaultModel = "meta-llama/Llama-3.3-70B-Instruct-Turbo",
      defaultMaxTokens = "1000",
      requiresApiKey = true,
      apiKeyPrefix = None,
      readinessCheck = ReadinessCheck.ApiKey,
      exposesThinking = true,
      helpText =
        """Together AI Setup Instructions:
          |
          |1. Get an API key:
          |   - Visit https://api.together.ai/settings/api-keys
          |   - Create a new API key
          |
          |2. Set the key:
          |   - In config file: together_api_key=your-key-here
          |   - Or at runtime: llm:set-api-key "your-key-here"
          |
          |3. Set a model (vendor-prefixed):
          |   - llm:set-model "meta-llama/Llama-3.3-70B-Instruct-Turbo"
          |   - llm:set-model "deepseek-ai/DeepSeek-R1"
          |   - llm:set-model "Qwen/Qwen2.5-72B-Instruct-Turbo"
          |
          |4. Verify:
          |   - Check llm:provider-status for "has-key: true"
          |
          |Browse models: https://api.together.ai/models""".stripMargin,
      factory = ec => new TogetherProvider()(using ec)
    ))

    ProviderRegistry.register(ProviderDescriptor(
      name = "groq",
      displayName = "Groq",
      apiKeyConfigKey = "groq_api_key",
      baseUrlConfigKey = "groq_base_url",
      defaultBaseUrl = "https://api.groq.com/openai/v1",
      defaultModel = "openai/gpt-oss-20b",
      defaultMaxTokens = "1000",
      requiresApiKey = true,
      apiKeyPrefix = Some("gsk_"),
      readinessCheck = ReadinessCheck.ApiKey,
      exposesThinking = true,
      helpText =
        """Groq Setup Instructions:
          |
          |1. Get an API key:
          |   - Visit https://console.groq.com/keys
          |   - Create a new API key
          |
          |2. Set the key:
          |   - In config file: groq_api_key=gsk_your-key-here
          |   - Or at runtime: llm:set-api-key "gsk_your-key-here"
          |
          |3. Set a model:
          |   - llm:set-model "openai/gpt-oss-20b"
          |   - llm:set-model "openai/gpt-oss-120b"
          |   - llm:set-model "groq/compound-mini"
          |
          |4. Verify:
          |   - Check llm:provider-status for "has-key: true"
          |
          |Groq's free tier is fast and generous — a good fit for classrooms.
          |Browse models: https://console.groq.com/docs/models""".stripMargin,
      factory = ec => new GroqProvider()(using ec)
    ))
  }
}
