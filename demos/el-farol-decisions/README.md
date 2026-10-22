# El Farol, Calibrated

The El Farol bar problem (W. Brian Arthur, 1994) played by 150 patrons who ask a
calibrated decision model what to do each night, using `llm:decide-all` and
`llm:choose` with TypeSafe's Jev.

The lesson is one choice every LLM-driven agent model makes: when the model gives you
probabilities, do you **sample** from them or **always take the top pick**?

## The model

One tick is one night. Each patron has a persona (student, cautious retiree,
fear-of-missing-out regular, night-shift nurse, newcomer) and remembers, in turtle
variables, the last three nights' attendance at each bar and how its own last visit to
each bar went. A bar is fun only with at most its capacity (by default 60% of the
patrons, split across 3 bars: 30 each). Jev is stateless, so a reporter builds the
whole memory into each prompt.

Each night `llm:decide-all` asks every patron three questions, each batch sent at once:

```netlogo
llm:decide-all patrons [ -> forecast-prompt ] (list "score" crowd-levels)  ; how crowded?
llm:decide-all patrons [ -> go-prompt ] ["yes-no"]                        ; go out?
llm:decide-all goers [ -> bar-prompt ] (list "choice" bar-names)          ; which bar?
```

The **decision-rule** chooser decides what a patron does with the answer:

| Rule | Go out | Which bar |
| ---- | ------ | --------- |
| sample from probabilities | `random-float 1 < P(yes)` | drawn from the choice distribution |
| always take the top pick | `yes` if likelier | the likeliest bar |

The **text LLM** backend asks one `llm:choose` question per patron instead, one patron
at a time ("The Owl", "Blue Note", "Corner Pub" or "stay home"). A text model names only
its top pick, so there is no forecast, nothing to sample, and it is much slower.

A failed call sends the patron home and adds to **failed calls**; the night goes on.

## Files

| File | Purpose |
| ---- | ------- |
| `el-farol-decisions.nlogox` | The model, with an embedded `headless-check` BehaviorSpace experiment |
| `config.txt.example` | Settings for the typesafe (Jev) backend; copy to `config.txt` |
| `config-text.txt.example` | Settings for the text LLM backend (Ollama by default); copy to `config-text.txt` |

`config.txt` and `config-text.txt` are gitignored, so your keys stay local.

## Run it

1. Copy `config.txt.example` to `config.txt` and set `typesafe_api_key`
   (key from https://console.typesafe.ai/).
2. Open `el-farol-decisions.nlogox`, press **setup**, then **one night** or **go**.
3. Switch **decision-rule** and press **setup** again to compare.

In the extension's zip the model sits in `demos/` next to the shared `config.txt`.
Turn on that file's TypeSafe block (`provider=typesafe`, `model=jev-latest`,
`typesafe_api_key`); setup says so if the loaded provider is not `typesafe`.

Headless, from this folder with the extension installed:

```bash
java -Djava.awt.headless=true -cp "<NetLogo classpath>" org.nlogo.headless.Main \
  --model el-farol-decisions.nlogox --experiment headless-check --table -
```

## Measured (Jev, `jev-latest`, 150 patrons, 3 bars of 30, 10 nights per rule)

| | sample from probabilities | always take the top pick |
| --- | --- | --- |
| patrons out per night (fun limit 90) | 59 to 103 | 0 to 150; 150 on 5 of 10 nights |
| mean P(go) vs fraction out, mean gap | 0.04 | 0.26 |
| ms per night (450 calls) | 2,700 to 4,200, mean 3,400 | 3,000 to 4,700, mean 4,200 |
| cost per night | about $0.008 | about $0.009 |
| failed calls | 0 | 0 |

What this shows, honestly:

- **Going out settles with sampling.** Jev's P(go) responds to the remembered
  attendance (it drops after a crowded night and rises after a quiet one), and sampling
  turns it into a crowd near the fun limit. Top pick sends everyone or no one.
- **The bar choice still herds under both rules.** Everyone remembers the same
  attendance, so Jev puts most of the choice probability on the bar that was quiet last
  night. Sampling spreads patrons only as much as the probabilities are spread: under
  sampling, a typical night had one bar near 55 and another near 2. Private memories
  would break this; see EXTENDING THE MODEL in the Info tab.
- **Cost and latency.** Three batches of 150 Jev calls take about 3 to 4 seconds and
  under a cent. The text backend with a local 3B model took 5 to 12 seconds for only
  10 patrons, one question each.

Results vary run to run: the starting history is random and Jev's probabilities are
read live.

## Credits

Arthur, W. B. (1994). Inductive reasoning and bounded rationality. *American Economic
Review*, 84(2), 406-411. Rand, W. and Wilensky, U. (2007). NetLogo El Farol model,
NetLogo Models Library.
