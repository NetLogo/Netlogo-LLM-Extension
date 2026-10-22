# NetLogo LLM Extension — Usage Guide

This guide explains how to use the `llm` extension in NetLogo models: setup, configuration, provider options, and the core primitives.

## Prerequisites
- NetLogo 7.0.0-beta1 or newer.
- Built extension JAR (see Build in repository `AGENTS.md`). Demos in `demos/` can be opened directly.

## Installing the Extension for a Model
You can use the extension locally per model, or install it globally.

- Per‑model (recommended for experiments):
  1. Build: `sbt assembly` (produces `target/LLM-Extension.jar`).
  2. Create a folder named `llm` next to your `.nlogo` file.
  3. Copy `target/LLM-Extension.jar` into that `llm/` folder.
  4. In your model, add: `extensions [ llm ]`.
- Global install:
  - Copy the JAR to your NetLogo installation under `NetLogo/extensions/llm/`, then use `extensions [ llm ]` in any model.

Tip: Open any demo in `demos/` to see working examples and panel layouts.

## Configuring Providers
Use inline commands or a config file. A config file keeps secrets out of your model and is easier to share.

- Inline (OpenAI example):
```
extensions [ llm ]
llm:set-provider "openai"
llm:set-api-key "sk-REPLACE_ME"
llm:set-model "gpt-6-luna"
```
- Config file (recommended):
  - Create a `config.txt` using the key=value format and load it:
```
extensions [ llm ]
llm:load-config "config.txt"
```
  - See full instructions and templates in `docs/CONFIGURATION.md` (OpenAI, Anthropic, Gemini, Ollama, OpenRouter, Together AI).

### Using Ollama (no API key)
- Start Ollama and pull a model (see quick start in `docs/CONFIGURATION.md`).
- Example config:
```
provider=ollama
model=llama3.2
base_url=http://localhost:11434
```
- Load and chat:
```
extensions [ llm ]
llm:load-config "config-ollama.txt"
show llm:chat "List two agent-based modeling use cases."
```

## Core Primitives and Examples
All primitives are under the `llm:` namespace.

- Configuration
  - `llm:set-provider "openai|anthropic|gemini|ollama|openrouter|together"`
  - `llm:set-api-key "..."`
  - `llm:set-model "model-name"`
  - `llm:load-config "path/to/config.txt"`

- Chat (synchronous)
```
show llm:chat "Explain agent-based modeling in one line."
```

- Chat (asynchronous)
```
let r llm:chat-async "Summarize my world state"
; other work here
show runresult r
```
The async call starts immediately; `runresult` blocks only when you need the value.

- Chat with a template (YAML + variables)
YAML file (e.g., `demos/simple-template.yaml`):
```
system: You are concise.
template: |
  Summarize the following in one sentence:
  {text}
```
NetLogo call:
```
let vars (list (list "text" "NetLogo agents interact on patches."))
show llm:chat-with-template "demos/simple-template.yaml" vars
```

- Constrained choice (force selection from options)
```
show llm:choose "Pick a color" ["red" "green" "blue"]
```
Returns exactly one of the provided options (best-effort; includes robust fallback matching).

- History (per agent)
```
show llm:history
llm:set-history (list (list "system" "Be terse."))
llm:clear-history
```
Each agent (observer/turtles/patches) maintains its own conversation state.

- Discovery
```
show llm:providers      ;; ready providers (with key/server)
show llm:providers-all  ;; all 6 supported providers
show llm:provider-status ;; detailed readiness per provider
show llm:provider-help "openrouter" ;; setup instructions for any provider
show llm:list-models    ;; models supported by current provider
show llm:active         ;; [provider model] currently active
```

- Reasoning / thinking models
```
llm:set-thinking true                 ;; turn on reasoning for models that support it
llm:set-reasoning-effort "high"       ;; "low" | "medium" | "high"
llm:set-thinking-budget 4096          ;; min 1024, used by Anthropic + Gemini
let r llm:chat-with-thinking "What is 17 * 23?"
print item 0 r  ;; final answer
print item 1 r  ;; reasoning text (empty for providers that hide thinking, e.g. OpenAI)
```
Thinking is exposed by Anthropic, Gemini, Ollama, OpenRouter, and Together AI.
OpenAI o-series uses internal reasoning that the API does not return.

## Common Patterns
- Per‑turtle conversations
```
to turtles-chat
  ask turtles [
    let greeting (word "Hello from turtle " who)
    show llm:chat greeting
  ]
end
```
- Periodic async checks
```
; launch async
if not any? turtles with [member? "pending" labels] [
  ask turtles [ set label "pending" set myTask llm:chat-async "summarize" ]
]
; resolve later
ask turtles with [label = "pending"] [
  let result runresult myTask
  set label result
]
```

## Error Handling & Timeouts
- Set `timeout_seconds` in your config to avoid indefinite waits.
- Missing `api_key` or unsupported `model` will raise a runtime error with a clear message.
- For Ollama, ensure the server is running and `base_url` is correct.

## Troubleshooting
- Verify setup:
```
show llm:providers
show llm:list-models
show llm:chat "Say hello in five words."
```
- For Ollama:
  - `ollama list` shows installed models.
  - `curl http://localhost:11434/api/tags` checks the server.
- Reduce flakiness: keep prompts deterministic for tests/experiments.

## Security Tips
- Do not embed real API keys in `.nlogo` files. Use `llm:load-config` with an untracked `config.txt`.
- Use `demos/config-reference.txt` as a safe template.

## Demos
Explore the `demos/` folder for complete, runnable examples (basic chat, templates, choice, and multi‑provider setups).
