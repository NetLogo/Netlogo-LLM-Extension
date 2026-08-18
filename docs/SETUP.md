# NetLogo Multi-LLM Extension - Setup Guide

## Quick Start

1. **Download Extension**: Copy `llm.jar` to your NetLogo extensions folder
2. **Create Config**: Copy and edit `demos/config.txt` for your provider
3. **Load Extension**: Add `extensions [llm]` to your NetLogo model
4. **Load Config**: Run `llm:load-config "config.txt"` in your code
5. **Start Chatting**: Use `llm:chat "Hello, world!"` to test

## Installation

### Step 1: Install the Extension

Place `llm.jar` in your NetLogo extensions directory:

**Windows**: `C:\Users\[username]\AppData\Roaming\NetLogo\[version]\extensions\`
**Mac**: `~/Library/Application Support/NetLogo/[version]/extensions/`
**Linux**: `~/.netlogo/[version]/extensions/`

### Step 2: Verify NetLogo Version

This extension requires **NetLogo 7.0.0 or later**. Check your version in NetLogo:
- Help → About NetLogo

## Provider Setup

### OpenAI (GPT Models)

1. **Get API Key**: Visit [platform.openai.com](https://platform.openai.com/api-keys)
2. **Create config.txt**:
```
provider=openai
model=gpt-4o-mini
api_key=sk-your-openai-key-here
temperature=0.7
max_tokens=1000
```

**Available Models**:
- `gpt-4o-mini` - Fast, cost-effective (recommended)
- `gpt-4o` - Most capable GPT-4 variant
- `gpt-4-turbo` - Faster GPT-4 with large context
- `gpt-3.5-turbo` - Legacy model, still capable

### Anthropic (Claude Models)

1. **Get API Key**: Visit [console.anthropic.com](https://console.anthropic.com/)
2. **Create config.txt**:
```
provider=anthropic
model=claude-3-haiku-20240307
api_key=sk-ant-your-anthropic-key-here
temperature=0.5
max_tokens=4000
```

**Available Models**:
- `claude-3-haiku-20240307` - Fast, cost-effective (recommended)
- `claude-3-sonnet-20240229` - Balanced performance
- `claude-3-opus-20240229` - Most capable Claude
- `claude-3-5-sonnet-20241022` - Latest improved Sonnet

### Google Gemini

1. **Get API Key**: Visit [aistudio.google.com](https://aistudio.google.com/app/apikey)
2. **Create config.txt**:
```
provider=gemini
model=gemini-1.5-flash
api_key=your-google-ai-api-key-here
temperature=0.8
max_tokens=2048
```

**Available Models**:
- `gemini-1.5-flash` - Fast, free tier available (recommended)
- `gemini-1.5-pro` - Most capable Gemini
- `gemini-pro` - Original Gemini Pro

### Ollama (Local Models)

1. **Install Ollama**: Download from [ollama.ai](https://ollama.ai/)
2. **Start Server**: Run `ollama serve` in terminal
3. **Pull Model**: Run `ollama pull llama3.2`
4. **Create config.txt**:
```
provider=ollama
model=llama3.2
temperature=0.7
max_tokens=2048
timeout_seconds=60
```

**Popular Models** (must pull first):
- `llama3.2` - Latest Llama (recommended)
- `llama3.1` - Previous Llama version
- `mistral` - Mistral 7B
- `codellama` - Code-specialized Llama
- `deepseek-r1:1.5b` - Reasoning model

**Pull command**: `ollama pull [model-name]`

### OpenRouter (200+ models via one key)

OpenRouter routes requests to many model vendors (OpenAI, Anthropic, Google, Meta, DeepSeek, etc.) through a single API.

1. **Get API Key**: Visit [openrouter.ai/keys](https://openrouter.ai/keys)
2. **Create config.txt**:
```
provider=openrouter
model=openai/gpt-4o-mini
openrouter_api_key=sk-or-your-key-here
temperature=0.7
max_tokens=1000
```

**Available Models** (vendor-prefixed):
- `openai/gpt-4o-mini` - Fast, cost-effective (recommended)
- `openai/gpt-4o` - GPT-4o through OpenRouter
- `anthropic/claude-3.5-sonnet` - Claude 3.5 Sonnet
- `anthropic/claude-3.5-haiku` - Fast Claude
- `google/gemini-2.0-flash-exp` - Latest Gemini
- `meta-llama/llama-3.3-70b-instruct` - Llama 3.3 70B
- `deepseek/deepseek-r1` - Reasoning model

Browse the full catalog: [openrouter.ai/models](https://openrouter.ai/models)

### Together AI (open-source models)

Together AI provides fast inference for open-source models (Llama, DeepSeek, Qwen, Mistral, Gemma) via an OpenAI-compatible API.

1. **Get API Key**: Visit [api.together.ai/settings/api-keys](https://api.together.ai/settings/api-keys)
2. **Create config.txt**:
```
provider=together
model=meta-llama/Llama-3.3-70B-Instruct-Turbo
together_api_key=your-together-key-here
temperature=0.7
max_tokens=1000
```

**Available Models** (vendor-prefixed):
- `meta-llama/Llama-3.3-70B-Instruct-Turbo` - Fast Llama 3.3 (recommended)
- `meta-llama/Meta-Llama-3.1-70B-Instruct-Turbo` - Llama 3.1 70B
- `deepseek-ai/DeepSeek-R1` - Reasoning model with `<think>` tag output
- `Qwen/Qwen2.5-72B-Instruct-Turbo` - Qwen 2.5 72B
- `mistralai/Mixtral-8x22B-Instruct-v0.1` - Mistral mixture-of-experts

Browse the full catalog: [api.together.ai/models](https://api.together.ai/models)

**Reasoning model note:** DeepSeek-R1 emits its thinking inside `<think>...</think>` tags (sometimes filling the entire response). Use `llm:chat-with-thinking` to get the answer and reasoning split out, and bump `max_tokens` to 2000+ so the model has room to finish its answer after thinking.

### Groq (fast free-tier inference)

Groq runs open-weight models on custom LPU hardware via an OpenAI-compatible API. Its free tier is fast and generous, which makes it a good choice for workshops and classrooms.

1. **Get API Key**: Visit [console.groq.com/keys](https://console.groq.com/keys)
2. **Create config.txt**:
```
provider=groq
model=openai/gpt-oss-20b
groq_api_key=gsk_your-key-here
temperature=0.7
max_tokens=1000
```

**Available Models**:
- `openai/gpt-oss-20b` - Fast open-weight reasoning model (recommended)
- `openai/gpt-oss-120b` - Larger open-weight reasoning model
- `groq/compound-mini` - Agentic system with built-in tool use
- `qwen/qwen3.6-27b` - Qwen 3.6 reasoning model (preview)

Browse the full catalog: [console.groq.com/docs/models](https://console.groq.com/docs/models)

**Reasoning model note:** the gpt-oss models always produce reasoning output. Use `llm:chat-with-thinking` to get the answer and reasoning split out, and `llm:set-reasoning-effort "low"` to keep latency and token use down in classroom settings.

**Model lifecycle note:** Groq retires models often. If a model stops working, check [console.groq.com/docs/deprecations](https://console.groq.com/docs/deprecations).

## Configuration Parameters

### Core Settings

| Parameter | Description | Required | Default |
|-----------|-------------|----------|---------|
| `provider` | LLM provider (`openai`, `anthropic`, `gemini`, `ollama`, `openrouter`, `together`, `groq`) | Yes | - |
| `model` | Model identifier | Yes | Provider-specific |
| `api_key` | API authentication key | Yes* | - |
| `temperature` | Response randomness (0.0-1.0) | No | 0.7 |
| `max_tokens` | Maximum response length | No | 1000 |
| `timeout_seconds` | Request timeout | No | 30 |
| `retry_max_retries` | Retry attempts after a rate-limit (HTTP 429) response | No | 6 |
| `retry_max_elapsed_seconds` | Total time allowed waiting out rate limits | No | 65 |
| `max_concurrent_requests` | Maximum requests in flight at once, per provider | No | 0 (off) |
| `min_request_interval_ms` | Minimum milliseconds between request starts | No | 0 (off) |

*Not required for Ollama

### Request throttling

`retry_max_retries` recovers from a rate limit *after* it happens. Throttling exists
to avoid tripping one in the first place.

`ask turtles [ llm:chat-async ... ]` sends one request per turtle with nothing between
the agents and the network, so 200 turtles open 200 connections on the first tick.
`max_concurrent_requests` caps how many may be in flight at once; the rest queue and
run as capacity frees up. No request is dropped — they are deferred, not discarded.

```
# At most 4 requests in flight, started at least 250ms apart
max_concurrent_requests=4
min_request_interval_ms=250
```

**Units and semantics:**

- `max_concurrent_requests` — a whole number of requests. **Unset or `0` disables**
  throttling, giving the unbounded behavior of earlier versions. Throttling is off by
  default, so existing models are unaffected until you set it.
- `min_request_interval_ms` — **milliseconds** between the starts of successive
  requests. Unset or `0` disables pacing. Use it for a requests-per-minute quota: a
  concurrency cap bounds how many run *at once*, not how many run *per minute*, so a
  small cap recycling quickly can still exceed an RPM limit. For a 20 RPM quota, an
  interval of `3000` keeps you inside it.
- A **negative or unparseable** value is a mistake rather than a choice, so it is
  reported on stderr and then treated as disabled. A typo will not stall your model,
  but it will not pass silently either. Use `0` when you mean to switch throttling off.

The limit is shared per provider and endpoint. Every request to the same provider and
provider-specific base URL key (`openai_base_url`, `gemini_base_url`, and so on — the
same value used to build the request) draws on one cap, no matter how many times the
extension rebuilds its provider internally, while a different provider gets its own —
a Gemini free tier will not throttle a paid OpenAI key running alongside it. Spellings
of one endpoint that differ only by trailing slash or letter case count as the same
endpoint. API keys are never part of that identity: it is rebuilt from scheme, host,
port and path only, so a credential in a URL's userinfo or query string is discarded.

Queued requests hold no thread while they wait, and are admitted in arrival order, so
no agent can be starved by later arrivals.

**Throttling and `timeout_seconds`:** queue time is *not* added to the timeout budget.
How long a request waits depends on how many agents call in a tick, not on the cap, so
there is no formula that could size an allowance for it honestly. This means a model
whose fan-out greatly exceeds its cap can still exceed `timeout_seconds` while queued —
if you set a small cap for a large population, raise `timeout_seconds` to match. An
unthrottled model's timeout behavior is exactly as it was.

Choosing a value: start from your provider's documented limits. A free tier allowing
5 requests/minute suits `max_concurrent_requests=1` with `min_request_interval_ms=12000`.
A paid key with generous limits may not need throttling at all. If a run still hits 429s
with throttling on, the cap is above the quota — lower it, or reduce how often the model
calls the LLM.

### Rate limits and retries

When a provider returns HTTP 429, the extension waits and retries with jittered
exponential backoff. If the provider states how long to wait — via a `Retry-After`
header, or Gemini's `error.details[].RetryInfo.retryDelay` — that delay is honored,
since retrying before the quota window reopens always fails.

The defaults are sized to sit out a per-minute quota window (free tiers are often
about 5 requests/minute, which a model calling an LLM per agent per tick exceeds
immediately). Waits of 2s or more are announced on stderr so a long pause inside
`go` is not mistaken for a hang.

Time spent waiting out a rate limit does **not** count against `timeout_seconds`;
that budget covers the request itself. Lowering `timeout_seconds` therefore does not
cost you the ability to recover from a rate limit. If a run still fails after
exhausting the budget, the error says so explicitly — that usually means the quota
is too low for the simulation, so reduce the request rate (fewer agents per tick, or
call the LLM every N ticks) rather than only raising `retry_max_elapsed_seconds`.

### Advanced Settings

| Parameter | Description | Default |
|-----------|-------------|---------|
| `base_url` | Custom API endpoint | Provider default |

### Provider-Specific Defaults

**OpenAI**:
- `base_url`: `https://api.openai.com/v1`
- `max_tokens`: 1000

**Anthropic**:
- `base_url`: `https://api.anthropic.com/v1`
- `max_tokens`: 4000

**Gemini**:
- `base_url`: `https://generativelanguage.googleapis.com/v1beta`
- `max_tokens`: 2048

**Ollama**:
- `base_url`: `http://localhost:11434`
- `max_tokens`: 2048
- `timeout_seconds`: 60

**OpenRouter**:
- `base_url`: `https://openrouter.ai/api/v1`
- `max_tokens`: 1000
- API key config field: `openrouter_api_key`

**Together AI**:
- `base_url`: `https://api.together.xyz/v1`
- `max_tokens`: 1000
- API key config field: `together_api_key`

**Groq**:
- `base_url`: `https://api.groq.com/openai/v1`
- `max_tokens`: 1000
- API key config field: `groq_api_key`

## Testing Your Setup

1. **Create Test Model**:
```netlogo
extensions [llm]

to setup
  llm:load-config "config.txt"
  print "Extension loaded successfully!"
end

to test-chat
  let response llm:chat "Hello! Please respond with exactly: 'Setup successful!'"
  print response
end
```

2. **Run Test**:
   - Click `setup` button
   - Click `test-chat` button  
   - Check Command Center for "Setup successful!" response

## Troubleshooting

### Common Issues

**"Extension not found"**
- Ensure `llm.jar` is in correct extensions directory
- Restart NetLogo after copying extension

**"Provider not found"**
- Check `provider=` line in config.txt
- Verify spelling: `openai`, `anthropic`, `gemini`, `ollama`, `openrouter`, `together`

**"API key invalid"**
- Verify API key is correct and active
- Check key format matches provider requirements
- Ensure sufficient API credits/quota

**"Model not found"**
- For Ollama: Run `ollama pull [model-name]` first
- For cloud providers: Check model availability in your tier

**"Connection timeout"**
- Increase `timeout_seconds` in config
- For Ollama: Ensure `ollama serve` is running
- Check network connectivity

**"Rate limited"**
- Wait and retry
- Consider upgrading API tier
- Reduce request frequency

### Getting Help

1. Check the [demos](demos/) folder for working examples
2. Review [API-REFERENCE.md](API-REFERENCE.md) for detailed usage
3. Test with simple prompts first
4. Enable debug logging if available

## Next Steps

- Read [API-REFERENCE.md](API-REFERENCE.md) for complete primitive documentation
- Explore [demos/](demos/) for example NetLogo models
- See [EXAMPLES.md](EXAMPLES.md) for common usage patterns
