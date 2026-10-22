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
model=gpt-6-luna
temperature=0.7
max_tokens=1000
timeout_seconds=30
```

### Anthropic (Claude)
```
provider=anthropic
anthropic_api_key=sk-ant-REPLACE_ME
model=claude-haiku-5-5
temperature=0.7
max_tokens=4000
timeout_seconds=30
```

### Google (Gemini)
```
provider=gemini
gemini_api_key=REPLACE_ME
model=gemini-3.5-flash-lite
gemini_base_url=https://generativelanguage.googleapis.com/v1beta
temperature=0.7
max_tokens=2048
timeout_seconds=30
```

### OpenRouter (200+ models, one API key)
```
provider=openrouter
openrouter_api_key=sk-or-REPLACE_ME
model=openai/gpt-oss-20b
temperature=0.7
max_tokens=1000
timeout_seconds=30
```

### Together AI (open-source models)
```
provider=together
together_api_key=REPLACE_ME
model=Qwen/Qwen3.8-Flash
temperature=0.7
max_tokens=1000
timeout_seconds=30
```

### TypeSafe (Jev, decisions with probabilities)
Jev answers typed questions instead of writing text: use it with `llm:decide`,
`llm:decide-all` and `llm:choose` (the chat primitives raise an error on it).
`temperature` and `max_tokens` do not apply.
```
provider=typesafe
typesafe_api_key=REPLACE_ME
model=jev-latest
timeout_seconds=30
# typesafe_base_url=https://api.typesafe.ai
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
typesafe_api_key=REPLACE_ME

# Set the active provider
provider=openai
model=gpt-6-luna

# Switch providers at runtime:
# llm:set-provider "anthropic"
# llm:set-model "claude-sonnet-5-5"
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
ollama pull qwen3.5:4b
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
llm:set-model "gpt-6.1-sol"
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

## Model Catalog (models.yaml, user catalog, models-override.yaml)

The list of known models and each provider's default model live in YAML, not in
the extension's code. When a model ID goes stale you edit a YAML file; no
rebuild is needed.

Three layers are read, each on top of the one before:

1. **Bundled catalog** — `models.yaml` inside the extension JAR.
2. **User catalog** — `~/.netlogo-llm/models.yaml`, read once when the extension
   starts. It applies to every model you open. To use a different path, set the
   `llm.models.user.file` Java system property or the `NETLOGO_LLM_MODELS`
   environment variable.
3. **Model-directory override** — `models-override.yaml` next to your `.nlogo`
   file, read by `llm:load-config`.

### Format
Each provider is either a plain list of model IDs, or a mapping with a
`default:` and a `models:` list (either may be left out, but not both):

```yaml
openai:
  default: gpt-6-luna
  models:
    - gpt-6-luna
    - gpt-6.1-sol

ollama:
  - llama3.2
  - my-custom-finetune:latest
```

`default:` is the model a provider uses when neither your config file
(`model=`) nor `llm:set-model` names one. Those two still take precedence. When
no layer sets `default:` for a provider, the extension's built-in default for
that provider is used.

### User catalog (~/.netlogo-llm/models.yaml)
The user catalog **adds** to the bundled catalog rather than replacing it:
- models you list are added to that provider's bundled list
- a `default:` replaces the bundled default, and that model is added to the
  provider's list even if you do not list it under `models:`, so it is never
  reported as unknown
- a provider the bundled catalog does not have is added

```yaml
# ~/.netlogo-llm/models.yaml
openai:
  default: gpt-6.1-sol        # make this the OpenAI default everywhere
gemini:
  - gemini-4-argon-preview    # a model released after this extension version
```

If the file cannot be parsed, the extension prints a warning naming the file and
carries on with the bundled catalog. A missing file is fine.

### Model-directory override (models-override.yaml)
Save the file in the same directory as your `.nlogo` model file:
```
my-model.nlogo
models-override.yaml   <-- Place it here
config.txt
```

Each provider section you include **completely replaces** that provider's list
from the layers below (bundled + user catalog):

```yaml
openai:
  - gpt-6-luna
  - gpt-6.1-sol
  - gpt-6-astra  # Add new model

anthropic:
  default: claude-sonnet-5-5
  models:
    - claude-haiku-5-5
    - claude-sonnet-5-5

ollama:
  - llama3.2
  - mistral
  - deepseek-r1:latest  # Add locally installed model
```

### Behavior
- **Complete replacement**: Each provider section you include completely replaces the models for that provider
- **Partial override**: You can override just one provider and leave others unchanged
- **Defaults carry over**: a section without `default:` keeps the default from the layers below
- **Default is a known model**: a section's `default:` is added to that section's model list
- **Custom marker**: Models from the override file are marked with `[custom]` in `llm:list-models` output
- **Validation**: `llm:set-model` warns (but still proceeds) when a model is not in the combined catalog

### Use Cases
1. **New model releases**: Add newly announced models before the extension is updated
   ```yaml
   openai:
     - gpt-6-luna
     - gpt-7-preview  # Hypothetical future model
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
     - claude-haiku-5-5  # Only show the model I use
   ```

### Example
1. Create `models-override.yaml` next to your model:
   ```yaml
   gemini:
     - gemini-3.8-flash
     - gemini-3.1-pro-preview
   ```

2. Load config and check available models:
   ```netlogo
   extensions [ llm ]
   llm:load-config "config.txt"
   print llm:list-models

   ; Output shows:
   ; --- gemini ---
   ;   gemini-3.1-pro-preview [reasoning] [custom]
   ;   gemini-3.8-flash [reasoning] [custom]
   ```

3. Set a custom model:
   ```netlogo
   llm:set-provider "gemini"
   llm:set-model "gemini-3.8-flash"  ; Uses custom model
   ```

### Notes
- The override file is optional - if not present, the bundled and user catalogs are used
- Invalid YAML prints a warning and the override is ignored
- The file is loaded automatically when you call `llm:load-config`
- You must include ALL models you want for a provider - no merging with the lower layers' list (use the user catalog to add instead)
- If the bundled catalog itself cannot be read, each provider falls back to its built-in default model only
