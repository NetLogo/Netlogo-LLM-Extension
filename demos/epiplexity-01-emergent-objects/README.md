# Demo 1: Emergent Discovery — LLM as Scientific Observer

An LLM observes Conway's Game of Life and acts as a scientist — describing patterns, predicting future states, and building theories about hidden rules. Memory transforms a confused observer into a competent one.

Validates **Paradox 1** from Finzi et al. (2026) *"From Entropy to Epiplexity"*: deterministic micro-rules expose extractable macro-structure to a computationally bounded observer with memory.

## Research Link

- Paper: *From Entropy to Epiplexity: Rethinking Information for Computationally Bounded Intelligence* (Finzi, Qiu, Jiang, Izmailov, Kolter, Wilson, 2026)
- arXiv: https://arxiv.org/abs/2601.03220

## Prerequisites

- **Ollama** running locally: https://ollama.ai
- **Qwen model** pulled:
  ```bash
  ollama pull qwen3.5:9b
  ```
- Verify Ollama is serving:
  ```bash
  curl http://localhost:11434/api/tags
  ```

## What the Model Does

- Runs Conway's Game of Life on a 50x50 grid with three dramatic seeds:
  - **R-pentomino** (center) — 5-cell methuselah that explodes into chaos, producing gliders, blinkers, blocks as emergent byproducts
  - **Gosper glider gun** (top-left) — continuously emits gliders every 30 ticks
  - **Pulsar** (bottom-right) — period-3 oscillator with dramatic rhythmic pulsing
- A single LLM observer scans the grid through a 5x5 local window
- Each tick, the observer:
  1. **Describes** what it sees in free text (`llm:chat-with-template`)
  2. **Predicts** the next 5x5 grid state (`llm:chat-with-template`)
  3. **Reflects** periodically to update its theory of the world

## Memory Modes

- **Bounded**: Clears LLM history every tick. The observer has amnesia — perpetually confused.
- **Persistent**: Accumulates history across ticks. The observer builds context, recognizes patterns, develops theories.

Switch between modes live using the `memory-mode` chooser.

## Run Instructions

1. Start Ollama: `ollama serve`
2. Open `game_of_life.nlogox` in NetLogo 7
3. Click **Setup** to initialize the grid and observer
4. Click **Test LLM** to verify Ollama connectivity
5. Start in **bounded** mode, click **Go**
6. After ~10 ticks, switch to **persistent** mode
7. Watch the LLM Theory monitor evolve and accuracy diverge

## What to Expect

- **Bounded mode**: LLM descriptions are generic, predictions are random, theory never develops
- **Persistent mode**: LLM starts recognizing patterns — "stable 2x2 blocks", "oscillating lines", "shapes that move diagonally"
- **The Plot**: Bounded accuracy (red) stays flat. Persistent accuracy (blue) climbs. The gap = the epiplexity signal.
- **The Killer Moment**: The LLM Theory monitor shows the observer independently discovering GoL patterns through pure observation with memory.

## Files

- `game_of_life.nlogox` — NetLogo model
- `config.txt` — Ollama provider/model settings
- `templates/describe.yaml` — prompt for pattern description
- `templates/predict_grid.yaml` — prompt for next-state prediction
- `templates/reflect.yaml` — prompt for theory generation
- `results/` — CSV output directory

## CSV Schema

`tick, observer_x, observer_y, window_pattern, llm_description, llm_predicted_grid, ground_truth_label, grid_match_percent, memory_mode, llm_provider, llm_model`

## Controls

| Control | Type | Purpose |
|---------|------|---------|
| memory-mode | chooser | Switch bounded/persistent live |
| show-observations? | switch | See raw LLM input in output area |
| reflect-every | slider (3-10) | How often the LLM updates its theory |
| episode-length | slider (20-200) | Total ticks per run |
