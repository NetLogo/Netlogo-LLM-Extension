# NetLogo Multi-LLM Extension - API Reference

## Overview

The NetLogo Multi-LLM Extension provides a unified interface for multiple Large Language Model providers. All primitives are prefixed with `llm:`.

## Quick Command Reference

| Command                              | Category      | Description                                                |
| ------------------------------------ | ------------- | ---------------------------------------------------------- |
| `llm:chat text`                    | Chat          | Send synchronous chat message, returns response            |
| `llm:chat-async text`              | Chat          | Send asynchronous chat message, returns awaitable reporter |
| `llm:chat-with-template file vars` | Chat          | Send templated prompt with variable substitution           |
| `llm:chat-with-thinking text`      | Chat          | Returns `[answer thinking]` for reasoning-capable models   |
| `llm:choose prompt choices`        | Chat          | Force selection from provided options                      |
| `llm:chat-with-schema prompt schema` | Structured  | Schema-constrained chat, returns parsed nested lists       |
| `llm:chat-json prompt`             | Structured    | Force valid JSON output, returns the raw JSON string       |
| `llm:get parsed key`               | Structured    | Look up a key in a `[[key value] ...]` list                |
| `llm:compile-error code`           | Validation    | `""` if valid, else compiler error; optional disallowed list |
| `llm:set-thinking bool`            | Reasoning     | Enable/disable reasoning mode for current provider         |
| `llm:set-reasoning-effort level`   | Reasoning     | Set effort: `"low"`, `"medium"`, `"high"`                  |
| `llm:set-thinking-budget n`        | Reasoning     | Token budget for thinking (min 1024; Anthropic + Gemini)   |
| `llm:history`                      | History       | Get current agent's conversation history                   |
| `llm:set-history list`             | History       | Set conversation history for current agent                 |
| `llm:clear-history`                | History       | Clear conversation history for current agent               |
| `llm:load-config filename`         | Configuration | Load settings from file                                    |
| `llm:set-provider name`            | Configuration | Set active provider (openai, anthropic, gemini, ollama, openrouter, together) |
| `llm:set-api-key key`              | Configuration | Set API key for current provider                           |
| `llm:set-model name`               | Configuration | Set model to use for current provider                      |
| `llm:providers`                    | Discovery     | List ready providers with configured keys/servers          |
| `llm:providers-all`                | Discovery     | List all supported providers (ready or not)                |
| `llm:provider-status`              | Discovery     | Get detailed status of each provider                       |
| `llm:provider-help name`           | Discovery     | Get setup instructions for a provider                      |
| `llm:list-models`                  | Discovery     | List all available models for current provider             |
| `llm:active`                       | Discovery     | Get currently active provider and model                    |
| `llm:config`                       | Discovery     | Get configuration summary (keys masked for security)       |


## Configuration Primitives

### llm:load-config

**Syntax**: `llm:load-config filename`

**Description**: Loads configuration from a file (key=value format)

**Parameters**:

- `filename` (string): Path to configuration file

**Example**:

```netlogo
llm:load-config "config.txt"
llm:load-config "models/gpt4-config.txt"
```

**Notes**:

- File path is relative to NetLogo model location
- Overwrites any existing configuration
- Validates provider readiness after loading (throws error if provider not ready)
- Prefer config file approach over runtime commands when possible

### llm:set-provider

**Syntax**: `llm:set-provider provider-name`

**Description**: Sets the active LLM provider

**Parameters**:

- `provider-name` (string): Provider identifier

**Valid Providers**:

- `"openai"` - OpenAI GPT models
- `"anthropic"` - Anthropic Claude models
- `"gemini"` - Google Gemini models
- `"ollama"` - Local Ollama models
- `"openrouter"` - OpenRouter (200+ models from many vendors via one API key)
- `"together"` - Together AI (fast open-source model inference)

**Example**:

```netlogo
llm:set-provider "openai"
; Sets default model (gpt-4o-mini) and validates API key

llm:set-provider "ollama"
; Sets default model (llama3.2) and checks if Ollama server is reachable
```

**Notes**:

- Automatically applies provider defaults (model, base URL, etc.)
- Validates immediately: requires API key for cloud providers or reachable server for Ollama
- Throws helpful error with setup instructions if provider not ready
- Use `llm:provider-help` to get setup instructions if validation fails

### llm:set-api-key

**Syntax**: `llm:set-api-key api-key`

**Description**: Sets the API key for cloud providers

**Parameters**:

- `api-key` (string): API authentication key

**Example**:

```netlogo
llm:set-api-key "sk-your-openai-key-here"
llm:set-api-key "sk-ant-your-claude-key-here"
```

**Notes**:

- Stores key for the currently active provider (provider-specific key like `openai_api_key`)
- Not required for Ollama (local models)
- Keep API keys secure, avoid hard-coding in models
- Prefer setting keys in config file over runtime commands

### llm:set-model

**Syntax**: `llm:set-model model-name`

**Description**: Sets the specific model to use

**Parameters**:

- `model-name` (string): Model identifier

**Example**:

```netlogo
llm:set-model "gpt-4o-mini"
llm:set-model "claude-3-5-haiku-latest"
llm:set-model "llama3.2"
```

**Notes**:

- Validates model against current provider's supported models
- Throws error with model suggestions if model is invalid
- Use `llm:list-models` to see all available models across all providers

## Chat Primitives

### llm:chat

**Syntax**: `llm:chat message`

**Description**: Sends a message and returns the response (synchronous)

**Parameters**:

- `message` (string): The message to send

**Returns**: String - The LLM's response

**Example**:

```netlogo
let response llm:chat "What is 2+2?"
print response  ; "2+2 equals 4"

let creative-response llm:chat "Write a haiku about turtles"
print creative-response
```

**Notes**:

- Blocks execution until response received
- Maintains conversation history per agent
- Throws error if request fails

### llm:chat-async

**Syntax**: `llm:chat-async message`

**Description**: Sends a message and returns an awaitable reporter (asynchronous)

**Parameters**:

- `message` (string): The message to send

**Returns**: AwaitableReporter - Use with `runresult` to get response

**Example**:

```netlogo
; Start async request
let awaitable-response llm:chat-async "Explain quantum physics"

; Do other work while waiting
print "Processing other tasks..."
repeat 10 [ tick ]

; Get the result when ready
let response runresult awaitable-response
print response
```

**Notes**:

- Non-blocking - allows other code to run while waiting
- Use `runresult` to retrieve the actual response
- Still maintains conversation history per agent

### llm:choose

**Syntax**: `llm:choose prompt choices`

**Description**: Ask LLM to select from predefined options

**Parameters**:

- `prompt` (string): The question or context
- `choices` (list): List of valid options to choose from

**Returns**: String - One of the provided choices

**Example**:

```netlogo
let decision llm:choose "What should I do next?" ["move", "turn", "wait"]
print decision  ; Will be exactly "move", "turn", or "wait"

let color-choice llm:choose "Pick a good color for this turtle" 
                           ["red" "blue" "green" "yellow"]
set color read-from-string color-choice
```

**Notes**:

- Forces LLM to return exactly one of the provided choices
- Useful for agent decision-making in models
- Maintains conversation context
- Where the provider supports constrained decoding, the option list is sent as a
  schema constraint so the model cannot reply with anything else. Providers that
  do not support it fall back to the prompt wording, and the reply is still
  matched case-insensitively against the list — so behaviour is unchanged, just
  more reliable where the constraint is available.
- Still raises an error if the reply matches no option. Wrap in `carefully` if a
  model should tolerate that.

## Structured Output Primitives

These constrain the *shape* of a reply at the API level rather than by asking
nicely in the prompt. See [Structured Output](#structured-output-details) below
for the JSON-to-NetLogo mapping and provider support.

### llm:chat-with-schema

**Syntax**: `llm:chat-with-schema prompt schema`

**Description**: Sends a prompt with the reply constrained to a JSON Schema, and
reports the parsed result as nested NetLogo lists.

**Parameters**:

- `prompt` (string): The question or context
- `schema` (string): A JSON Schema object, as a string

**Returns**: List — the parsed JSON. Objects arrive as `[[key value] ...]` pairs;
use `llm:get` to read fields.

**Example**:

```netlogo
let schema "{\"type\":\"object\",\"properties\":{\"action\":{\"type\":\"string\"},\"confidence\":{\"type\":\"number\"}}}"
let result llm:chat-with-schema "What should the agent do?" schema
let action llm:get result "action"          ; "explore"
let conf   llm:get result "confidence"      ; 0.85
```

**Notes**:

- The schema's structure is checked before any request is sent — that it is a
  JSON object, that every level declares a supported `type`, that objects have
  non-empty `properties`, that arrays declare `items`, and that any `enum` is a
  non-empty array. A problem in any of those fails immediately with a message
  naming the offending path, rather than as an opaque provider error after a
  network round-trip.
- Other JSON Schema keywords are passed through to the provider unchecked. The
  four providers accept different dialects, so validating every keyword here
  would reject schemas a given provider would have honoured; a keyword one
  provider rejects still surfaces as that provider's error.
- Object schemas are normalized to strict mode (`additionalProperties: false`,
  every property listed in `required`) because OpenAI and Anthropic require it.
  The same normalization is applied for every provider so one schema behaves
  the same everywhere.
- If the reply is not valid JSON, this raises an error and conversation history
  is left unchanged — a failed call never records a bogus exchange.

### llm:chat-json

**Syntax**: `llm:chat-json prompt`

**Description**: Sends a prompt with the reply constrained to valid JSON, with no
schema, and reports the raw JSON string.

**Returns**: String — the JSON text

**Example**:

```netlogo
let json-str llm:chat-json "List three actions as a JSON array"
```

**Notes**:

- Use `llm:chat-with-schema` when the shape matters and you want NetLogo values
  back; use this when you want the JSON text itself.
- The reply is checked to be valid JSON before it is reported. Not every
  provider can enforce schemaless JSON natively — Anthropic has no such mode —
  so a model that replies with prose raises an error rather than returning it,
  and conversation history is left unchanged.
- Any JSON value is accepted, not just objects: an array or a scalar is still
  valid JSON. The text is reported exactly as received, not reformatted.

### llm:get

**Syntax**: `llm:get parsed key`

**Description**: Looks up a key in a list of `[key value]` pairs — the shape
`llm:chat-with-schema` reports.

**Parameters**:

- `parsed` (list): A list of `[key value]` pairs
- `key` (string): The key to find

**Returns**: The value at that key — a string, number, boolean, or nested list

**Example**:

```netlogo
let result llm:chat-with-schema "Describe a turtle" schema
let name llm:get result "name"
let city llm:get (llm:get result "address") "city"   ; nested lookup
```

**Notes**:

- Matching is exact and case-sensitive, because JSON keys are.
- A missing key raises an error listing the available keys. It does not report a
  blank — a silent `""` would be indistinguishable from a JSON null and would let
  a typo travel through a run as data.

### llm:chat-with-template

**Syntax**: `llm:chat-with-template template-file variables`

**Description**: Sends a prompt built from a YAML template file, substituting variables supplied from NetLogo. Keeps prompt wording out of the model code, so the phrasing can be changed and version-controlled without touching NetLogo source.

**Parameters**:

- `template-file` (string): Path to a YAML template
- `variables` (list): List of `[key value]` pairs substituted into the template

**Returns**: String — the model's response

**The template file**:

```yaml
system: "You are a cautious forager deciding where to move next."
template: |
  You are at patch {xcor}, {ycor}.
  Nearby food: {food-count}
  Nearby predators: {predator-count}

  Reply with exactly one of: MOVE, STAY, FLEE
```

`template` is required. `system` is optional — omit it and no system message is sent.

**Using it from NetLogo**:

```netlogo
ask turtles [
  let decision llm:chat-with-template "forager.yaml" (list
    (list "xcor" xcor)
    (list "ycor" ycor)
    (list "food-count" count food-here)
    (list "predator-count" count predators in-radius 3))

  if decision = "FLEE" [ rt 180 fd 2 ]
]
```

Each `{name}` in the template is replaced by the value paired with `"name"`. Values are converted to strings, so numbers and booleans can be passed directly.

**Notes**:

- **File lookup order**: the directory of the open model first, then the path as given, then the current working directory. Keeping the template beside the `.nlogox` is the reliable option — a bare working-directory path breaks when the model is opened from elsewhere.
- A placeholder with no matching variable is left in the prompt as literal text (`{food-count}`), rather than raising. Worth checking the wording if a response looks confused.
- The system message is prepended for this call only; it is not written into the agent's history.
- The rendered prompt and the response are both committed to the agent's history on success, so `llm:chat` afterwards continues the same conversation. Nothing is committed if the call fails.
- A missing file or a template without a `template:` field raises an extension error naming the file.

**Example templates** ship in `demos/templates/` — `simple-template.yaml`, `reasoning-template.yaml`, `analysis-template.yaml`, `code-evolution-template.yaml`, and `movement-evolution.yaml`.

## Code Validation

### llm:compile-error

**Syntax**: `llm:compile-error code-string`
&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;`(llm:compile-error code-string disallowed-list)`

**Description**: Checks whether a string of NetLogo commands compiles, without running it. Intended for validating LLM-generated code before `run`. With the optional second argument, also rejects code that uses any listed primitive.

**Returns**: String — `""` if the code is acceptable, otherwise a message explaining why not.

**Example**:

```netlogo
let new-rule llm:chat "Write NetLogo commands to make this turtle seek food."

ifelse llm:compile-error new-rule = ""
  [ run new-rule ]
  [ print (word "Rejected: " llm:compile-error new-rule) ]
```

With a disallowed list — note the parentheses, required when passing the optional argument:

```netlogo
let banned ["die" "clear-all" "ask" "hatch"]

ifelse (llm:compile-error new-rule banned) = ""
  [ run new-rule ]
  [ print (llm:compile-error new-rule banned) ]
```

Typical error strings:

```
Nothing named WEIGHT has been defined. (offset 76)
FD expected 1 input, a number. (offset 73)
Expected a TRUE/FALSE here, rather than a list or block. (offset 30)
Disallowed primitive(s) used: die, clear-all
```

**Notes**:

- Uses NetLogo's own compiler — the same one `run` uses. If this returns a non-empty
  string, `run` would have failed with that error.
- Compiles in **turtle context**, because generated agent rules are normally executed
  inside `ask turtles`. Turtle-only primitives such as `fd` and `rt` are therefore valid.
- The **live model's symbol table is in scope** — `turtles-own` variables, globals, and
  breeds defined by the running model all resolve. An external validator cannot do this,
  which is why undefined-variable errors are the most common failure it catches.
- Empty or whitespace-only input reports `""` (valid): empty commands are legal NetLogo.
- **Compiling is not the same as running safely.** Runtime errors — for example
  `item 3` on a three-element list — surface only during execution. Keep `carefully`
  around `run`.

**Multi-line code**:

The whole string is compiled as one command block, so newlines, indentation and
comments are all fine, and `let` variables defined on one line are in scope on later
lines:

```netlogo
llm:compile-error "let x 5\nlet y x * 2\nrt y\nfd 1"   ;=> ""
```

Because it is a single block, ordinary scoping rules apply and their violations are
reported:

```netlogo
llm:compile-error "fd x\nlet x 5"
;=> "Nothing named X has been defined. (offset 3)"     - used before definition

llm:compile-error "let x 5\nlet x 6\nfd x"
;=> "There is already a local variable here called X (offset 43)"
```

**The disallowed list**:

- Checked only if the code compiles. Code that fails to compile cannot run, so its
  syntax error is reported instead.
- Matching is on **exact tokens**, using NetLogo's own tokenizer. Banning `"die"`
  rejects `die` but not a variable named `diehard`, not `die` inside a comment, and
  not the string `"die"`. Substring matching would reject all three, and a false
  positive discards code that was valid.
- Case-insensitive on both sides: `"DIE"` in the list matches `die` in the code.
- The list is entirely caller-supplied. The extension ships no built-in set, because
  what counts as dangerous is model-specific — a model may legitimately need `hatch`
  while forbidding it in generated agent rules.

**Enforcing your own policy**: this primitive checks syntax only. Domain rules stay in
NetLogo, where you can change them without rebuilding the extension:

```netlogo
to-report acceptable? [ code ]
  if llm:compile-error code != "" [ report false ]
  if length code > 500 [ report false ]
  report true
end
```

## Reasoning / Thinking Primitives

For models that expose intermediate reasoning (Anthropic Claude, Google Gemini, Ollama qwen3/deepseek-r1, OpenRouter, Together AI). OpenAI o-series uses internal reasoning that the API does not return.

### llm:chat-with-thinking

**Syntax**: `llm:chat-with-thinking text`

**Description**: Same as `llm:chat`, but returns both the final answer and the model's reasoning text as a 2-element list.

**Returns**: `[answer thinking]` — both strings. `thinking` will be `""` if the provider/model does not expose reasoning tokens.

**Example**:

```netlogo
llm:set-thinking true
let result llm:chat-with-thinking "What is 17 * 23?"
let answer   item 0 result
let thinking item 1 result
print (word "Answer: " answer)
print (word "Reasoning: " thinking)
```

**Notes**:

- Only the final answer is added to conversation history (not the thinking text).
- For DeepSeek-R1 on Together AI, thinking is parsed from `<think>...</think>` tags in the content.
- For Anthropic, OpenRouter, and Gemini, thinking comes from a dedicated reasoning field in the API response.

### llm:set-thinking

**Syntax**: `llm:set-thinking enabled?`

**Description**: Enable or disable reasoning mode for the current provider. When enabled, the request includes provider-specific reasoning fields.

**Parameters**:

- `enabled?` (boolean): `true` to enable, `false` to disable

### llm:set-reasoning-effort

**Syntax**: `llm:set-reasoning-effort level`

**Description**: Set the reasoning effort hint for models that support it (OpenAI o-series, OpenRouter, Together AI hybrid models).

**Parameters**:

- `level` (string): `"low"`, `"medium"`, or `"high"`

### llm:set-thinking-budget

**Syntax**: `llm:set-thinking-budget tokens`

**Description**: Maximum tokens the model may spend on reasoning before producing the final answer. Used by Anthropic and Gemini.

**Parameters**:

- `tokens` (number): Minimum 1024. For Anthropic, the value is clamped to `[1024, max_tokens-1]`.

## History Management

### llm:history

**Syntax**: `llm:history`

**Description**: Returns the conversation history for current agent

**Returns**: List - Conversation history as alternating user/assistant messages

**Example**:

```netlogo
llm:chat "Hello"
llm:chat "How are you?"
let history llm:history
print history
; ["Hello" "Hello! How can I help you?" "How are you?" "I'm doing well, thanks!"]
```

### llm:set-history

**Syntax**: `llm:set-history message-list`

**Description**: Sets the conversation history for current agent

**Parameters**:

- `message-list` (list): List of messages (alternating user/assistant)

**Example**:

```netlogo
; Set up a conversation context
llm:set-history ["You are a helpful turtle" "I understand, I'm a helpful turtle"]
let response llm:chat "What are you?"
print response  ; "I'm a helpful turtle, ready to assist you!"
```

**Notes**:

- Messages should alternate between user and assistant
- Overwrites existing history for this agent
- Use to prime conversations with context

### llm:clear-history

**Syntax**: `llm:clear-history`

**Description**: Clears conversation history for current agent

**Example**:

```netlogo
llm:chat "Hello"
print length llm:history  ; 2 (user + assistant message)
llm:clear-history
print length llm:history  ; 0
```

## Provider Information

### llm:providers

**Syntax**: `llm:providers`

**Description**: Returns list of READY providers (those with API keys or reachable servers)

**Returns**: List - Provider names that are ready to use

**Example**:

```netlogo
let ready-providers llm:providers
print ready-providers  ; ["openai" "ollama"] - only providers with keys/reachable


; Check if specific provider is ready
if member? "ollama" llm:providers [
  print "Ollama is ready for use"
]
```

**Notes**:

- Only lists providers that have API keys configured (OpenAI, Anthropic, Gemini) or are reachable (Ollama)
- Use `llm:providers-all` to see all supported providers regardless of readiness
- Use `llm:provider-status` for detailed status of each provider

**Readiness Checks**:

| Provider    | Check Performed                                       |
| ----------- | ----------------------------------------------------- |
| OpenAI      | Has `openai_api_key` or `api_key` configured          |
| Anthropic   | Has `anthropic_api_key` or `api_key` configured       |
| Gemini      | Has `gemini_api_key` or `api_key` configured          |
| Ollama      | Server reachable at `ollama_base_url` (1s timeout)    |
| OpenRouter  | Has `openrouter_api_key` configured                   |
| Together AI | Has `together_api_key` configured                     |

### llm:providers-all

**Syntax**: `llm:providers-all`

**Description**: Returns list of all supported providers (regardless of readiness)

**Returns**: List - All supported provider names

**Example**:

```netlogo
let all-providers llm:providers-all
print all-providers  ; ["openai" "anthropic" "gemini" "ollama" "openrouter" "together"]
```

### llm:provider-status

**Syntax**: `llm:provider-status`

**Description**: Returns detailed status information for all providers. This is a diagnostic tool that shows the current configuration state of each provider without attempting to use them.

**Returns**: List - Nested lists with provider status details

**Example**:

```netlogo
let status llm:provider-status
print status
; Output format:
; [["openai" ["ready" true] ["has-key" true]]
;  ["anthropic" ["ready" false] ["has-key" false]]
;  ["gemini" ["ready" false] ["has-key" false]]
;  ["ollama" ["ready" true] ["reachable" true] ["base-url" "http://localhost:11434"]]
;  ["openrouter" ["ready" false] ["has-key" false]]
;  ["together" ["ready" false] ["has-key" false]]]

; Check specific provider status
foreach llm:provider-status [ provider-info ->
  let provider-name item 0 provider-info
  if provider-name = "ollama" [
    print provider-info
  ]
]
```

**Status Fields by Provider**:

| Provider  | Fields Returned                                  |
| --------- | ------------------------------------------------ |
| OpenAI    | `ready` (bool), `has-key` (bool)                 |
| Anthropic | `ready` (bool), `has-key` (bool)                 |
| Gemini    | `ready` (bool), `has-key` (bool)                 |
| Ollama    | `ready` (bool), `reachable` (bool), `base-url`   |

**Notes**:

- For cloud providers (OpenAI, Anthropic, Gemini): shows `ready` and `has-key` status
- For Ollama: shows `ready`, `reachable`, and `base-url`
- Use this to diagnose configuration issues
- The `ready` field indicates whether the provider can be used immediately

### llm:provider-help

**Syntax**: `llm:provider-help provider-name`

**Description**: Returns setup instructions for a specific provider. The help text is built into the extension and covers the essential setup steps for each provider.

**Parameters**:

- `provider-name` (string): Provider to get help for (`openai`, `anthropic`, `gemini`, `ollama`, `openrouter`, `together`)

**Returns**: String - Multi-line setup instructions

**Example**:

```netlogo
; Get Ollama setup instructions
print llm:provider-help "ollama"

; Get OpenAI setup instructions
print llm:provider-help "openai"
```

**Help Content by Provider**:

| Provider  | Help Includes                                                    |
| --------- | ---------------------------------------------------------------- |
| OpenAI    | API key setup, console URL, config file instructions             |
| Anthropic | API key setup, console URL, config file instructions             |
| Gemini    | API key setup, MakerSuite URL, config file instructions          |
| Ollama    | Installation, server startup, model pulling, custom server URLs  |

**Notes**:

- Provides step-by-step setup instructions
- Includes installation, configuration, and verification steps
- Useful when a provider is not ready
- Returns "Unknown provider" message for unsupported provider names

### llm:active

**Syntax**: `llm:active`

**Description**: Returns the currently active provider and model

**Returns**: List - [provider model]

**Example**:

```netlogo
let current llm:active
print current  ; ["openai" "gpt-4o-mini"]
print (word "Using " item 0 current " with " item 1 current)
```

**Notes**:

- Use this to verify your current configuration
- Helpful for sanity checks before sending chat requests

### llm:config

**Syntax**: `llm:config`

**Description**: Returns a summary of the current configuration (with masked API keys)

**Returns**: String - Configuration summary

**Example**:

```netlogo
print llm:config
; Output: provider=openai, model=gpt-4o-mini, openai_api_key=sk-pr...jYzQ, ...
```

**Notes**:

- API keys are masked for security (shows first 4 and last 4 characters)
- Useful for debugging configuration issues

### llm:list-models

**Syntax**: `llm:list-models`

**Description**: Returns a formatted string showing available models for ALL providers

**Returns**: String - Formatted multi-line string with provider sections, model lists, and status indicators

**Format**:

- Shows all providers grouped with headers
- Marks active provider and model with `[ACTIVE]`
- Marks custom models from `models-override.yaml` with `[custom]`
- Each provider section shows all available models for that provider

**Example**:

```netlogo
print llm:list-models

; Output format:
; === OpenAI Models ===
; gpt-4o [ACTIVE]
; gpt-4o-mini
; gpt-4-turbo
; gpt-3.5-turbo
; o1-preview
; o1-mini
;
; === Anthropic Models ===
; claude-3-5-sonnet-20241022
; claude-3-5-haiku-latest
; claude-3-opus-latest
;
; === Gemini Models === [ACTIVE]
; gemini-2.0-flash-exp [custom] [ACTIVE]
; gemini-1.5-pro
; gemini-1.5-flash
;
; === Ollama Models ===
; llama3.2
; mistral
; qwen2

; Use it to see what models are available
llm:set-provider "anthropic"
print llm:list-models  ; Shows all providers, with Anthropic marked as ACTIVE
```

**Notes**:

- Returns a STRING (not a list) with formatted output
- Shows ALL providers in a single view, not just the active one
- Use this to discover available models across all providers
- Custom models added via `models-override.yaml` are marked with `[custom]`
- The currently active provider and model are marked with `[ACTIVE]`

## Structured Output Details

### Why JSON becomes nested lists

NetLogo's type system has strings, numbers, booleans, lists, and agents — there
is no map or dictionary type. A JSON object therefore arrives as a list of
`[key value]` pairs, which is the association-list shape NetLogo modelers
already use and which `llm:get` can search.

| JSON        | NetLogo                     | Example                        |
| ----------- | --------------------------- | ------------------------------ |
| Object `{}` | List of `[key value]` pairs | `[["name" "Alice"] ["age" 30]]` |
| Array `[]`  | List                        | `["a" "b" "c"]`                |
| String      | String                      | `"hello"`                      |
| Number      | Number                      | `42`, `3.14`                   |
| Boolean     | Boolean                     | `true`, `false`                |
| Null        | Empty string                | `""`                           |

Null maps to `""` because NetLogo has no null, and `""` is a value a model can
compare against without a runtime error.

### Provider support

Each provider expresses the same constraint in its own request field:

| Provider                            | Field                                  | Schemaless JSON mode        |
| ----------------------------------- | -------------------------------------- | --------------------------- |
| OpenAI, Groq, Together, OpenRouter   | `response_format.json_schema.schema`   | `response_format.json_object` |
| Anthropic (Claude)                  | `output_config.format.schema`          | Prompt instruction only     |
| Gemini                              | `generationConfig.responseJsonSchema`  | `responseMimeType` only     |
| Ollama                              | `format`                               | `format: "json"`            |

Notes on the two asymmetries:

- **Anthropic has no schemaless JSON mode.** For `llm:chat-json`, no
  `output_config.format` is sent — inventing one would be rejected by the API —
  so the JSON instruction is carried in the prompt instead. `llm:chat-with-schema`
  uses the native schema field.
- **Claude's `output_config` is shared.** Thinking depth (`effort`) and response
  format (`format`) are siblings under one key, so both are merged into the same
  object. Setting a schema does not disturb an existing `effort`, and vice versa.

Whether a *particular model* honours the constraint is decided by the provider,
not this extension. Where a model ignores it, the reply is still returned and
`llm:choose` still matches it against the option list — nothing fails merely
because constrained decoding was unavailable.

### Scope of this release

`llm:set-response-format` and `llm:clear-response-format` — a persistent schema
applied to ordinary `llm:chat` calls — are **not** included. Two reasons:

1. `llm:chat` declares a `StringType` return in its syntax. A persistent schema
   would make it report JSON-as-a-string with no signal at the type level, so
   model code could not tell which shape it was about to get.
2. The format would live in global config while conversation history is
   per-agent, so a schema set for one agent would silently apply to every other
   agent's calls mid-run.

The one-shot primitives (`llm:chat-with-schema`, `llm:chat-json`) express the
same capability without either problem: the constraint and the return type are
visible at the call site. If a persistent form is added later, per-agent scoping
and a distinct return type are the open design questions.

Generalizing `llm:choose` to non-string results (numbers, coordinates, ordered
plans) is likewise not a new set of primitives here — those are schema shapes for
`llm:chat-with-schema`. For example, a multi-step plan:

```netlogo
let schema "{\"type\":\"object\",\"properties\":{\"steps\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}}"
let plan llm:get (llm:chat-with-schema "Plan three moves" schema) "steps"
foreach plan [ s -> print s ]
```

If the steps are meant to be executed as NetLogo code rather than printed, check
each one with `llm:compile-error` first and keep `carefully` around the `run` —
a schema constrains the *shape* of a reply, not whether its contents are safe or
runnable.

## Usage Patterns

### Basic Chat Bot

```netlogo
extensions [llm]

to setup
  llm:load-config "config.txt"
end

to chat-with-user
  let user-input user-input "What would you like to ask?"
  let response llm:chat user-input
  user-message response "LLM Response"
end
```

### Multi-Agent Conversations

```netlogo
extensions [llm]

turtles-own [personality]

to setup
  create-turtles 3
  llm:load-config "config.txt"
  
  ask turtles [
    set personality one-of ["helpful" "curious" "creative"]
    ; Each turtle gets its own conversation history
    llm:set-history (list (word "You are a " personality " assistant") 
                          (word "I understand, I'm " personality))
  ]
end

to converse
  ask turtles [
    let question "What's something interesting about science?"
    let response llm:chat question
    print (word "Turtle " who " (" personality "): " response)
  ]
end
```

### Async Processing

```netlogo
extensions [llm]

globals [pending-requests]

to setup
  set pending-requests []
  llm:load-config "config.txt"
end

to start-async-requests
  let questions ["What is AI?" "Explain gravity" "How do plants grow?"]
  
  foreach questions [ question ->
    let awaitable llm:chat-async question
    set pending-requests lput (list question awaitable) pending-requests
  ]
end

to check-responses
  let completed []
  let still-pending []
  
  foreach pending-requests [ request ->
    let question first request
    let awaitable last request
  
    ; Try to get response (non-blocking check would be ideal)
    carefully [
      let response runresult awaitable
      print (word "Q: " question " A: " response)
      set completed lput request completed
    ] [
      ; Still pending
      set still-pending lput request still-pending
    ]
  ]
  
  set pending-requests still-pending
  print (word "Completed: " length completed " Still pending: " length still-pending)
end
```

### Decision Making

```netlogo
extensions [llm]

turtles-own [current-action]

to setup
  create-turtles 10
  llm:load-config "config.txt"
end

to make-decisions
  ask turtles [
    let context (word "I'm a turtle at position " xcor "," ycor ". ")
    set context (word context "There are " count other turtles in-radius 3 " nearby turtles.")
  
    let action llm:choose (word context " What should I do?")
                         ["move-forward" "turn-left" "turn-right" "stop"]
  
    set current-action action
    execute-action action
  ]
end

to execute-action [action]
  if action = "move-forward" [ forward 1 ]
  if action = "turn-left" [ left 90 ]
  if action = "turn-right" [ right 90 ]
  if action = "stop" [ ]
end
```

## Error Handling

### Common Errors

**Configuration Errors**:

- `Provider not found` - Check provider name spelling
- `API key missing` - Set API key for cloud providers
- `Model not available` - Verify model exists and is accessible

**Request Errors**:

- `Request timeout` - Check network, increase timeout_seconds
- `Rate limited` - Wait before retrying, check API quotas
- `Invalid response` - Check model parameters, try different prompt

**Example Error Handling**:

```netlogo
to safe-chat [message]
  carefully [
    let response llm:chat message
    print response
  ] [
    print (word "Error: " error-message)
    print "Check your configuration and try again"
  ]
end
```

## Performance Tips

1. **Use Configuration Files**: Load settings once rather than setting each parameter
2. **Leverage Async**: Use `llm:chat-async` for multiple concurrent requests
3. **Manage History**: Clear history when context is no longer needed
4. **Choose Right Models**: Balance capability vs speed/cost for your use case
5. **Handle Errors**: Implement retry logic for production applications

## Advanced Configuration

### Custom Base URLs

For enterprise or custom deployments:

```
provider=openai
base_url=https://your-custom-openai-endpoint.com/v1
api_key=your-key
model=gpt-4o-mini
```

### Timeout Tuning

Adjust timeouts based on model and request complexity:

```
# Fast models, simple requests
timeout_seconds=15

# Complex reasoning, large responses  
timeout_seconds=120
```

### Temperature Settings

Control response randomness:

```
# Deterministic, factual responses
temperature=0.0

# Creative, varied responses  
temperature=1.0

# Balanced (recommended)
temperature=0.7
```
