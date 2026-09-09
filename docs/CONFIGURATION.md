# LLM Extension Configuration Guide

This guide explains how to create, save, and use a configuration file for the NetLogo LLM extension.

## Configuration Precedence
The extension follows this order of precedence for configuration:

1. **Runtime Commands** (highest priority) - `llm:set-provider`, `llm:set-api-key`, `llm:set-model`, etc.
2. **Config File** - Settings loaded via `llm:load-config`
3. **Built-in Defaults** (lowest priority) - Applied only if no other config exists

This allows you to:
- Use config file for persistent settings (recommended approach)
- Override specific settings at runtime when needed
- Switch between configurations during experimentation

## Immediate Validation
Starting from this version, configuration is validated immediately:
- When you call `llm:load-config` or `llm:set-provider`, the extension checks if the provider is ready
- **Cloud providers** (OpenAI, Anthropic, Gemini, OpenRouter, Together AI) require API keys
- **Ollama** requires the server to be running and reachable
- If validation fails, you get a clear error message with setup instructions
- Use `print llm:provider-help "provider-name"` to get detailed setup guidance

## Format
- Plain text, UTF-8 encoded, one `key=value` per line.
- Empty lines are ignored; lines starting with `#` are comments.
- No quotes required; avoid trailing spaces around `=`.
- **Supported keys**:
  - Common: `provider`, `model`, `temperature`, `max_tokens`, `timeout_seconds`
  - Rate limiting: `retry_max_retries`, `retry_max_elapsed_seconds`, `max_concurrent_requests`, `min_request_interval_ms`
  - Provider-specific API keys: `openai_api_key`, `anthropic_api_key`, `gemini_api_key`, `openrouter_api_key`, `together_api_key`
  - Provider-specific base URLs: `openai_base_url`, `anthropic_base_url`, `gemini_base_url`, `ollama_base_url`, `openrouter_base_url`, `together_base_url`
  - Legacy (still supported): `api_key`, `base_url` (applies to current provider)

## Where to Save the File
- Recommended: save the file next to your `.nlogo` model (e.g., `config.txt`).
- Alternatively: save anywhere and pass a relative or absolute path.
- Resolution: the loader checks the path you pass, then the current working directory.

## How to Create It
1. Open a text editor (VS Code, Notepad, TextEdit in plain-text mode).
2. Paste one of the examples below and adjust values.
3. Save as `config.txt` (or `config-openai.txt`, etc.). Keep it out of version control if it contains secrets.

## Provider Examples

### OpenAI
```
provider=openai
openai_api_key=sk-REPLACE_ME
model=gpt-4o-mini
temperature=0.7
max_tokens=1000
timeout_seconds=30
```

### Anthropic (Claude)
```
provider=anthropic
anthropic_api_key=sk-ant-REPLACE_ME
model=claude-3-5-sonnet-20241022
temperature=0.7
max_tokens=4000
timeout_seconds=30
```

### Google (Gemini)
```
provider=gemini
gemini_api_key=REPLACE_ME
model=gemini-1.5-flash
gemini_base_url=https://generativelanguage.googleapis.com/v1beta
temperature=0.7
max_tokens=2048
timeout_seconds=30
```

### OpenRouter (200+ models, one API key)
```
provider=openrouter
openrouter_api_key=sk-or-REPLACE_ME
model=openai/gpt-4o-mini
temperature=0.7
max_tokens=1000
timeout_seconds=30
```

### Together AI (open-source models)
```
provider=together
together_api_key=REPLACE_ME
model=meta-llama/Llama-3.3-70B-Instruct-Turbo
temperature=0.7
max_tokens=1000
timeout_seconds=30
```

### Local (Ollama, no API key)
```
provider=ollama
model=llama3.2
ollama_base_url=http://localhost:11434
temperature=0.7
max_tokens=2048
timeout_seconds=30
```

### Multi-Provider Configuration
You can configure multiple providers at once using provider-specific keys:
```
# Configure all providers in one file
openai_api_key=sk-REPLACE_ME
anthropic_api_key=sk-ant-REPLACE_ME
gemini_api_key=REPLACE_ME
openrouter_api_key=sk-or-REPLACE_ME
together_api_key=REPLACE_ME

# Set the active provider
provider=openai
model=gpt-4o-mini

# Switch providers at runtime:
# llm:set-provider "anthropic"
# llm:set-model "claude-3-5-sonnet-20241022"
```

### Profiles: one config file per model, different agents on different models

`llm:load-config` sets the one configuration every agent shares. To run several
models in one simulation, load extra config files as named profiles and bind
agents to them. Each profile is an ordinary config file with its own provider,
key and model:

```
llm:load-config "config.txt"                    ;; default for everyone
llm:load-profile "llama"  "config-groq.txt"
llm:load-profile "sonnet" "config-anthropic.txt"
ask turtles with [role = "scout"]  [ llm:use-profile "llama" ]
ask turtles with [role = "leader"] [ llm:use-profile "sonnet" ]
```

A profile file is validated on load the same way as `config.txt`. Thinking,
retry and throttling keys in the file apply to that profile. See the API
reference for `llm:load-profile`, `llm:use-profile`, `llm:profile` and
`llm:profiles`.

### Request Throttling (staying inside a rate limit)
A model that calls the LLM once per agent per tick sends one request per agent
simultaneously. On a free tier that exceeds the quota on the first tick.

```
# At most 4 requests in flight; start them at least 250ms apart
max_concurrent_requests=4
min_request_interval_ms=250
```

- `max_concurrent_requests` — whole number of simultaneous requests. Unset or `0` means
  **disabled** (unbounded, the default). Negative or unparseable values are reported on
  stderr and treated as disabled.
- `min_request_interval_ms` — **milliseconds** between request starts. Unset or `0`
  means **disabled**. Use this for a requests-per-minute quota; a concurrency cap alone
  limits simultaneity, not rate.

Excess requests queue rather than fail, occupy no thread while waiting, and are admitted
in arrival order. The cap applies per provider and endpoint, using the provider-specific
base URL key (`openai_base_url`, `gemini_base_url`, and so on) — the same value used to
build the request — so different providers never share a cap. Spellings of one endpoint
that differ only by trailing slash or letter case count as the same endpoint.

Queue time is not added to `timeout_seconds`, so a cap set well below your agent
population may require raising `timeout_seconds` too.

See [SETUP.md](SETUP.md#request-throttling) for how to pick values.

## Ollama Quick Start (No API Key)
Use Ollama to run models locally without any cloud credentials.

1) Install Ollama
- Download and install: https://ollama.com/download
- Docs/README: https://github.com/ollama/ollama

2) Start the server
- macOS/Windows apps usually start the background server automatically.
- CLI (any OS):
```
ollama serve
```
- Linux service (if installed as a service):
```
sudo systemctl enable --now ollama
```

3) Choose and download a model
- Browse models: https://ollama.com/library
- Pull a model (examples):
```
ollama pull llama3.2   
# or
ollama pull mistral
ollama pull qwen2
```

4) Verify installed models
```
ollama list
```

5) Quick local test
```
ollama run llama3.2
> Write a haiku about NetLogo.
```

6) Verify the server is reachable
```
curl http://localhost:11434/api/tags
```

7) Configure this extension for Ollama
- In your config file (see example above), set:
  - `provider=ollama`
  - `model=<one from ollama list>` (e.g., `llama3.2`, `mistral`)
  - `base_url=http://localhost:11434`
- Then in NetLogo:
```
extensions [ llm ]
llm:load-config "config-ollama.txt"
show llm:chat "Name two agent-based modeling use cases."
```

Notes
- The extension's `llm:list-models` shows all supported models across providers; `ollama list` shows what is actually installed locally.
- Some models are large; ensure sufficient RAM/VRAM. Close other apps if startup fails.
- Default port is `11434`; adjust `base_url` if you run on a different host/port.

## Loading in NetLogo
Add the extension and load your config file:
```
extensions [ llm ]
llm:load-config "config.txt"        ;; or a path like "configs/openai.txt"
```
You can override values at runtime:
```
llm:set-model "gpt-4o"
```

## Verifying & Troubleshooting
- Verify provider and models:
```
show llm:providers
print llm:list-models
```
- Test a simple call:
```
show llm:chat "Say hello in five words."
```
- If you see missing key errors, ensure `api_key` is set for cloud providers and your `model` is supported.

## Security Tips
- Do not commit secrets. Keep your config in `.gitignore`.
- Use `demos/config-reference.txt` as a safe template and keep your real `config.txt` local.

## Custom Model Registry (models-override.yaml)

You can add custom or newly released models without waiting for an extension update by creating a `models-override.yaml` file.

### Location
Save the file in the same directory as your `.nlogo` model file:
```
my-model.nlogo
models-override.yaml   <-- Place it here
config.txt
```

### Format
The file uses YAML format with provider sections. Each provider section completely replaces the bundled models for that provider:

```yaml
openai:
  - gpt-4o
  - gpt-4o-mini
  - gpt-4-turbo
  - o1-preview
  - o1-mini
  - gpt-4o-2024-11-20  # Add new model

anthropic:
  - claude-3-5-sonnet-20241022
  - claude-3-5-haiku-latest
  - claude-3-opus-latest
  - claude-3-7-sonnet-20250219  # Add new model

gemini:
  - gemini-2.0-flash-exp  # Override to use only newest model
  - gemini-1.5-pro
  - gemini-1.5-flash

ollama:
  - llama3.2
  - mistral
  - qwen2
  - deepseek-r1:latest  # Add locally installed model
```

### Behavior
- **Complete replacement**: Each provider section you include completely replaces the bundled models for that provider
- **Partial override**: You can override just one provider and leave others unchanged
- **Custom marker**: Models from the override file are marked with `[custom]` in `llm:list-models` output
- **Validation**: The extension still validates that the model you select exists in the combined registry

### Use Cases
1. **New model releases**: Add newly announced models before the extension is updated
   ```yaml
   openai:
     - gpt-4o
     - gpt-4o-mini
     - gpt-5  # Hypothetical future model
   ```

2. **Local Ollama models**: Include models you've installed locally
   ```yaml
   ollama:
     - llama3.2
     - my-custom-finetune:latest
     - codellama:13b
   ```

3. **Simplify model list**: Reduce clutter by listing only the models you use
   ```yaml
   anthropic:
     - claude-3-5-sonnet-20241022  # Only show the model I use
   ```

### Example
1. Create `models-override.yaml` next to your model:
   ```yaml
   gemini:
     - gemini-2.0-flash-exp
     - gemini-1.5-pro
   ```

2. Load config and check available models:
   ```netlogo
   extensions [ llm ]
   llm:load-config "config.txt"
   print llm:list-models

   ; Output shows:
   ; === Gemini Models ===
   ; gemini-2.0-flash-exp [custom]
   ; gemini-1.5-pro [custom]
   ```

3. Set a custom model:
   ```netlogo
   llm:set-provider "gemini"
   llm:set-model "gemini-2.0-flash-exp"  ; Uses custom model
   ```

### Notes
- The override file is optional - if not present, bundled models are used
- Invalid YAML syntax will cause an error when loading config
- The file is loaded automatically when you call `llm:load-config`
- You must include ALL models you want for a provider - no merging with bundled list
