# Schelling Segregation Demo

This demo shows a standard Schelling-style result: mild local preferences can produce strong global segregation. Red and blue households only ask for a modest share of similar neighbors, but repeated relocation still creates visibly separate clusters.

## What Emerges

With the default settings, the world starts mixed and quickly sorts itself into same-color regions.

- A low tolerance still creates substantial separation.
- Agents become nearly all happy after only a few ticks.
- The segregation index rises much faster than the individual preference threshold.

## Key Insight

At the default configuration (`num-agents = 450`, `empty-rate = 15`, `similar-wanted = 30`), the model typically settles near 80% segregation even though agents only require about 30% similar neighbors to stay put.

## How To Run

1. Open `schelling-segregation.nlogo` in NetLogo 7.
2. Click `setup`.
3. Click `go` and let it run until movement stops.
4. Watch the `Segregation %`, `Happy Agents`, and `Empty Patches` monitors, plus the plot over time.

## BehaviorSpace Sweep

The `BehaviorSpace/` folder contains `sweep.xml`, which sweeps `similar-wanted` from 10 to 50 while holding the other defaults fixed.

- Metrics collected: `ticks`, `segregation-index`, `happy-count`, `empty-patches`, `mean-similar-share`
- Repetitions: `10`
- Exit condition: `happy-count = count turtles`

The same experiment is also embedded in the `.nlogo` model metadata.
