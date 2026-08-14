# Structured Outputs

A working demonstration of `llm:chat-with-schema`, `llm:chat-json`, and `llm:get` —
constraining a model's reply to a JSON Schema and reading the fields as real NetLogo
values instead of parsing a sentence.

## The Problem

`llm:chat` returns free text. A model that needs a number has to hope the phrasing
stays stable and pick it out of the string:

```netlogo
let reply llm:chat "How confident are you, 0 to 1?"
;=> "I'd say about 0.8, though it depends on the situation."
;   now what? substring? position? what if it says "eighty percent"?
```

That works until the model words things differently, and then it fails quietly.

## The Fix

```netlogo
let schema "{\"type\":\"object\",\"properties\":{\"action\":{\"type\":\"string\",\"enum\":[\"eat\",\"explore\",\"rest\"]},\"confidence\":{\"type\":\"number\"}},\"required\":[\"action\",\"confidence\"]}"

let reply llm:chat-with-schema "A turtle sees food. What now?" schema
;=> [[action eat] [confidence 0.9]]

let act  llm:get reply "action"      ;=> "eat"   constrained to the enum
let conf llm:get reply "confidence"  ;=> 0.9     a NUMBER, not text
```

## What the demo shows

Six turtles forage. Each tick every turtle sends its energy and whether it is
standing on food, and gets back a reply matching the schema above.

Two consequences are visible on screen:

- **`action` is enum-constrained**, so `act-on` compares it directly. No fuzzy
  matching, no fallback branch for unexpected wording.
- **`confidence` is a number**, so `recolor` writes `confidence >= 0.7`. Turtles
  above the threshold turn lime, the rest orange. That comparison is only possible
  because the value arrives typed.

Press **show decisions** for each turtle's parsed fields, or **raw JSON (no schema)**
to see what `llm:chat-json` gives instead — valid JSON, but still a string.

## How to Run

1. `cp config.txt.example config.txt` and add your key. `config.txt` is gitignored.
2. Open `structured-outputs.nlogox` in NetLogo 7.0.3.
3. Press **setup**, then **go**.

Structured output needs a provider that supports constrained decoding. Verified
against Groq; OpenAI, Anthropic, and Gemini also support it. Ollama depends on the
local model.

## How JSON maps into NetLogo

NetLogo has no dictionary type, so objects become `[key value]` pair lists:

| JSON | NetLogo |
|---|---|
| `{"a": 1}` | `[[a 1]]` |
| `[1, 2]` | `[1 2]` |
| `"text"` | string |
| `10` | number |
| `true` | boolean |
| `null` | `""` (NetLogo has no null) |

Nested objects are just more pair lists, so `llm:get` chains:

```netlogo
let stats llm:get reply "stats"     ;=> [[alive true] [speed 10]]
let speed llm:get stats "speed"     ;=> 10
```

## Gotchas

- **The schema is a JSON string, not a NetLogo list.** The escaped quotes are
  unavoidable. Passing a list raises an error naming the problem.
- **A missing key raises**, listing the keys that were available. Wrap `llm:get` in
  `carefully` when a field is genuinely optional.
- **Key matching is exact and case-sensitive**, because JSON keys are.
- **The schema constrains shape, not truth.** A well-formed reply can still be a bad
  decision — this removes parsing failures, not model error.

## Verification

Run headless against live Groq: 12 schema-constrained calls across 2 ticks, 0
failures. Turtles standing on food chose `eat` at 0.9 confidence while others chose
`explore` at 0.8 — the replies track state rather than repeating a default.

## Related

- API reference: `docs/API-REFERENCE.md` → Structured Output
- Issue [#22](https://github.com/NetLogo/Netlogo-LLM-Extension/issues/22)
