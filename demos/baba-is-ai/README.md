# Baba Is AI

An LLM agent plays a simplified "Baba Is You" puzzle game where the rules of the world are physical word-block objects on a grid. The agent must push word-blocks to change or break rules in order to reach the goal.

## The Experiment

### Background

Based on findings from the [BALROG benchmark](https://arxiv.org/abs/2411.13543) (Benchmarking Agentic LLM and VLM Reasoning On Games), which found that **all tested LLMs score near 0% on Baba Is You** despite understanding the rules perfectly when quizzed in isolation.

### The Knowing-Doing Gap

LLMs can explain that "rules are pushable objects" and "you need to rearrange word-blocks to change rules." But when given a spatial grid and asked to actually execute moves to push specific blocks in specific directions, they fail consistently. They *know* what to do but *cannot do* it.

### What This Demo Tests

Can an LLM agent, given a text observation of a 9x9 grid, figure out that it needs to:
1. Navigate to the right word-block
2. Push it in the right direction
3. Verify the rule changed
4. Navigate through the now-passable area to win

This is **meta-rule discovery** -- the agent doesn't just follow rules, it manipulates them.

### Why It Matters for ABM

Agent-based models traditionally have fixed rules. This demo shows agents operating in a world where **the rules themselves are agents** (word-blocks) that can be moved. The rule engine is pure NetLogo ABM; the decision-making is LLM. Together they create a system where rules are emergent from the spatial arrangement of objects.

## Prerequisites

1. **Ollama** running locally with `qwen2.5:7b` pulled:
   ```bash
   ollama pull qwen2.5:7b
   ```
2. **NetLogo 7.0.3** with the LLM extension JAR installed
3. Built extension: run `./build.sh` from the extension root if needed

## How to Run

1. Open `baba-is-ai.nlogox` in NetLogo 7.0.3
2. Select a level from the chooser
3. Click **Setup** to initialize
4. Click **Go** to let the LLM agent play autonomously, or **Step** for one move at a time
5. Toggle **show-observations?** to see the text observation sent to the LLM each turn
6. Watch the **Active Rules** monitor update as word-blocks are pushed

## Levels

### Level 1: Navigate (baseline)
```
Rules: BABA IS YOU, FLAG IS WIN
Layout: baba on the left, flag on the right, clear path
Solution: Walk right to the flag (~7 moves)
Expected: Most LLMs solve this consistently
```

### Level 2: Break the Wall (medium)
```
Rules: BABA IS YOU, WALL IS STOP, FLAG IS WIN
Layout: Wall column blocking the path to the flag
Solution: Navigate to the STOP word-block, push it away to break
          "WALL IS STOP", then walk through walls to the flag
Expected: Some LLMs occasionally solve this; most get stuck
```

### Level 3: Push and Rearrange (hard)
```
Rules: BABA IS YOU, ROCK IS PUSH, WALL IS STOP, FLAG IS WIN
Layout: Rocks near the WALL IS STOP rule, wall column, flag behind walls
Solution: Navigate around rocks, push STOP away, walk through walls
Expected: Very rarely solved -- demonstrates the knowing-doing gap
```

## Swapping Providers

Edit `config.txt` to try different LLM providers:

```
# OpenAI
provider=openai
model=gpt-4o-mini
api_key=YOUR_KEY_HERE

# Anthropic
provider=anthropic
model=claude-sonnet-4-20250514
api_key=YOUR_KEY_HERE

# Gemini
provider=gemini
model=gemini-2.0-flash
api_key=YOUR_KEY_HERE
```

## Files

| File | Purpose |
|------|---------|
| `baba-is-ai.nlogox` | Main NetLogo model with rule engine, levels, and LLM integration |
| `config.txt` | LLM provider configuration (defaults to local Ollama) |
| `action-template.yaml` | Documents the prompt format (not used at runtime -- `llm:choose` builds its own prompt) |
| `README.md` | This file |

## How It Works

### Rule Engine
Three consecutive word-blocks in a horizontal or vertical line form a rule: `[NOUN] IS [PROPERTY]`. The engine scans all possible 3-tuples every tick and rebuilds the active rule set.

### Push System
Word-blocks are **always pushable**. Other entities (rocks, walls) are pushable only if they have the PUSH property. Push chains propagate: pushing a block into another pushable block pushes both.

### LLM Integration
Each tick, the agent receives a text observation including the grid layout, active rules, adjacent cell descriptions, and feedback from the last action. It chooses from `["up", "down", "left", "right"]` via `llm:choose`. History accumulates across ticks so the agent can learn from failed moves.

## Extension Points

- **New properties**: Add DEFEAT (touching kills baba), SINK (object + baba both destroyed), MELT/HOT combos
- **Multiple YOU entities**: What if two things are YOU and must coordinate?
- **Procedural level generation**: Random placement of word-blocks and obstacles
- **Rule construction**: Levels where the agent must *build* a rule, not just break one

## Reference

- BALROG paper: [arXiv 2411.13543](https://arxiv.org/abs/2411.13543)
- Baba Is You (original game): [hempuli.com/baba](https://hempuli.com/baba/)
