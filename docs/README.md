# NetLogo Multi-LLM Extension

Unified LLM capabilities for NetLogo with multi-provider support — OpenAI, Anthropic/Claude, Google/Gemini, local Ollama, OpenRouter (hundreds of models via one key), Together AI, and Groq — plus TypeSafe's Jev decision model for fast, cheap, calibrated agent decisions. Per‑agent memory, async and batched requests, structured outputs, reasoning/thinking models, token and cost tracking, and simple configuration.

## Quick Links
- Usage Guide: `docs/USAGE.md`
- Configuration Guide: `docs/CONFIGURATION.md`
- API Reference: `docs/API-REFERENCE.md`
- Provider Guide: `docs/PROVIDER-GUIDE.md`
- Setup Guide: `docs/SETUP.md`
- Testing Guide: `docs/TESTING.md`
- Demos: `demos/` (flagship: `demos/el-farol-decisions/`)

## Quick Start
1) Add the extension in your model:
```
extensions [ llm ]
```
2) Configure a provider (recommended via file):
```
llm:load-config "config.txt"
```
See `docs/CONFIGURATION.md` for ready-to-copy examples for every provider. For inline setup:
```
llm:set-provider "openai"
llm:set-api-key "sk-REPLACE_ME"
llm:set-model "gpt-6-luna"
```
3) Chat, or decide:
```
show llm:chat "Say hello in five words."
show llm:decide "Go out tonight?" ["yes-no"]   ;; with provider "typesafe"
```

## Core Features
- Multi-provider with a single set of primitives.
- Per-agent conversation history and `llm:clear-history`.
- Synchronous `llm:chat`, asynchronous `llm:chat-async` (+ `runresult`), and batched `llm:chat-all`.
- Prompt templates with `llm:chat-with-template` (YAML + variables).
- Constrained decisions with `llm:choose`; structured output with `llm:chat-with-schema`, `llm:chat-json` and `llm:get`.
- Typed decisions with probabilities: `llm:decide` and `llm:decide-all` (choice, score, yes-no) on decision providers such as TypeSafe (Jev).
- Named profiles so different agents use different models (`llm:load-profile`, `llm:use-profile`).
- Request throttling, retries on rate limits, server errors and timeouts, and token/cost/latency tracking (`llm:usage`, `llm:usage-total`).
- Model lists and defaults come from YAML (`models.yaml`, plus an optional `~/.netlogo-llm/models.yaml`), so stale model IDs need no rebuild.

## Install
- From NetLogo: **Tools → Extensions → LLM** (Extension Manager).
- From source: see `BUILD.md` and `INSTALL.md` (JDK 21+, NetLogo 7.0.4).
- The `demos/` folder contains working models you can open immediately.

## Notes
- Keep API keys out of `.nlogox` files; prefer `llm:load-config` with a gitignored `config.txt`.
- For local, no-API usage, see the Ollama quick start in `docs/CONFIGURATION.md`.

## For maintainers

**Layout.** `src/main/LLMExtension.scala` registers every `llm:` primitive and owns
per-agent history and usage (both guarded by a run-generation counter so calls launched
before `clear-all` cannot write into a new run). `src/main/providers/` holds one class per
provider on top of `BaseHttpProvider` (HTTP, retries, throttle, friendly errors) and
`OpenAICompatibleProvider`; each provider is declared once in `ProviderRegistrations.scala`.
`src/main/config/` loads config files, profiles and the model catalog. `src/main/models/`
holds the request/response, structured-output and decision types.

**Adding a chat provider.** Add a descriptor in `ProviderRegistrations.scala`, a provider
class (usually a small `OpenAICompatibleProvider` subclass, see `GroqProvider.scala`), and a
block in `src/main/resources/config/models.yaml` with `default:` and `models:`.

**Adding a decision backend.** Implement `DecisionProvider` (`src/main/providers/DecisionProvider.scala`)
next to `BaseHttpProvider`, as `TypeSafeProvider.scala` does. `llm:decide`, `llm:decide-all`
and `llm:choose` pick it up without other changes.

**Model catalog.** Provider model IDs go stale on the provider's schedule. Check each
provider's deprecation page, edit `models.yaml`, and run `sbt test`
(`ModelCatalogSpec` checks every default is listed).

**Tests.** `sbt test` is deterministic (no network): `tests.txt` runs NetLogo code through
`DeterministicTestProvider`, and the Scala specs stub HTTP. Live checks live in
`demos/e2e-tests/e2e-tests.nlogox` (T1–T12), run headless as described in `docs/TESTING.md`.

**Releasing.** See `RELEASE.md`.

**Decided not to build** (closed issues, with reasons on each): response caching (#54),
record/replay (#55), BehaviorSpace logging (#56, already possible with `llm:usage`),
memory consolidation (#47), observer-level channels and shared blackboards (#57, #58),
streaming (#59), and API keys from environment variables (#50).

## License

This project is licensed under the BSD 3-Clause License - see the [LICENSE](../LICENSE) file for details.
