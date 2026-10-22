# NetLogo Multi-LLM Extension - Provider Configuration Guide

## Overview

This guide provides comprehensive configuration details for all supported LLM providers, including hyperparameters, model options, and provider-specific settings.

Model lists and each provider's default model come from the extension's model
catalog (`models.yaml`), current as of October 2026. Model IDs retire often; to
add a new ID or change a default without updating the extension, put a
`models.yaml` in `~/.netlogo-llm/` — see [CONFIGURATION.md](CONFIGURATION.md#model-catalog-modelsyaml-user-catalog-models-overrideyaml).

## OpenAI Configuration

### API Setup

1. **Get API Key**: Visit [platform.openai.com](https://platform.openai.com/api-keys)
2. **Check Usage**: Monitor costs at [platform.openai.com/usage](https://platform.openai.com/usage)
3. **Rate Limits**: View limits at [platform.openai.com/settings/organization/limits](https://platform.openai.com/settings/organization/limits)

### Configuration Parameters

```ini
# Required Parameters
provider=openai
api_key=sk-your-openai-key-here
model=gpt-6-luna

# Optional Parameters  
base_url=https://api.openai.com/v1
temperature=0.7
max_tokens=1000
timeout_seconds=30
```

### Available Models

| Model | Description | Context | Cost (per 1K tokens) |
|-------|-------------|---------|---------------------|
| `gpt-6-luna` | Fast, cost-effective reasoning model (default) | 1.05M | Input: $0.0001, Output: $0.0005 |
| `gpt-6.1-sol` | Mid-tier GPT-6 | — | Input: $0.002, Output: $0.01 |
| `gpt-6-astra` | Most capable GPT-6 | — | Input: $0.01, Output: $0.05 |
| `gpt-5.6-luna` / `gpt-5.6-terra` / `gpt-5.6-sol` | Previous GPT-5.x reasoning models | — | See OpenAI pricing |
| `gpt-4o-mini` | Deprecated, served until 2027-01-14 | 128K | Input: $0.00015, Output: $0.0006 |

**Recommended for NetLogo**: `gpt-6-luna` (best balance of speed, capability, and cost)

GPT-5.x and GPT-6.x are reasoning models, like the retired o-series. The
extension sends them in reasoning format automatically: system prompts go as
`developer` messages, `max_tokens` is sent as `max_completion_tokens`, and
`temperature` is not sent. Set depth with `reasoning_effort`
(`none|low|medium|high|xhigh`).

Reasoning tokens count against `max_completion_tokens`. When you have not set
`max_tokens`, reasoning models are sent `max_completion_tokens=4000` instead of
the provider default (1000), so the model has room left to answer. Any
`max_tokens` you set yourself, in a config file or a profile, is sent unchanged,
even one below 4000. If a reply still comes back empty because the model used
its whole budget (`finish_reason=length`), the call fails with an error that
names the tokens used and tells you to set `max_tokens` above that. The tokens
are still counted in `llm:usage` and `llm:usage-total`. The same applies to
reasoning models on OpenRouter, Together AI and Groq.

### Hyperparameters

#### temperature (float, 0.0-2.0, default: 0.7)
Controls response randomness and creativity.

```ini
temperature=0.0    # Deterministic, factual responses
temperature=0.3    # Slightly more focused than default
temperature=0.7    # Balanced creativity (recommended)
temperature=1.0    # More creative and varied
temperature=1.5    # Highly creative, less consistent
```

#### max_tokens (integer, 1-4096, default: 1000)
Maximum length of the response.

```ini
max_tokens=100     # Short responses
max_tokens=1000    # Default length
max_tokens=2000    # Longer responses
max_tokens=4000    # Very long responses (costs more)
```

#### top_p (float, 0.0-1.0, default: 1.0)
Alternative to temperature for controlling randomness.

```ini
top_p=0.9          # Slightly more focused
top_p=1.0          # Default (use all tokens)
```

**Note**: OpenAI recommends using either `temperature` OR `top_p`, not both.

### Enterprise/Custom Endpoints

For Azure OpenAI or custom deployments:

```ini
provider=openai
base_url=https://your-resource.openai.azure.com/openai/deployments/your-deployment/chat/completions?api-version=2024-02-15-preview
api_key=your-azure-key
model=gpt-6-luna
```

## Anthropic (Claude) Configuration

### API Setup

1. **Get API Key**: Visit [console.anthropic.com](https://console.anthropic.com/)
2. **Check Usage**: Monitor in Anthropic Console
3. **Rate Limits**: 5 requests/minute (free tier), higher for paid tiers

### Configuration Parameters

```ini
# Required Parameters
provider=anthropic
api_key=sk-ant-your-anthropic-key-here
model=claude-haiku-5-5

# Optional Parameters
base_url=https://api.anthropic.com/v1
temperature=0.5
max_tokens=4000
timeout_seconds=30
```

### Available Models

| Model | Description | Context | Cost (per 1K tokens) |
|-------|-------------|---------|---------------------|
| `claude-haiku-5-5` | Fast, cost-effective (default) | — | Input: $0.0001, Output: $0.0005 (prompts up to 100K) |
| `claude-sonnet-5-5` | Balanced performance | — | Input: $0.002, Output: $0.01 |
| `claude-opus-5-5` | Most capable Opus | — | Input: $0.004, Output: $0.02 |
| `claude-fable-5-1` | Most capable Claude | — | Input: $0.01, Output: $0.05 |
| `claude-haiku-4-5-20251001` | Previous Haiku (still served) | 200K | See Anthropic pricing |

**Recommended for NetLogo**: `claude-haiku-5-5` (excellent speed and capability for most use cases)

Claude 4.7 and later (including every 5.x model) reject a non-default
`temperature`, so the extension leaves it out for them.

### Hyperparameters

#### temperature (float, 0.0-1.0, default: 0.5)
Controls response randomness. Claude is more sensitive to temperature than GPT.

```ini
temperature=0.0    # Most deterministic
temperature=0.3    # Low creativity, high consistency
temperature=0.5    # Balanced (recommended)
temperature=0.7    # More creative responses
temperature=1.0    # Maximum creativity
```

#### max_tokens (integer, 1-4096, default: 4000)
Maximum response length. Claude supports longer responses efficiently.

```ini
max_tokens=1000    # Short responses
max_tokens=4000    # Default (recommended)
max_tokens=8000    # Very long responses (if supported)
```

#### top_p (float, 0.0-1.0, default: 1.0)
Nucleus sampling parameter.

```ini
top_p=0.9          # More focused sampling
top_p=1.0          # Default sampling
```

#### top_k (integer, 1-40, default: not set)
Top-k sampling parameter (Claude-specific).

```ini
top_k=10           # Very focused
top_k=20           # Moderately focused
# Leave unset for default behavior
```

## Google Gemini Configuration

### API Setup

1. **Get API Key**: Visit [aistudio.google.com](https://aistudio.google.com/app/apikey)
2. **Free Tier**: Generous free quota available
3. **Rate Limits**: 15 RPM (free), higher for paid

### Configuration Parameters

```ini
# Required Parameters
provider=gemini
api_key=your-google-ai-api-key-here
model=gemini-3.5-flash-lite

# Optional Parameters
base_url=https://generativelanguage.googleapis.com/v1beta
temperature=0.8
max_tokens=2048
timeout_seconds=30
```

### Available Models

| Model | Description | Context | Cost (per 1K tokens) |
|-------|-------------|---------|---------------------|
| `gemini-3.5-flash-lite` | Fast, free tier available (default) | — | Free tier available |
| `gemini-3.8-flash` | Stronger Flash tier | — | See Google pricing |
| `gemini-3.1-pro-preview` | Most capable Gemini (preview) | — | See Google pricing |
| `gemini-2.5-flash` / `gemini-2.5-pro` | Only for projects that already used 2.5 | 1M | See Google pricing |

**Recommended for NetLogo**: `gemini-3.5-flash-lite` (excellent free tier, very fast)

Thinking depth: Gemini 3 and later take `reasoning_effort`, sent as
`thinkingLevel` (`none` becomes `MINIMAL`, `xhigh` becomes `HIGH`); when an
effort is set, `thinking_budget_tokens` is not sent with it, because the API
rejects the two together. Gemini 2.5 takes only `thinking_budget_tokens`.

### Hyperparameters

#### temperature (float, 0.0-2.0, default: 0.8)
Controls response creativity. Gemini works well with higher temperatures.

```ini
temperature=0.2    # Very focused, factual
temperature=0.5    # Balanced responses
temperature=0.8    # Creative (recommended)
temperature=1.2    # Highly creative
```

#### max_tokens (integer, 1-8192, default: 2048)
Maximum output length.

```ini
max_tokens=512     # Short responses
max_tokens=2048    # Default length
max_tokens=4096    # Longer responses
```

#### top_p (float, 0.0-1.0, default: 0.95)
Nucleus sampling for response variety.

```ini
top_p=0.8          # More focused
top_p=0.95         # Default (recommended)
top_p=1.0          # Maximum variety
```

#### top_k (integer, 1-40, default: 40)
Top-k sampling parameter.

```ini
top_k=20           # More focused
top_k=40           # Default (recommended)
```

#### candidate_count (integer, 1-8, default: 1)
Number of response candidates to generate (only first is returned).

```ini
candidate_count=1  # Default (recommended)
# Higher values increase cost but may improve quality
```

## OpenRouter Configuration

### API Setup

1. **Get API Key**: Visit [openrouter.ai/keys](https://openrouter.ai/keys)
2. **Check Usage**: Monitor credits at [openrouter.ai/credits](https://openrouter.ai/credits)
3. **Browse Models**: Explore 200+ models at [openrouter.ai/models](https://openrouter.ai/models)

### Configuration Parameters

```ini
# Required Parameters
provider=openrouter
openrouter_api_key=sk-or-your-openrouter-key-here
model=openai/gpt-oss-20b

# Optional Parameters
openrouter_base_url=https://openrouter.ai/api/v1
temperature=0.7
max_tokens=1000
timeout_seconds=30
```

### Available Models

Model names use vendor prefixes (`vendor/model-name`):

| Model | Description | Context |
|-------|-------------|---------|
| `openai/gpt-oss-20b` | Fast open-weight reasoning model (default) | 131K |
| `openai/gpt-oss-120b` | Larger open-weight reasoning model | 131K |
| `openai/gpt-6-luna` | OpenAI GPT-6 Luna via OpenRouter | 1.05M |
| `anthropic/claude-haiku-5.5` | Anthropic Claude Haiku 5.5 | — |
| `google/gemini-3.5-flash-lite` | Google Gemini Flash-Lite | — |
| `google/gemini-3.8-flash` | Google Gemini Flash | — |
| `meta-llama/llama-3.3-70b-instruct` | Meta Llama 3.3 70B | 128K |
| `deepseek/deepseek-v4.1-flash` | DeepSeek V4.1 Flash | — |
| `qwen/qwen3.8-flash` | Qwen 3.8 Flash | — |
| `openrouter/auto` | OpenRouter picks a model per request | — |

**Recommended for NetLogo**: `openai/gpt-oss-20b` (fast, cheap, capable)

Avoid `:free` model variants as a default; OpenRouter adds and removes them often.

### Why OpenRouter?

- **One API key** for 200+ models from OpenAI, Anthropic, Google, Meta, DeepSeek, and more
- **Automatic fallback**: if one provider is down, OpenRouter routes to another
- **Pay-per-use credits** across all providers
- **Easy model comparison**: switch between vendors without managing separate API keys

### Hyperparameters

Same as OpenAI: `temperature`, `max_tokens`, `top_p`. OpenRouter passes these through to the underlying provider.

### Thinking/Reasoning Models

OpenRouter supports reasoning across vendors. Set thinking mode and the extension
translates automatically:

```netlogo
llm:set-provider "openrouter"
llm:set-model "openai/gpt-oss-20b"
llm:set-thinking true
llm:set-reasoning-effort "high"
```

## Together AI Configuration

### API Setup

1. **Get API Key**: Visit [api.together.ai/settings/api-keys](https://api.together.ai/settings/api-keys)
2. **Check Usage**: Monitor at [api.together.ai/settings/billing](https://api.together.ai/settings/billing)
3. **Browse Models**: Explore at [api.together.ai/models](https://api.together.ai/models)

### Configuration Parameters

```ini
# Required Parameters
provider=together
together_api_key=your-together-key-here
model=Qwen/Qwen3.8-Flash

# Optional Parameters
together_base_url=https://api.together.xyz/v1
temperature=0.7
max_tokens=1000
timeout_seconds=30
```

### Available Models

Model names use vendor prefixes (`vendor/model-name`):

| Model | Description | Context |
|-------|-------------|---------|
| `Qwen/Qwen3.8-Flash` | Fast Qwen 3.8 (default) | — |
| `Qwen/Qwen3.5-9B` | Small, cheap Qwen | — |
| `Qwen/Qwen3.7-Plus` | Larger Qwen | — |
| `openai/gpt-oss-120b` | Open-weight reasoning model | 131K |
| `deepseek-ai/DeepSeek-V4.1-Flash` | DeepSeek V4.1 Flash | — |
| `zai-org/GLM-5.3-Flash` | GLM 5.3 Flash | — |
| `meta-models/Muse-Glimmer-30B` | Meta Muse Glimmer 30B | — |
| `moonshotai/Kimi-K3` | Kimi K3 | — |

**Recommended for NetLogo**: `Qwen/Qwen3.8-Flash` (fast, capable, good value: $0.15 in / $0.47 out per 1M tokens)

Together IDs are case-sensitive. No Llama model is left on Together serverless.

### Why Together AI?

- **Fast inference** on popular open-source models
- **Pay-per-token** pricing, often cheaper than proprietary APIs
- **Open-source models** — Qwen, DeepSeek, GLM, gpt-oss, Kimi
- **Reasoning support** — Qwen 3.8 and gpt-oss with thinking output

### Thinking/Reasoning Models

Together AI supports reasoning models like Qwen 3.8:

```netlogo
llm:set-provider "together"
llm:set-model "Qwen/Qwen3.8-Flash"
llm:set-thinking true
let result llm:chat-with-thinking "What is 15 * 17?"
; result is [answer thinking-text]
```

## Groq Configuration

### API Setup

1. **Get API Key**: Visit [console.groq.com/keys](https://console.groq.com/keys)
2. **Check Usage**: Monitor at [console.groq.com/settings/billing](https://console.groq.com/settings/billing)
3. **Browse Models**: Explore at [console.groq.com/docs/models](https://console.groq.com/docs/models)

### Configuration Parameters

```ini
# Required Parameters
provider=groq
groq_api_key=gsk_your-groq-key-here
model=openai/gpt-oss-20b

# Optional Parameters
groq_base_url=https://api.groq.com/openai/v1
temperature=0.7
max_tokens=1000
timeout_seconds=30
```

### Available Models

| Model | Description | Context |
|-------|-------------|---------|
| `openai/gpt-oss-20b` | Fast open-weight reasoning model | 131K |
| `openai/gpt-oss-120b` | Larger open-weight reasoning model | 131K |
| `qwen/qwen3.8-27b` | Qwen 3.8 reasoning model (preview) | 131K |
| `minimaxai/minimax-m2.7` | MiniMax M2.7 (preview) | 131K |
| `meta-models/muse-spark-open` | Meta Muse Spark (preview) | 131K |

Groq shut down `groq/compound*`, the Llama 3.x models (on free and developer
tiers) and `qwen/qwen3.6-27b` in 2026.

**Recommended for NetLogo**: `openai/gpt-oss-20b` (fast, cheap, generous free tier)

### Why Groq?

- **Very fast inference** — custom LPU hardware delivers high tokens/second
- **Generous free tier** — the best free option for workshops and classrooms
- **Open-weight models** — gpt-oss, Qwen, MiniMax
- **Reasoning support** — thinking text exposed via `llm:chat-with-thinking`

### Thinking/Reasoning Models

The gpt-oss models are reasoning models and return their thinking separately:

```netlogo
llm:set-provider "groq"
llm:set-model "openai/gpt-oss-20b"
llm:set-thinking true
llm:set-reasoning-effort "low"
let result llm:chat-with-thinking "What is 15 * 17?"
; result is [answer thinking-text]
```

`llm:set-reasoning-effort` accepts `low`, `medium`, and `high` for gpt-oss models.
Groq's model lineup changes frequently — check the
[deprecations page](https://console.groq.com/docs/deprecations) before relying on
a specific model in a long-lived model file.

## TypeSafe (Jev) Configuration

Jev, TypeSafe's System One model, does not write text. It answers a typed question
about a prompt (pick an option, rate on levels, or yes/no) with a calibrated
probability for every possible answer. Use it with `llm:decide`, `llm:decide-all`
and `llm:choose`. The free-text primitives (`llm:chat` and friends) raise
`typesafe (Jev) returns decisions, not text; use llm:decide, llm:decide-all or llm:choose`.

### API Setup

1. **Get API Key**: Visit [console.typesafe.ai](https://console.typesafe.ai/)
2. **Rate Limits**: View your organization's limits at [console.typesafe.ai/rate-limits](https://console.typesafe.ai/rate-limits)
3. **Docs**: [docs.typesafe.ai](https://docs.typesafe.ai)

### Configuration Parameters

```ini
# Required Parameters
provider=typesafe
typesafe_api_key=your-typesafe-key-here
model=jev-latest

# Optional Parameters
typesafe_base_url=https://api.typesafe.ai
timeout_seconds=30
```

`temperature`, `max_tokens` and the thinking settings do not apply to Jev and are ignored.

### Available Models

| Model | Description | Price |
|-------|-------------|-------|
| `jev-latest` | Alias for the newest stable release (the default) | $0.042 per million input tokens; output free |
| `jev-preview` | Alias for the newest release, stable or not | same |
| `jev-1.13.0` | Pinned version | same |
| `jev-1.14.0` | Pinned version | same |

An alias can move to a new version without notice; pin a version if you calibrated
thresholds against one. `llm:usage` reports `cost` for TypeSafe calls, computed from the
input tokens the API reports at the published price.

### How the primitives map to the API

Each call sends `POST /v1/systemone` with your prompt as `state` and one question:

| Spec | TypeSafe question | `answer` / `probability` |
|------|-------------------|--------------------------|
| `["choice" [options]]` | `choice` (1-255 options) | top option / its probability |
| `["score" [levels]]` | `score` (2-10 levels, 0-based) | expected level / confidence |
| `["yes-no"]` | `noul` | `true`/`false` / probability of that answer |

HTTP 429 and 529 (overloaded) are retried with backoff like every other provider; a
401 reports an invalid key and names `typesafe_api_key`.

```netlogo
llm:set-provider "typesafe"
llm:set-api-key "your-typesafe-key"
show llm:decide "Last week 70 of 100 went to the bar. Go tonight?" ["yes-no"]
; => [false 0.71 [[true 0.29] [false 0.71]]]
```

## Ollama (Local) Configuration

### Setup Requirements

1. **Install Ollama**: Download from [ollama.ai](https://ollama.ai/)
2. **Start Server**: Run `ollama serve` in terminal
3. **Pull Models**: Run `ollama pull [model-name]` for each model
4. **Check Status**: Visit `http://localhost:11434` in browser

### Configuration Parameters

```ini
# Required Parameters
provider=ollama
model=llama3.2

# Optional Parameters  
base_url=http://localhost:11434
temperature=0.7
max_tokens=2048
timeout_seconds=60
```

### Available Models

Must be pulled locally first with `ollama pull [model-name]`:

| Model | Size | Description | Pull Command |
|-------|------|-------------|--------------|
| `llama3.2` | 2.0GB | Latest Llama (recommended) | `ollama pull llama3.2` |
| `llama3.1` | 4.7GB | Previous Llama version | `ollama pull llama3.1` |
| `llama3` | 4.7GB | Llama 3 base | `ollama pull llama3` |
| `mistral` | 4.1GB | Mistral 7B | `ollama pull mistral` |
| `mixtral` | 26GB | Mixtral 8x7B (requires 16GB+ RAM) | `ollama pull mixtral` |
| `codellama` | 3.8GB | Code-specialized Llama | `ollama pull codellama` |
| `deepseek-r1:1.5b` | 1.0GB | Reasoning model | `ollama pull deepseek-r1:1.5b` |
| `phi3` | 2.3GB | Microsoft Phi-3 | `ollama pull phi3` |
| `gemma` | 5.0GB | Google Gemma | `ollama pull gemma` |
| `qwen2` | 4.4GB | Alibaba Qwen2 | `ollama pull qwen2` |

**Recommended for NetLogo**: `llama3.2` (good balance of capability and resource usage)

### Model Management Commands

```bash
# List available models
ollama list

# Pull a new model
ollama pull llama3.2

# Remove a model
ollama rm old-model-name

# Show model info
ollama show llama3.2

# Update all models
ollama pull --all
```

### Hyperparameters

#### temperature (float, 0.0-2.0, default: 0.7)
Controls randomness in responses.

```ini
temperature=0.1    # Very deterministic
temperature=0.7    # Balanced (recommended)
temperature=1.2    # More creative
```

#### max_tokens (integer, 1-4096, default: 2048)
Maximum response length.

```ini
max_tokens=512     # Short responses
max_tokens=2048    # Default
max_tokens=4000    # Longer responses (slower)
```

#### top_p (float, 0.0-1.0, default: 0.9)
Nucleus sampling parameter.

```ini
top_p=0.8          # More focused
top_p=0.9          # Default
top_p=1.0          # Maximum variety
```

#### top_k (integer, 1-100, default: 40)
Top-k sampling for response variety.

```ini
top_k=20           # More focused
top_k=40           # Default
top_k=80           # More variety
```

#### repeat_penalty (float, 1.0-2.0, default: 1.1)
Penalty for repeating tokens (Ollama-specific).

```ini
repeat_penalty=1.0 # No penalty
repeat_penalty=1.1 # Default penalty
repeat_penalty=1.3 # Higher penalty (less repetition)
```

### Performance Tuning

#### System Resources

```bash
# Check GPU usage (if available)
nvidia-smi

# Monitor CPU usage
htop

# Check memory usage
free -h
```

#### Model-Specific Settings

For **resource-constrained systems**:
```ini
model=deepseek-r1:1.5b  # Smaller model
max_tokens=1024         # Shorter responses
timeout_seconds=120     # Longer timeout
```

For **high-performance systems**:
```ini
model=mixtral           # Larger, more capable model
max_tokens=4096         # Longer responses
timeout_seconds=60      # Standard timeout
```

### Remote Ollama Setup

To use Ollama running on another machine:

```ini
provider=ollama
base_url=http://192.168.1.100:11434  # Remote IP
model=llama3.2
# No API key needed
```

## Multi-Provider Configuration

### Provider Switching

Use separate config files for easy switching:

```bash
# config-openai.txt
provider=openai
api_key=sk-your-openai-key
model=gpt-6-luna

# config-claude.txt  
provider=anthropic
api_key=sk-ant-your-claude-key
model=claude-haiku-5-5

# config-local.txt
provider=ollama
model=llama3.2
```

Load different configs as needed:
```netlogo
llm:load-config "config-openai.txt"    ; Use OpenAI
llm:load-config "config-claude.txt"    ; Switch to Claude
llm:load-config "config-local.txt"     ; Switch to local
```

### Fallback Configuration

For production systems, consider fallback providers:

```netlogo
to setup-with-fallback
  ; Try primary provider
  carefully [
    llm:load-config "config-primary.txt"
    llm:chat "Test message"
    print "Primary provider ready"
  ] [
    ; Fallback to secondary
    print "Primary failed, using fallback"
    llm:load-config "config-fallback.txt" 
  ]
end
```

## Cost Optimization

### Token Usage Estimation

| Provider | Input Cost/1K | Output Cost/1K | 100 Chat Turns |
|----------|---------------|----------------|-----------------|
| OpenAI (gpt-6-luna) | $0.0001 | $0.0005 | ~$0.06 |
| Claude (Haiku 5.5) | $0.0001 | $0.0005 | ~$0.06 |
| Gemini (Flash) | Free tier | Free tier | Free |
| Ollama | Free | Free | Free |

### Cost-Effective Settings

For **minimal costs**:
```ini
provider=gemini
model=gemini-3.5-flash-lite  # Free tier
max_tokens=512          # Shorter responses
temperature=0.3         # More focused
```

For **local processing**:
```ini
provider=ollama
model=deepseek-r1:1.5b  # Efficient model
max_tokens=1024         # Reasonable length
```

## Troubleshooting by Provider

### OpenAI Issues

**"Incorrect API key"**
- Verify key starts with `sk-`
- Check key is active at platform.openai.com
- Ensure sufficient credits

**"Rate limit exceeded"**
- Wait before retrying
- Upgrade to paid tier
- Implement request throttling

**"Model not found"**
- Check model name spelling
- Verify model access for your tier
- Use `gpt-6-luna` (the default) as fallback

### Claude Issues

**"Authentication failed"**
- Verify key starts with `sk-ant-`
- Check key is valid in Anthropic Console
- Ensure API access is enabled

**"Usage limit exceeded"**
- Check usage in Console
- Wait for limit reset
- Upgrade plan if needed

### Gemini Issues

**"API key invalid"**
- Verify key from AI Studio
- Check key permissions
- Try generating new key

**"Quota exceeded"**
- Check free tier limits
- Wait for quota reset
- Consider paid tier

### Ollama Issues

**"Connection refused"**
- Ensure `ollama serve` is running
- Check port 11434 is available
- Verify firewall settings

**"Model not found"**
- Run `ollama pull [model-name]`
- Check `ollama list` for available models
- Verify model name spelling

**"Out of memory"**
- Use smaller model (e.g., `deepseek-r1:1.5b`)
- Close other applications
- Increase system RAM if possible

## Best Practices

### Security
- Never hard-code API keys in NetLogo models
- Use config files with appropriate permissions
- Rotate API keys regularly
- Monitor usage for unexpected spikes

### Performance
- Use async primitives (`llm:chat-async`) for better responsiveness
- Implement appropriate timeouts
- Clear conversation history when context no longer needed
- Choose models appropriate for your use case

### Reliability
- Implement error handling with `carefully`
- Have fallback providers configured
- Monitor rate limits and quotas
- Log important interactions for debugging

### Cost Management
- Start with free tiers and smaller models
- Monitor usage regularly
- Use local models for development/testing
- Optimize prompts to reduce token usage
