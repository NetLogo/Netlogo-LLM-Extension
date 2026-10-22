# Testing Guide

## Overview

The project uses two test layers:

1. Automated deterministic tests (`sbt test`)
2. Manual live-provider integration tests (`demos/tests/tests.nlogox`)

Use both, but for different goals.

## Automated Tests (Default)

Run:

```bash
sbt test
```

These tests:
- run in headless NetLogo using `TestLanguage`
- execute `tests.txt`
- require no API keys and no network access
- are deterministic and CI-friendly

### How API-free testing works

- `src/test/Tests.scala` installs a provider factory override before tests.
- The override injects `src/test/DeterministicTestProvider.scala`.
- That provider returns predictable in-memory responses instead of calling external APIs.
- After test execution, the override is removed.

### Core behavior covered

- Configuration primitives (`set-provider`, `set-api-key`, `load-config`, `active`, `config`)
- Provider metadata primitives (`providers`, `providers-all`, `provider-status`, `provider-help`, `list-models`)
- History primitives (`history`, `set-history`, `clear-history`)
- Chat flow (`chat`, `chat-async`, `runresult`)
- Choice flow (`choose`)
- Template flow (`chat-with-template`, variable substitution, history behavior)
- Thinking/reasoning primitives (`set-thinking`, `set-reasoning-effort`, `set-thinking-budget`, `chat-with-thinking`)
- Per-agent history isolation

### Not covered by automated tests

- Real provider authentication validity
- Network timeouts/rate limits/provider outages
- Upstream API contract changes
- Model quality/correctness of generated content
- Actual thinking/reasoning output content from live models

## Ollama Integration Tests (Local)

Run against a real local Ollama server to verify actual LLM responses:

```bash
OLLAMA_TESTS=true sbt test
```

Or run only the Ollama tests:

```bash
OLLAMA_TESTS=true sbt "testOnly org.nlogo.extensions.llm.OllamaIntegrationTests"
```

Prerequisites:
- Ollama installed and running (`ollama serve`)
- Models pulled: `ollama pull qwen3:0.6b` and `ollama pull llama3.2:3b`

These tests verify:
- Real chat responses from Ollama
- Async chat with actual model inference
- Thinking/reasoning output from qwen3 (a thinking-capable model)
- Thinking budget configuration with live model
- Multi-agent chat with separate turtle contexts
- Template-based chat with variable substitution

These tests are **excluded from default `sbt test`** and from CI.

## Manual Integration Tests (Live APIs)

Use `demos/tests/tests.nlogox` when you want to verify real providers end-to-end.

These tests do require:
- valid provider credentials for cloud providers (OpenAI, Anthropic, Gemini, OpenRouter, Together AI), or
- a running Ollama server for local provider tests

### Test setup

1. Copy the template: `cp demos/tests/config.txt.example demos/tests/config.txt`
2. Edit `config.txt`: set `provider=` to your chosen provider and replace the matching `*_api_key=REPLACE_ME` line with a real key. `config.txt` is gitignored, so the key stays local.
3. Open `demos/tests/tests.nlogox` in NetLogo.
4. In the Command Center: `run-all-tests`

### What the suite covers

The suite runs the same 13 test procedures regardless of provider, plus two provider-specific procedures that auto-skip if not applicable:

- `test-providers` — registry has all 6 providers and `provider-help` returns text for each
- `test-invalid-provider` — bogus provider name is rejected
- `test-load-config` / `test-config-rollback` — config file loads cleanly and rolls back on failure
- `test-sync-chat`, `test-async-chat`, `test-choose`, `test-history` — core chat flow
- `test-thinking-config`, `test-chat-with-thinking` — reasoning primitives and `[answer thinking]` return shape
- `test-openrouter-vendor-prefix` — vendor-prefixed model names (skips unless `provider=openrouter`)
- `test-together-thinking` — DeepSeek `<think>` tag extraction (skips unless `provider=together` AND model contains "DeepSeek")
- `test-reasoning-marker` — `[reasoning]` marker visible in `llm:list-models`

To exercise everything, run the suite twice — once with `provider=openrouter`, once with `provider=together` (use `model=deepseek-ai/DeepSeek-V4.1-Flash` and `max_tokens=2000+` for the Together reasoning test).

Suggested usage:
- run automated tests first (`sbt test`)
- run manual integration checks before releases or when changing provider adapters

## Git Automation

### CI on every push and pull request

The repository includes `.github/workflows/ci.yml`.

It runs `sbt test` automatically on:
- every push
- every pull request

Recommended repository setting:
- enable branch protection and require the `Core Tests` check before merge

### Optional local push gate

The repository also includes `.githooks/pre-push` to run `sbt test` before each push.

Enable repository hooks once:

```bash
git config core.hooksPath .githooks
chmod +x .githooks/pre-commit .githooks/pre-push
```

Temporarily skip local push-time tests:

```bash
SKIP_LOCAL_TESTS=1 git push
```

## Future Live Smoke Tests (API-based)

For cloud-provider smoke tests you will need real API keys in CI secrets.

Typical setup later:
- separate workflow (manual trigger and/or nightly schedule)
- secrets like `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`, `OPENROUTER_API_KEY`, `TOGETHER_API_KEY`
- not required for normal PR merges (to avoid flaky/costly gating)
