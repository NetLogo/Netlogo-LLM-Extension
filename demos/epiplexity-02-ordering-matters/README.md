# Epiplexity Demo 2: Ordering Matters

This demo operationalizes **Paradox 2** from *From Entropy to Epiplexity* (Finzi et al., 2026):

- Classical claim: information is order-independent.
- Bounded-observer claim: extracted structure depends on sequence order.

The same deterministic trajectory events are analyzed in three orderings:

1. `forward` (causal order)
2. `reversed` (time-reversed)
3. `shuffled` (chunk-randomized)

The observer (LLM-style adapter) induces rules and predicts next actions. Accuracy and coherence diverge by ordering.

## Files

- `trajectory_generator.nlogo`: deterministic trajectory generator (100 ticks, 5 foragers)
- `trajectory_analysis.py`: ordering analysis harness + CSV + plots
- `templates/trajectory_analysis.yaml`: rule-induction template
- `templates/next_step_predict.yaml`: next-step choice template
- `config.txt`: provider/model config for NetLogo extension and Python API mode
- `data/trajectory-raw.txt`: trajectory log input
- `results/trajectory-analysis.csv`: per-event analysis output
- `tests/test_analysis.py`: validation tests

## NetLogo Generator

Output row format:

```text
tick,agent_id,xcor,ycor,energy,state,action
```

Agent dynamics:

- `energy < 30` -> `state = hungry`
- hungry agents consume if resources exist, else move toward richer neighboring patches
- high-energy agents trend into resting behavior
- energy decays by `-1` per tick
- `energy <= 0` -> `die`

Run in NetLogo:

1. Open `trajectory_generator.nlogo`
2. Click `run-100`
3. Confirm `data/trajectory-raw.txt`

## Analysis Harness

Default mode is deterministic `mock`, which is offline-safe and reproducible.

```bash
cd demos/epiplexity-02-ordering-matters
python3 trajectory_analysis.py --mode mock --input data/trajectory-raw.txt --output results/trajectory-analysis.csv
```

Optional real model mode (OpenAI-compatible APIs, incl. Ollama `/v1`):

```bash
python3 trajectory_analysis.py --mode ollama --config config.txt
# or
python3 trajectory_analysis.py --mode openai --config config.txt
```

## Outputs

- `results/trajectory-analysis.csv` with columns:
  - `ordering,tick,event_index,agent_id,rule_hypothesis,predicted_action,actual_action,accuracy,coherence,prediction_entropy`
- `results/plot-accuracy-over-time.svg`
- `results/plot-hypothesis-coherence.svg`
- `results/plot-accuracy-summary.svg`
- `results/summary.txt`

Expected pattern (Paradox 2 signal):

- `forward accuracy` significantly higher than `reversed` and `shuffled`
- `forward` hypotheses are more stable/coherent
- `reversed/shuffled` show lower coherence and higher uncertainty

## Tests

```bash
cd demos/epiplexity-02-ordering-matters
python3 -m unittest tests/test_analysis.py -v
```

Tests validate:

- trajectory parsing and ordering construction
- CSV schema and no missing/NaN-like values
- prediction choices constrained to valid action set
- coherence/entropy bounds
- ordering gap thresholds:
  - `forward >= 0.70`
  - `reversed <= 0.50`
  - `shuffled <= 0.40`

## References

- Paper: `https://arxiv.org/pdf/2601.03220`
- Related notes:
  - `From Entropy to Epiplexity (Finzi et al 2026)`
  - `Epiplexity Paper — NetLogo Demo Concepts (using llm extension).md`
  - `NetLogo Demo Ideas — Index (LLM extension).md`
- Repository extension docs:
  - `docs/API-REFERENCE.md`
  - `docs/USAGE.md`
- Related demos:
  - `demos/color-sharing/`
  - `demos/emergent-treasure-hunt/`
