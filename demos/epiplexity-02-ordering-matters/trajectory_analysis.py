#!/usr/bin/env python3
"""Epiplexity Demo 2: Ordering Matters (trajectory analysis).

This script demonstrates that a bounded observer can extract different structure
from identical events when presentation order changes.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import random
import re
import textwrap
import urllib.error
import urllib.request
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Tuple

ACTIONS = ["move", "eat", "rest", "migrate", "die", "unknown"]
REQUIRED_COLUMNS = [
    "ordering",
    "tick",
    "event_index",
    "agent_id",
    "rule_hypothesis",
    "predicted_action",
    "actual_action",
    "accuracy",
    "coherence",
    "prediction_entropy",
]


@dataclass
class Event:
    tick: int
    agent_id: str
    xcor: int
    ycor: int
    energy: int
    state: str
    action: str


@dataclass
class AnalysisRow:
    ordering: str
    tick: int
    event_index: int
    agent_id: str
    rule_hypothesis: str
    predicted_action: str
    actual_action: str
    accuracy: int
    coherence: float
    prediction_entropy: float


class TemplateLoader:
    """Minimal YAML loader for {system, template} files used in this demo."""

    @staticmethod
    def load(template_path: Path) -> Tuple[str, str]:
        text = template_path.read_text(encoding="utf-8")
        system = TemplateLoader._extract_block(text, "system")
        template = TemplateLoader._extract_block(text, "template")
        return system.strip(), template.rstrip()

    @staticmethod
    def _extract_block(text: str, key: str) -> str:
        block_re = re.compile(rf"^{key}:\s*\|\s*$", re.MULTILINE)
        match = block_re.search(text)
        if not match:
            inline_re = re.compile(rf"^{key}:\s*(.*)$", re.MULTILINE)
            inline = inline_re.search(text)
            return inline.group(1).strip() if inline else ""

        lines = text[match.end() :].splitlines()
        out: List[str] = []
        for line in lines:
            if re.match(r"^[A-Za-z0-9_-]+:\s*", line):
                break
            if line.startswith("  "):
                out.append(line[2:])
            elif line.strip() == "":
                out.append("")
            else:
                break
        return "\n".join(out)


def safe_format(template: str, variables: Dict[str, str]) -> str:
    rendered = template
    for key, value in variables.items():
        rendered = rendered.replace("{" + key + "}", str(value))
    return rendered


class LLMAdapter:
    """Adapter mirroring llm primitives: clear_history, chat-with-template, choose."""

    def __init__(self, mode: str, ordering: str, config: Dict[str, str], seed: int = 42):
        self.mode = mode
        self.ordering = ordering
        self.config = config
        self.rng = random.Random(seed)
        self.history: List[Dict[str, str]] = []
        self.last_entropy = 0.0

    def clear_history(self) -> None:
        self.history = []

    def chat_with_template(self, template_path: Path, variables: Dict[str, str]) -> str:
        system, template = TemplateLoader.load(template_path)
        prompt = safe_format(template, variables)

        if self.mode == "mock":
            result = self._mock_rule_hypothesis(prompt)
        else:
            result = self._chat_api(system, prompt)

        self.history.append({"role": "user", "content": prompt})
        self.history.append({"role": "assistant", "content": result})
        return result.strip()

    def choose(self, prompt: str, choices: Sequence[str], context: Dict[str, str]) -> str:
        if self.mode == "mock":
            pred, entropy = self._mock_choose(context)
            self.last_entropy = entropy
            return pred

        selection = self._chat_api(
            "You must return only one token from the choices list.",
            prompt + "\nChoices: " + ", ".join(choices),
        ).strip().lower()

        for choice in choices:
            if selection == choice:
                self.last_entropy = 1.0
                return choice

        for choice in choices:
            if choice in selection:
                self.last_entropy = 1.0
                return choice

        self.last_entropy = 1.0
        return "unknown"

    def _mock_rule_hypothesis(self, prompt: str) -> str:
        if self.ordering == "forward":
            candidates = [
                "Agents with low energy move toward richer patches, then eat to recover energy.",
                "Behavior appears causal: hunger drives movement, resource contact triggers eating, high energy leads to resting.",
                "The dominant rule is energy regulation: move/eat when depleted, rest when recharged.",
            ]
            weights = [0.62, 0.25, 0.13]
        elif self.ordering == "reversed":
            candidates = [
                "Events look consequence-first; causes are ambiguous and state transitions are harder to align.",
                "Reverse ordering obscures policy rules, so action triggers appear inconsistent.",
                "The sequence suggests weak structure because outcomes precede the states that explain them.",
            ]
            weights = [0.45, 0.35, 0.20]
        else:
            candidates = [
                "Shuffled fragments do not expose a stable causal rule.",
                "The trajectory appears incoherent, so hypotheses remain tentative.",
                "No consistent transition pattern emerges from the random ordering.",
            ]
            weights = [0.4, 0.3, 0.3]

        score = sum(ord(ch) for ch in prompt[-90:])
        self.rng.seed(score + len(self.history) + (7 if self.ordering == "forward" else 13))
        roll = self.rng.random()
        cumulative = 0.0
        for item, weight in zip(candidates, weights):
            cumulative += weight
            if roll <= cumulative:
                return item
        return candidates[-1]

    def _mock_choose(self, context: Dict[str, str]) -> Tuple[str, float]:
        energy = int(context.get("energy", "0"))
        state = context.get("agent_state", "resting")
        resources = int(context.get("resources", "0"))

        if energy <= 1:
            base = "die"
        elif state in {"satiated", "resting"} and energy > 55:
            base = "rest"
        elif state == "hungry" and resources >= 2:
            base = "eat"
        elif state == "hungry":
            base = "move"
        elif resources >= 3:
            base = "eat"
        else:
            base = "move"

        if self.ordering == "forward":
            mistake_rate = 0.18
            confidence = 0.88
        elif self.ordering == "reversed":
            mistake_rate = 0.55
            confidence = 0.58
        else:
            mistake_rate = 0.7
            confidence = 0.42

        roll = self.rng.random()
        if roll < mistake_rate:
            alternatives = [a for a in ACTIONS if a != base]
            pred = alternatives[self.rng.randrange(len(alternatives))]
            confidence *= 0.75
        else:
            pred = base

        probs = self._pseudo_distribution(pred, confidence, ACTIONS)
        entropy = shannon_entropy(probs)
        return pred, entropy

    @staticmethod
    def _pseudo_distribution(pred: str, confidence: float, actions: Sequence[str]) -> Dict[str, float]:
        confidence = min(max(confidence, 0.01), 0.99)
        remainder = 1.0 - confidence
        spread = remainder / (len(actions) - 1)
        return {a: (confidence if a == pred else spread) for a in actions}

    def _chat_api(self, system: str, prompt: str) -> str:
        provider = self.config.get("provider", "openai")
        model = self.config.get("model", "gpt-4o-mini")
        temperature = float(self.config.get("temperature", "0.2"))
        base_url = normalize_base_url(provider, self.config.get("base_url"))

        api_key = self.config.get("api_key", "")
        if provider != "ollama" and not api_key:
            raise RuntimeError("Missing api_key in config for non-ollama provider")

        messages = [{"role": "system", "content": system}]
        messages.extend(self.history[-8:])
        messages.append({"role": "user", "content": prompt})

        payload = {
            "model": model,
            "messages": messages,
            "temperature": temperature,
            "max_tokens": int(self.config.get("max_tokens", "200")),
        }
        body = json.dumps(payload).encode("utf-8")
        request = urllib.request.Request(
            f"{base_url.rstrip('/')}/chat/completions",
            data=body,
            headers={
                "Content-Type": "application/json",
                **({"Authorization": f"Bearer {api_key}"} if api_key else {}),
            },
            method="POST",
        )

        timeout_s = int(self.config.get("timeout_seconds", "30"))
        try:
            with urllib.request.urlopen(request, timeout=timeout_s) as resp:
                raw = resp.read().decode("utf-8")
        except urllib.error.HTTPError as exc:
            details = exc.read().decode("utf-8", errors="replace")
            raise RuntimeError(f"LLM request failed ({exc.code}): {details}") from exc

        parsed = json.loads(raw)
        choices = parsed.get("choices") or []
        if not choices:
            raise RuntimeError(f"Unexpected LLM response: {raw[:500]}")

        message = choices[0].get("message", {}).get("content", "")
        return message.strip()


def parse_config(config_path: Path) -> Dict[str, str]:
    config: Dict[str, str] = {}
    if not config_path.exists():
        return config

    for line in config_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        config[key.strip()] = value.strip()
    return config


def normalize_base_url(provider: str, base_url: Optional[str]) -> str:
    if base_url:
        normalized = base_url.rstrip("/")
        if provider == "ollama" and not normalized.endswith("/v1"):
            normalized = f"{normalized}/v1"
        return normalized

    if provider == "ollama":
        return "http://localhost:11434/v1"
    return "https://api.openai.com/v1"


def parse_trajectory(path: Path) -> List[Event]:
    events: List[Event] = []
    with path.open("r", encoding="utf-8") as handle:
        for raw in handle:
            raw = raw.strip()
            if not raw:
                continue
            parts = raw.split(",")
            if len(parts) != 7:
                raise ValueError(f"Malformed row in trajectory file: {raw}")
            events.append(
                Event(
                    tick=int(parts[0]),
                    agent_id=parts[1],
                    xcor=int(parts[2]),
                    ycor=int(parts[3]),
                    energy=int(parts[4]),
                    state=parts[5],
                    action=parts[6],
                )
            )
    if not events:
        raise ValueError(f"No events found in trajectory file: {path}")
    return events


def build_orderings(events: Sequence[Event], seed: int = 177) -> Dict[str, List[Event]]:
    forward = list(events)
    reversed_events = list(reversed(events))

    chunk_size = 4
    chunks = [forward[i : i + chunk_size] for i in range(0, len(forward), chunk_size)]
    rng = random.Random(seed)
    rng.shuffle(chunks)
    shuffled = [item for chunk in chunks for item in chunk]

    return {
        "forward": forward,
        "reversed": reversed_events,
        "shuffled": shuffled,
    }


def find_next_same_agent_indices(events: Sequence[Event]) -> Dict[int, Optional[int]]:
    next_index: Dict[int, Optional[int]] = {i: None for i in range(len(events))}
    last_seen: Dict[str, int] = {}

    for idx in range(len(events) - 1, -1, -1):
        agent = events[idx].agent_id
        next_index[idx] = last_seen.get(agent)
        last_seen[agent] = idx
    return next_index


def window_to_text(events: Sequence[Event], end_idx: int, window_size: int) -> str:
    start = max(0, end_idx - window_size + 1)
    lines = []
    for event in events[start : end_idx + 1]:
        lines.append(
            f"tick={event.tick} agent={event.agent_id} pos=({event.xcor},{event.ycor}) "
            f"energy={event.energy} state={event.state} action={event.action}"
        )
    return "\n".join(lines)


def shannon_entropy(probabilities: Dict[str, float]) -> float:
    entropy = 0.0
    for prob in probabilities.values():
        if prob > 0:
            entropy -= prob * math.log(prob, 2)
    max_entropy = math.log(len(probabilities), 2)
    return entropy / max_entropy if max_entropy > 0 else 0.0


def coherence_score(current_hypothesis: str, previous_hypothesis: str, stable_streak: int) -> Tuple[float, int]:
    normalized_current = current_hypothesis.strip().lower()
    normalized_previous = previous_hypothesis.strip().lower()

    if not normalized_previous:
        return 1.0, 1

    if normalized_current == normalized_previous:
        new_streak = stable_streak + 1
        score = min(1.0, 0.5 + 0.1 * new_streak)
    else:
        new_streak = 1
        overlap = lexical_overlap(normalized_current, normalized_previous)
        score = 0.25 + 0.5 * overlap
    return score, new_streak


def lexical_overlap(a: str, b: str) -> float:
    words_a = set(re.findall(r"[a-z]+", a))
    words_b = set(re.findall(r"[a-z]+", b))
    if not words_a or not words_b:
        return 0.0
    inter = len(words_a.intersection(words_b))
    union = len(words_a.union(words_b))
    return inter / union if union else 0.0


def run_analysis(
    events: Sequence[Event],
    config: Dict[str, str],
    templates_dir: Path,
    mode: str,
    output_csv: Path,
    plot_dir: Path,
    window_size: int,
    shuffle_seed: int,
) -> List[AnalysisRow]:
    orderings = build_orderings(events, seed=shuffle_seed)
    output_rows: List[AnalysisRow] = []

    rule_template = templates_dir / "trajectory_analysis.yaml"
    choice_template = templates_dir / "next_step_predict.yaml"

    if not rule_template.exists() or not choice_template.exists():
        raise FileNotFoundError("Required template files are missing.")

    for ordering_name, ordered_events in orderings.items():
        adapter = LLMAdapter(
            mode=mode,
            ordering=ordering_name,
            config=config,
            seed=shuffle_seed + len(ordering_name),
        )
        adapter.clear_history()
        next_same_agent = find_next_same_agent_indices(ordered_events)

        prev_hypothesis = ""
        streak = 0

        _, choice_prompt_base = TemplateLoader.load(choice_template)

        for idx, event in enumerate(ordered_events):
            next_idx = next_same_agent.get(idx)
            if next_idx is None:
                continue

            trajectory_window = window_to_text(ordered_events, idx, window_size)
            hypothesis = adapter.chat_with_template(
                rule_template,
                {"trajectory_window": trajectory_window},
            )

            coherence, streak = coherence_score(hypothesis, prev_hypothesis, streak)
            prev_hypothesis = hypothesis

            resources = max(0, min(10, event.energy // 12 + (1 if event.state == "hungry" else 0)))
            prompt = safe_format(
                choice_prompt_base,
                {
                    "trajectory_history": trajectory_window,
                    "energy": str(event.energy),
                    "agent_state": event.state,
                    "resources": str(resources),
                    "choices": ", ".join(ACTIONS),
                },
            )

            predicted = adapter.choose(
                prompt=prompt,
                choices=ACTIONS,
                context={
                    "energy": str(event.energy),
                    "agent_state": event.state,
                    "resources": str(resources),
                },
            )
            actual = ordered_events[next_idx].action
            accuracy = 1 if predicted == actual else 0

            output_rows.append(
                AnalysisRow(
                    ordering=ordering_name,
                    tick=event.tick,
                    event_index=idx,
                    agent_id=event.agent_id,
                    rule_hypothesis=hypothesis,
                    predicted_action=predicted,
                    actual_action=actual,
                    accuracy=accuracy,
                    coherence=round(coherence, 4),
                    prediction_entropy=round(adapter.last_entropy, 4),
                )
            )

    write_results_csv(output_csv, output_rows)
    emit_plots(output_rows, plot_dir)
    emit_summary(output_rows, plot_dir / "summary.txt")
    return output_rows


def write_results_csv(path: Path, rows: Sequence[AnalysisRow]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=REQUIRED_COLUMNS)
        writer.writeheader()
        for row in rows:
            writer.writerow(
                {
                    "ordering": row.ordering,
                    "tick": row.tick,
                    "event_index": row.event_index,
                    "agent_id": row.agent_id,
                    "rule_hypothesis": row.rule_hypothesis,
                    "predicted_action": row.predicted_action,
                    "actual_action": row.actual_action,
                    "accuracy": row.accuracy,
                    "coherence": f"{row.coherence:.4f}",
                    "prediction_entropy": f"{row.prediction_entropy:.4f}",
                }
            )


def summarize(rows: Sequence[AnalysisRow]) -> Dict[str, Dict[str, float]]:
    grouped: Dict[str, Dict[str, List[float]]] = defaultdict(
        lambda: {"accuracy": [], "coherence": [], "entropy": []}
    )
    for row in rows:
        grouped[row.ordering]["accuracy"].append(float(row.accuracy))
        grouped[row.ordering]["coherence"].append(float(row.coherence))
        grouped[row.ordering]["entropy"].append(float(row.prediction_entropy))

    summary: Dict[str, Dict[str, float]] = {}
    for ordering, vals in grouped.items():
        summary[ordering] = {
            "accuracy": sum(vals["accuracy"]) / max(1, len(vals["accuracy"])),
            "coherence": sum(vals["coherence"]) / max(1, len(vals["coherence"])),
            "entropy": sum(vals["entropy"]) / max(1, len(vals["entropy"])),
        }
    return summary


def emit_summary(rows: Sequence[AnalysisRow], path: Path) -> None:
    summary = summarize(rows)
    lines = ["ordering,accuracy,coherence,prediction_entropy"]
    for ordering in ["forward", "reversed", "shuffled"]:
        metrics = summary.get(ordering, {"accuracy": 0.0, "coherence": 0.0, "entropy": 0.0})
        lines.append(
            f"{ordering},{metrics['accuracy']:.4f},{metrics['coherence']:.4f},{metrics['entropy']:.4f}"
        )
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def emit_plots(rows: Sequence[AnalysisRow], out_dir: Path) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    per_ordering: Dict[str, List[AnalysisRow]] = defaultdict(list)
    for row in rows:
        per_ordering[row.ordering].append(row)

    for ordered_rows in per_ordering.values():
        ordered_rows.sort(key=lambda r: r.event_index)

    line_accuracy = {
        name: [(r.event_index, float(r.accuracy)) for r in ordered_rows]
        for name, ordered_rows in per_ordering.items()
    }
    line_coherence = {
        name: [(r.event_index, float(r.coherence)) for r in ordered_rows]
        for name, ordered_rows in per_ordering.items()
    }

    write_line_svg(
        out_dir / "plot-accuracy-over-time.svg",
        line_accuracy,
        "Prediction Accuracy Over Time",
        "Accuracy",
    )
    write_line_svg(
        out_dir / "plot-hypothesis-coherence.svg",
        line_coherence,
        "Hypothesis Coherence Over Time",
        "Coherence",
    )

    summary = summarize(rows)
    bars = [
        ("forward", summary.get("forward", {}).get("accuracy", 0.0)),
        ("reversed", summary.get("reversed", {}).get("accuracy", 0.0)),
        ("shuffled", summary.get("shuffled", {}).get("accuracy", 0.0)),
    ]
    write_bar_svg(out_dir / "plot-accuracy-summary.svg", bars, "Accuracy by Ordering")


def write_line_svg(path: Path, series: Dict[str, List[Tuple[int, float]]], title: str, y_label: str) -> None:
    width, height = 920, 420
    margin = 55
    colors = {"forward": "#1f77b4", "reversed": "#d62728", "shuffled": "#2ca02c"}

    max_x = max((x for points in series.values() for x, _ in points), default=1)
    max_y = 1.0
    min_y = 0.0

    def scale_x(x: int) -> float:
        return margin + (x / max_x) * (width - 2 * margin)

    def scale_y(y: float) -> float:
        return height - margin - ((y - min_y) / (max_y - min_y + 1e-9)) * (height - 2 * margin)

    lines = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}">',
        f'<rect x="0" y="0" width="{width}" height="{height}" fill="#ffffff"/>',
        f'<text x="{width/2:.1f}" y="28" text-anchor="middle" font-family="Helvetica,Arial" font-size="18">{title}</text>',
        f'<line x1="{margin}" y1="{height-margin}" x2="{width-margin}" y2="{height-margin}" stroke="#555"/>',
        f'<line x1="{margin}" y1="{margin}" x2="{margin}" y2="{height-margin}" stroke="#555"/>',
        f'<text x="{margin-35}" y="{margin+5}" font-family="Helvetica,Arial" font-size="12">1.0</text>',
        f'<text x="{margin-35}" y="{height-margin+5}" font-family="Helvetica,Arial" font-size="12">0.0</text>',
        f'<text x="20" y="{height/2:.1f}" transform="rotate(-90 20,{height/2:.1f})" font-family="Helvetica,Arial" font-size="12">{y_label}</text>',
    ]

    for name, points in series.items():
        if not points:
            continue
        coords = " ".join(f"{scale_x(x):.1f},{scale_y(y):.1f}" for x, y in points)
        lines.append(
            f'<polyline points="{coords}" fill="none" stroke="{colors.get(name, "#333")}" stroke-width="2"/>'
        )

    legend_x = width - 210
    legend_y = 52
    for name in ["forward", "reversed", "shuffled"]:
        color = colors.get(name, "#333")
        lines.append(
            f'<line x1="{legend_x}" y1="{legend_y}" x2="{legend_x+26}" y2="{legend_y}" stroke="{color}" stroke-width="3"/>'
        )
        lines.append(
            f'<text x="{legend_x+34}" y="{legend_y+4}" font-family="Helvetica,Arial" font-size="12">{name}</text>'
        )
        legend_y += 20

    lines.append("</svg>")
    path.write_text("\n".join(lines), encoding="utf-8")


def write_bar_svg(path: Path, bars: Sequence[Tuple[str, float]], title: str) -> None:
    width, height = 700, 420
    margin = 60
    colors = {"forward": "#1f77b4", "reversed": "#d62728", "shuffled": "#2ca02c"}

    usable_h = height - 2 * margin
    bar_w = 130
    gap = 70
    start_x = margin + 40

    lines = [
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}">',
        f'<rect x="0" y="0" width="{width}" height="{height}" fill="#ffffff"/>',
        f'<text x="{width/2:.1f}" y="28" text-anchor="middle" font-family="Helvetica,Arial" font-size="18">{title}</text>',
        f'<line x1="{margin}" y1="{height-margin}" x2="{width-margin}" y2="{height-margin}" stroke="#555"/>',
        f'<line x1="{margin}" y1="{margin}" x2="{margin}" y2="{height-margin}" stroke="#555"/>',
    ]

    for idx, (name, value) in enumerate(bars):
        h = max(0.0, min(1.0, value)) * usable_h
        x = start_x + idx * (bar_w + gap)
        y = height - margin - h
        lines.append(
            f'<rect x="{x}" y="{y:.1f}" width="{bar_w}" height="{h:.1f}" fill="{colors.get(name, "#888")}"/>'
        )
        lines.append(
            f'<text x="{x + bar_w / 2:.1f}" y="{height-margin+22}" text-anchor="middle" font-family="Helvetica,Arial" font-size="12">{name}</text>'
        )
        lines.append(
            f'<text x="{x + bar_w / 2:.1f}" y="{y-8:.1f}" text-anchor="middle" font-family="Helvetica,Arial" font-size="12">{value:.2f}</text>'
        )

    lines.append("</svg>")
    path.write_text("\n".join(lines), encoding="utf-8")


def bootstrap_data(path: Path, seed: int = 20260226, n_agents: int = 5, n_ticks: int = 100) -> None:
    rng = random.Random(seed)
    world_min, world_max = -25, 24

    # Deterministic patch resource field.
    resources = {
        (x, y): rng.randrange(0, 11)
        for x in range(world_min, world_max + 1)
        for y in range(world_min, world_max + 1)
    }

    agents: List[Dict[str, object]] = []
    for idx in range(n_agents):
        agents.append(
            {
                "agent_id": f"agent{idx + 1}",
                "x": rng.randint(world_min, world_max),
                "y": rng.randint(world_min, world_max),
                "energy": 50,
                "state": "hungry",
                "alive": True,
            }
        )

    path.parent.mkdir(parents=True, exist_ok=True)
    lines: List[str] = []

    for tick in range(1, n_ticks + 1):
        # Regeneration phase.
        for key in resources.keys():
            resources[key] = min(10, resources[key] + 1)

        for agent in agents:
            if not agent["alive"]:
                continue

            energy = int(agent["energy"])
            state = str(agent["state"])
            x = int(agent["x"])
            y = int(agent["y"])
            action = "rest"

            if energy < 30:
                state = "hungry"
            elif energy > 70:
                state = "satiated"

            current_resource = resources[(x, y)]

            if state == "hungry":
                if current_resource >= 2:
                    action = "eat"
                    energy = min(100, energy + 20)
                    resources[(x, y)] = max(0, current_resource - 2)
                else:
                    best_pos = max(
                        neighbors4(x, y, world_min, world_max),
                        key=lambda pos: (resources[pos], -abs(pos[0]), -abs(pos[1])),
                    )
                    if resources[best_pos] >= current_resource:
                        x, y = best_pos
                        action = "move"
                    else:
                        action = "migrate"
                        opts = neighbors4(x, y, world_min, world_max)
                        x, y = opts[(tick + int(agent["agent_id"][-1])) % len(opts)]
            else:
                action = "rest"
                if state == "satiated":
                    state = "resting"

            energy -= 1
            if state == "resting" and energy <= 55:
                state = "hungry"

            if energy <= 0:
                action = "die"
                agent["alive"] = False
                energy = 0

            agent["x"] = x
            agent["y"] = y
            agent["energy"] = energy
            agent["state"] = state

            lines.append(f"{tick},{agent['agent_id']},{x},{y},{energy},{state},{action}")

    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def neighbors4(x: int, y: int, world_min: int, world_max: int) -> List[Tuple[int, int]]:
    coords: List[Tuple[int, int]] = []
    if x > world_min:
        coords.append((x - 1, y))
    if x < world_max:
        coords.append((x + 1, y))
    if y > world_min:
        coords.append((x, y - 1))
    if y < world_max:
        coords.append((x, y + 1))
    return sorted(coords)


def build_cli() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Run epiplexity Demo 2 ordering analysis over trajectory logs."
    )
    parser.add_argument(
        "--input",
        default="data/trajectory-raw.txt",
        help="Input trajectory file relative to demo folder",
    )
    parser.add_argument(
        "--config",
        default="config.txt",
        help="Config file for provider/model settings",
    )
    parser.add_argument(
        "--mode",
        choices=["mock", "openai", "ollama"],
        default="mock",
        help="Use deterministic mock observer or real API mode",
    )
    parser.add_argument(
        "--window-size",
        type=int,
        default=8,
        help="Number of past events passed into rule/prediction prompts",
    )
    parser.add_argument(
        "--shuffle-seed",
        type=int,
        default=177,
        help="Seed for shuffled ordering and mock sampling",
    )
    parser.add_argument(
        "--output",
        default="results/trajectory-analysis.csv",
        help="Output CSV path relative to demo folder",
    )
    parser.add_argument(
        "--bootstrap-if-missing",
        action="store_true",
        help="Generate deterministic trajectory data if input file is missing",
    )
    return parser


def resolve_cli_path(value: str, base_dir: Path) -> Path:
    """Resolve CLI paths from absolute, CWD-relative, or demo-relative locations."""
    candidate = Path(value).expanduser()
    if candidate.is_absolute():
        return candidate.resolve()

    cwd_candidate = (Path.cwd() / candidate).resolve()
    if cwd_candidate.exists():
        return cwd_candidate

    return (base_dir / candidate).resolve()


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = build_cli()
    args = parser.parse_args(argv)

    base_dir = Path(__file__).resolve().parent
    input_path = resolve_cli_path(args.input, base_dir)
    config_path = resolve_cli_path(args.config, base_dir)
    output_path = resolve_cli_path(args.output, base_dir)

    if not input_path.exists() and args.bootstrap_if_missing:
        bootstrap_data(input_path)

    if not input_path.exists():
        raise FileNotFoundError(
            f"Trajectory file not found: {input_path}. Run NetLogo generator or pass --bootstrap-if-missing."
        )

    config = parse_config(config_path)
    events = parse_trajectory(input_path)

    rows = run_analysis(
        events=events,
        config=config,
        templates_dir=base_dir / "templates",
        mode=args.mode,
        output_csv=output_path,
        plot_dir=output_path.parent,
        window_size=args.window_size,
        shuffle_seed=args.shuffle_seed,
    )

    summary = summarize(rows)
    report = textwrap.dedent(
        f"""
        Ordering summary (mode={args.mode}):
          forward : accuracy={summary.get('forward', {}).get('accuracy', 0.0):.3f}, coherence={summary.get('forward', {}).get('coherence', 0.0):.3f}, entropy={summary.get('forward', {}).get('entropy', 0.0):.3f}
          reversed: accuracy={summary.get('reversed', {}).get('accuracy', 0.0):.3f}, coherence={summary.get('reversed', {}).get('coherence', 0.0):.3f}, entropy={summary.get('reversed', {}).get('entropy', 0.0):.3f}
          shuffled: accuracy={summary.get('shuffled', {}).get('accuracy', 0.0):.3f}, coherence={summary.get('shuffled', {}).get('coherence', 0.0):.3f}, entropy={summary.get('shuffled', {}).get('entropy', 0.0):.3f}

        Wrote:
          - {output_path}
          - {output_path.parent / 'plot-accuracy-over-time.svg'}
          - {output_path.parent / 'plot-hypothesis-coherence.svg'}
          - {output_path.parent / 'plot-accuracy-summary.svg'}
          - {output_path.parent / 'summary.txt'}
        """
    ).strip()
    print(report)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
