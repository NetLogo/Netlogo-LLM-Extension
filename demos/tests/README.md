# Manual Integration Tests (Live Providers)

This directory contains the NetLogo model used for manual, live-provider integration checks.

## Purpose

Use this suite to validate external integration behavior:
- real API credentials
- network connectivity
- provider endpoint compatibility
- provider-specific runtime issues (timeouts, auth failures, model availability)

This suite is not part of default `sbt test`.

## Files

- `tests.nlogox` - Manual test model (provider registry, config, primitive coverage)
- `reliability-tests.nlogox` - Reliability regression tests against a live provider (see below)
- `config.txt.example` - Template with placeholders for all 6 providers
- `config.txt` - Local config used by the model (gitignored, create from template)

## Reliability Tests

`reliability-tests.nlogox` covers the three reliability fixes in PR #44 against a
real provider - the behavior the deterministic `sbt test` suite cannot reach,
because it uses a stub provider that never returns a genuine HTTP 429.

| Issue | Checked by |
|-------|-----------|
| #33 - failed calls leave orphaned prompts in history | `test-history-unchanged-on-failure`, `test-thinking-history-unchanged-on-failure` |
| #32 - async calls can reorder or race per-agent history | `test-async-overlapping-atomic`, `test-mixed-sync-async-atomic` |
| #37 - rate-limit errors halt the run instead of retrying | `test-rate-limit-retry` |

The history tests assert two invariants that both bugs break: history length is
always even, and roles strictly alternate `user` / `assistant`.

**Running them:**

- **Run history tests** - low quota use, works on any provider including Ollama.
- **Rate limit (10)** / **Rate limit (50)** - needs a rate-limited free tier
  (Gemini or Groq) to actually produce 429s. Ollama will not trigger them.
  Raise the burst size until the quota trips; the run should still complete.

## Prerequisites

1. Build and install the extension
2. Create a local config from the template:
   ```
   cp config.txt.example config.txt
   ```
   Then edit `config.txt`, set `provider=` to your chosen provider, and replace the matching `*_api_key=REPLACE_ME` line with a real key. Or run Ollama locally for a no-key option.
3. Ensure network access for cloud providers

## How To Run

1. Open `tests.nlogox` in NetLogo
2. The model loads `config.txt`
3. Run test procedures from the Command Center/buttons

## Relationship To Automated Tests

- Automated tests (`sbt test`) are deterministic and API-free.
- This model is for manual integration verification against real providers.

Run both for release confidence:
1. `sbt test`
2. `demos/tests/tests.nlogox` manual checks
