# Battle City Tank Arena

A NetLogo LLM extension demo that replicates the key finding from [BattleAgentBench](https://arxiv.org/abs/2408.15971): **communication between LLM agents hurts most models — only strong models benefit from messaging**.

## Hypothesis

When LLM-controlled tanks can send tactical messages to allies:
- **Weak models** (gpt-4o-mini, etc.) perform *worse* due to message noise, hallucinated coordination, and context overload
- **Strong models** (gpt-4o, claude-3.5-sonnet, etc.) perform *better* by leveraging ally intel for coordinated tactics

## Stages

| Stage | Setup | What It Tests |
|-------|-------|---------------|
| 1: Solo Navigation | 1 LLM tank reaches a flag | Spatial reasoning with `llm:choose` |
| 2: Team Battle | 2 LLM tanks vs 2 bots (no comms) | Implicit cooperation via `llm:choose` |
| 3: Team + Comms | Same + messaging channel | Communication paradox via `llm:choose` + `llm:chat-with-template` + `llm:set-history` |

## Setup

1. Install the LLM extension in NetLogo 7.0.3+
2. Edit `config.txt` with your provider and API key:
   ```
   provider=openai
   model=gpt-4o-mini
   temperature=0.3
   api_key=YOUR_KEY_HERE
   ```
3. Open `battle-city-tank-arena.nlogox` in NetLogo
4. Select a stage, click Setup, then Go

## Running the Experiment

1. Set stage to "2: Team Battle", click Setup → Go. Note the final score.
2. Set stage to "3: Team + Comms", click Setup → Go. Compare scores.
3. Toggle `comms-override?` mid-run to see the effect live.
4. Change `config.txt` to a stronger model (e.g., `gpt-4o`) and repeat.

## Expected Results

- **gpt-4o-mini**: Stage 3 (with comms) performs *equal or worse* than Stage 2
- **gpt-4o / claude-3.5-sonnet**: Stage 3 performs *better* than Stage 2
- `show-thinking?` reveals the reasoning chain differences

## LLM Primitives Used

- `llm:load-config` — Load provider/model settings
- `llm:choose` — Select action from fixed options given observations
- `llm:clear-history` — Fresh context each decision tick
- `llm:chat-with-template` — Generate tactical messages (Stage 3)
- `llm:set-history` — Inject ally messages into decision context
- `llm:chat-with-thinking` — Expose reasoning chains (show-thinking mode)

## Files

- `battle-city-tank-arena.nlogox` — Main model
- `config.txt` — LLM provider configuration
- `action-template.yaml` — Action decision prompt (thinking mode)
- `message-template.yaml` — Communication prompt (Stage 3)
