# Demo 2: Crisis Triage with Ambiguous Incidents

A municipal emergency operations center where LLM-powered dispatchers assess ambiguous crisis reports — demonstrating that keyword matching fails when incidents are deliberately misleading, but LLMs reading full impact descriptions can succeed.

Target runtime: NetLogo 7.0.3 (`.nlogox` model format).

## The Story

Three dispatchers — Veteran, Rookie, and Analyst — receive a stream of crisis incidents. Each must assess severity and route to the right response tier. The incident bank includes **misleading cases** where surface keywords don't match reality:

- "Toxic chemical spill at school" → actually spilled vinegar (LOW severity)
- "Minor water leak in basement" → threatening a neonatal ICU (CRITICAL severity)
- "Dog loose on highway" → causing a multi-vehicle pileup (HIGH severity)

A naive keyword heuristic over-triggers on "toxic", "fire", "collapse" and fails on these cases. The LLM reads the full impact description and can assess correctly.

## Quick Start

1. Edit `config.txt` with your provider credentials (default: local Ollama).
2. Open `crisis-triage.nlogox` in NetLogo 7.0.3.
3. Click **setup** → dispatchers appear with persona labels, responders by tier.
4. Click **go** → incidents spawn, flow through the pipeline, monitors update.
5. Watch the output log for `[TRIAGE]`, `[ROUTE]`, and `[REFLECT]` messages.

## How to Use

### Controls

| Control | Type | Purpose |
|---------|------|---------|
| `use-llm?` | Switch | Toggle between LLM dispatchers and naive heuristic |
| `memory-mode` | Chooser | persistent / per-episode / none |
| `reflection-interval` | Slider | Ticks between dispatcher self-reflection (0 = off) |
| `incident-rate` | Slider | Probability (%) of new incident per tick |
| `episode-length` | Slider | Ticks per episode boundary (0 = no episodes) |
| `add incident` | Button | Manually inject a random incident |
| `force reflect` | Button | Trigger immediate reflection for all dispatchers |

### What to Observe

- **Misleading%** — The key metric. Accuracy on misleading incidents where keywords don't match reality.
- **Triage Acc%** / **Route Acc%** — Overall accuracy vs ground truth.
- **Accuracy Over Time** plot — Watch how accuracy evolves, especially with memory.
- **Per-persona differences** — Veteran, Rookie, and Analyst may perform differently.
- **Reflection log** — Dispatchers reason about their own performance.

## The A/B Experiment

1. Run with `use-llm?` ON for 50+ ticks. Note the Misleading% metric.
2. Click setup again. Toggle `use-llm?` OFF. Run for 50+ ticks.
3. Compare:
   - **Heuristic**: ~30% on misleading cases (keywords mislead it).
   - **LLM**: Expected ~70%+ on misleading cases (reads actual impact).
4. Compare memory modes: Run with "persistent" vs "none" over multiple episodes.

## LLM Primitives Exercised (8)

| Primitive | Where | Paper Concept |
|-----------|-------|---------------|
| `llm:load-config` | `setup-llm` | Config management |
| `llm:set-history` | `setup-dispatchers` — persona injection | Personalization (Ch.2) |
| `llm:chat-with-template` | `triage-my-incidents` — severity assessment | Environment/Interface (Ch.1) |
| `llm:choose` | `route-my-incidents` — bounded tier selection | Bounded Rationality |
| `llm:history` | `dispatcher-reflect` — check history length | Memory (Ch.3) |
| `llm:chat` | `dispatcher-reflect` — freeform reflection | Reflection (Ch.3) |
| `llm:clear-history` | `handle-episode-boundary` — configurable reset | Memory ablation |
| `llm:active` | Monitor widget — show provider/model | Provider awareness |

## Design Rationale

**Why dispatchers use LLM, not responders**: Triage and routing are judgment calls where reading context matters. Case processing is mechanical — it doesn't benefit from language understanding.

**Why no thinking/reasoning models**: With 3 dispatchers making 2+ LLM calls per tick, thinking models would add minutes of latency per tick. The triage task is classification, not multi-step reasoning. Standard `llm:chat-with-template` and `llm:choose` are the right tools.

**Why `llm:choose` for routing**: Guarantees the output is one of the valid tier names, avoiding parsing failures from freeform text.

**Why misleading incidents**: They make the LLM genuinely necessary. Without them, keyword matching achieves similar accuracy and the LLM adds cost without value.

## Paper Connection

This demo implements concepts from the Gao et al. (2312.11970) LLM-ABM survey:

- **Personalization** (Ch.2): Dispatcher personas via `llm:set-history` produce different decisions from the same model.
- **Bounded Rationality**: `llm:choose` constrains decisions to valid options.
- **Memory** (Ch.3): Configurable memory modes show how history retention affects performance.
- **Reflection** (Ch.3): Dispatchers reason about their own accuracy and identify patterns.
- **Environment/Interface** (Ch.1): Templates structure how agents perceive incidents.

## Files

| File | Purpose |
|------|---------|
| `crisis-triage.nlogox` | NetLogo 7 simulation model |
| `triage-template.yaml` | Severity assessment prompt with anti-keyword-bias guidance |
| `dispatcher-template.yaml` | Documentation stub (routing uses `llm:choose`) |
| `config.txt` | LLM provider configuration |

## Provider Configuration

Default is local Ollama (no API key needed). See commented examples in `config.txt` for OpenAI, Claude, and Gemini. Never commit real API keys.
