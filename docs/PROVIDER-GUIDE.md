# NetLogo Multi-LLM Extension - Provider Configuration Guide

## Overview

This guide provides comprehensive configuration details for all supported LLM providers, including hyperparameters, model options, and provider-specific settings.

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
model=gpt-4o-mini

# Optional Parameters  
base_url=https://api.openai.com/v1
temperature=0.7
max_tokens=1000
timeout_seconds=30
```

### Available Models

| Model | Description | Context | Cost (per 1K tokens) |
|-------|-------------|---------|---------------------|
| `gpt-4o-mini` | Fast, cost-effective GPT-4 | 128K | Input: $0.00015, Output: $0.0006 |
| `gpt-4o` | Most capable GPT-4 variant | 128K | Input: $0.005, Output: $0.015 |
| `gpt-4-turbo` | Fast GPT-4 with large context | 128K | Input: $0.01, Output: $0.03 |
| `gpt-4` | Original GPT-4 | 8K | Input: $0.03, Output: $0.06 |
| `gpt-3.5-turbo` | Legacy but capable | 16K | Input: $0.0005, Output: $0.0015 |

**Recommended for NetLogo**: `gpt-4o-mini` (best balance of speed, capability, and cost)

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
model=gpt-4o-mini
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
model=claude-3-haiku-20240307

# Optional Parameters
base_url=https://api.anthropic.com/v1
temperature=0.5
max_tokens=4000
timeout_seconds=30
```

### Available Models

| Model | Description | Context | Cost (per 1K tokens) |
|-------|-------------|---------|---------------------|
| `claude-3-haiku-20240307` | Fast, cost-effective | 200K | Input: $0.00025, Output: $0.00125 |
| `claude-3-sonnet-20240229` | Balanced performance | 200K | Input: $0.003, Output: $0.015 |
| `claude-3-opus-20240229` | Most capable Claude | 200K | Input: $0.015, Output: $0.075 |
| `claude-3-5-sonnet-20241022` | Latest improved Sonnet | 200K | Input: $0.003, Output: $0.015 |

**Recommended for NetLogo**: `claude-3-haiku-20240307` (excellent speed and capability for most use cases)

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
model=gemini-1.5-flash

# Optional Parameters
base_url=https://generativelanguage.googleapis.com/v1beta
temperature=0.8
max_tokens=2048
timeout_seconds=30
```

### Available Models

| Model | Description | Context | Cost (per 1K tokens) |
|-------|-------------|---------|---------------------|
| `gemini-1.5-flash` | Fast, free tier available | 1M | Free tier available |
| `gemini-1.5-pro` | Most capable Gemini | 2M | Input: $0.00125, Output: $0.005 |
| `gemini-1.0-pro` | Original Gemini Pro | 32K | Input: $0.0005, Output: $0.0015 |
| `gemini-pro` | Alias for gemini-1.0-pro | 32K | Input: $0.0005, Output: $0.0015 |

**Recommended for NetLogo**: `gemini-1.5-flash` (excellent free tier, very fast)

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
model=openai/gpt-4o-mini

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
| `openai/gpt-4o` | OpenAI GPT-4o via OpenRouter | 128K |
| `openai/gpt-4o-mini` | Fast, cost-effective GPT-4 | 128K |
| `openai/o3-mini` | OpenAI reasoning model | 128K |
| `anthropic/claude-3.5-sonnet` | Anthropic Claude 3.5 Sonnet | 200K |
| `anthropic/claude-sonnet-4-20250514` | Latest Claude Sonnet 4 | 200K |
| `google/gemini-2.5-flash` | Google Gemini Flash | 1M |
| `google/gemini-2.5-pro` | Google Gemini Pro | 1M |
| `meta-llama/llama-3.3-70b-instruct` | Meta Llama 3.3 70B | 128K |
| `deepseek/deepseek-r1` | DeepSeek reasoning model | 64K |
| `deepseek/deepseek-chat-v3-0324` | DeepSeek Chat V3 | 64K |

**Recommended for NetLogo**: `openai/gpt-4o-mini` (fast, cheap, capable)

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
llm:set-model "deepseek/deepseek-r1"
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
model=meta-llama/Llama-3.3-70B-Instruct-Turbo

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
| `meta-llama/Llama-3.3-70B-Instruct-Turbo` | Fast Llama 3.3 70B | 128K |
| `meta-llama/Meta-Llama-3.1-405B-Instruct-Turbo` | Largest Llama | 128K |
| `meta-llama/Meta-Llama-3.1-8B-Instruct-Turbo` | Small, fast Llama | 128K |
| `deepseek-ai/DeepSeek-R1` | DeepSeek reasoning model | 64K |
| `deepseek-ai/DeepSeek-V3` | DeepSeek V3 chat | 64K |
| `Qwen/Qwen2.5-72B-Instruct-Turbo` | Qwen 2.5 72B | 128K |
| `google/gemma-3-27b-it` | Google Gemma 3 | 128K |
| `mistralai/Mistral-Small-24B-Instruct-2501` | Mistral Small | 32K |

**Recommended for NetLogo**: `meta-llama/Llama-3.3-70B-Instruct-Turbo` (fast, capable, good value)

### Why Together AI?

- **Fast inference** on popular open-source models
- **Pay-per-token** pricing, often cheaper than proprietary APIs
- **Open-source models** — Llama, DeepSeek, Qwen, Gemma, Mistral
- **Reasoning support** — DeepSeek-R1 with thinking output

### Thinking/Reasoning Models

Together AI supports reasoning models like DeepSeek-R1:

```netlogo
llm:set-provider "together"
llm:set-model "deepseek-ai/DeepSeek-R1"
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
| `openai/gpt-oss-safeguard-20b` | Safety-tuned gpt-oss variant | 131K |
| `groq/compound` | Agentic system with built-in tool use | 131K |
| `groq/compound-mini` | Smaller agentic system | 131K |
| `qwen/qwen3.6-27b` | Qwen 3.6 reasoning model (preview) | 131K |
| `minimaxai/minimax-m2.7` | MiniMax M2.7 (preview) | 131K |

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
model=gpt-4o-mini

# config-claude.txt  
provider=anthropic
api_key=sk-ant-your-claude-key
model=claude-3-haiku-20240307

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
| OpenAI (gpt-4o-mini) | $0.00015 | $0.0006 | ~$0.08 |
| Claude (Haiku) | $0.00025 | $0.00125 | ~$0.15 |
| Gemini (Flash) | Free tier | Free tier | Free |
| Ollama | Free | Free | Free |

### Cost-Effective Settings

For **minimal costs**:
```ini
provider=gemini
model=gemini-1.5-flash  # Free tier
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
- Use `gpt-4o-mini` as fallback

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
