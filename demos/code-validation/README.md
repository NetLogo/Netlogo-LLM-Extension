# Code Validation

A demonstration of `llm:compile-error` — the gate that makes the "LLM writes NetLogo code,
the model runs it" pattern safe enough to put in front of students.

**No API key required.** The candidate rules are a fixed pool, so this demo runs offline.

## The Problem

Several patterns in this repo have a model generate NetLogo code that the simulation then
executes (`demos/templates/code-evolution-template.yaml`, `movement-evolution.yaml`).
Generated code goes straight to `run`, and it cannot be trusted in two distinct ways:

1. **It may not compile.** Invalid code throws mid-tick, killing the run.
2. **It may compile and still be unwanted.** `die` in a generated agent rule kills the
   population; `clear-all` wipes the experiment. Compiling is not the same as safe.

`llm:compile-error` closes both. It reports `""` when code is acceptable, and otherwise
the reason it was refused.

## How It Works

Every tick, each turtle draws a candidate rule from a pool and passes it through the gate:

```netlogo
to-report acceptable? [code]
  let verdict (llm:compile-error code banned-primitives)
  ...
end
```

Turtles whose candidate passes adopt the new rule and turn **green**. Turtles whose
candidate is refused keep their previous rule and turn **red**.

The candidate pool deliberately mixes three kinds of code, so both failure modes are
visible on every run:

| Candidate | Verdict |
|---|---|
| `fd 1` | passes |
| `rt random 30 fd 1` | passes |
| `ifelse random 2 = 0 [ lt 10 fd 1 ] [ rt 10 fd 1 ]` | passes |
| `frobnicate 1` | fails — no such primitive |
| `ifelse xcor > 0 [ fd 1 ]` | fails — missing second block |
| `die` | compiles, refused — banned |
| `ask turtles [ fd 1 ]` | compiles, refused — banned |

## How to Run

1. Build and install the extension: `./build.sh` from the repo root.
2. Open `code-validation.nlogox` in NetLogo 7.0.3.
3. Press **setup**, then **go**. Watch the accepted/rejected monitors diverge.
4. Press **check all rules** to print the gate's verdict on every candidate.

## Things to Notice

- Compile failures report a **character offset**, so a model can point at where the
  generated code went wrong.
- `die` and `ask turtles [ fd 1 ]` compile perfectly well and are still refused. This is
  the case an external syntax checker cannot cover.
- Matching is **token-based**, not substring. `die` inside a comment, inside a string
  literal, or inside a longer identifier such as `diehard` does not trigger a false
  positive — each of those would otherwise discard code that was valid.
- The banned list lives in NetLogo (`banned-primitives`), not in the extension. What
  counts as dangerous is model-specific, so the policy can change without a rebuild.

## Making It Live

Replace `candidate-rules` with a call to `llm:chat`:

```netlogo
to-report candidate-rules
  report (list llm:chat "Write one line of NetLogo commands to move this turtle.")
end
```

The gate does not change. That is the point of it.

## Scope

This proves the code **compiles**, not that it cannot fail at runtime. Bounds errors such
as `item 3` on a three-element list still surface only during execution, so keep
`carefully` around `run`.

## Related

- API reference: `docs/API-REFERENCE.md` → Code Validation
- Issue [#52](https://github.com/NetLogo/Netlogo-LLM-Extension/issues/52)
