breed [reds red]
breed [blues blue]

turtles-own [
  similar-share
  is-happy?
]

to setup
  clear-all
  set-default-shape turtles "circle"
  setup-agents
  reset-ticks
  update-happiness
  update-metrics-plot
end

to setup-agents
  ; Empty-rate caps how many patches can be occupied at setup.
  let available-slots floor (count patches * (100 - empty-rate) / 100)
  let total-agents min list num-agents available-slots
  let red-count floor (total-agents / 2)
  let occupied-patches n-of total-agents patches

  ask n-of red-count occupied-patches [
    sprout-reds 1 [
      set color red + 1
      set size 0.9
    ]
  ]

  ask occupied-patches with [not any? turtles-here] [
    sprout-blues 1 [
      set color blue + 1
      set size 0.9
    ]
  ]
end

to go
  if not any? turtles [ stop ]
  if happy-count = count turtles [ stop ]

  move-unhappy
  update-happiness
  tick
  update-metrics-plot
  if happy-count = count turtles [ stop ]
end

to move-unhappy
  ; Unhappy agents relocate to a random empty patch.
  ask turtles with [not is-happy?] [
    let destination one-of patches with [not any? turtles-here]
    if destination != nobody [
      move-to destination
    ]
  ]
end

to update-happiness
  ask turtles [
    let share similar-neighbor-share self
    set similar-share share * 100
    set is-happy? share >= (similar-wanted / 100)
  ]
end

to update-metrics-plot
  set-current-plot "Segregation Over Time"
  set-current-plot-pen "Segregation"
  plot segregation-index
  set-current-plot-pen "Happy %"
  ifelse any? turtles [
    plot (100 * happy-count / count turtles)
  ] [
    plot 0
  ]
end

to-report similar-neighbor-share [resident]
  let nearby [other turtles in-radius 1] of resident
  let total count nearby
  if total = 0 [
    report 1
  ]
  let similar count nearby with [breed = [breed] of resident]
  report similar / total
end

to-report happy? [resident]
  report similar-neighbor-share resident >= (similar-wanted / 100)
end

to-report segregation-index
  let total-similar sum [count other turtles in-radius 1 with [breed = [breed] of myself]] of turtles
  let total-neighbors sum [count other turtles in-radius 1] of turtles
  if total-neighbors = 0 [
    report 0
  ]
  report 100 * total-similar / total-neighbors
end

to-report happy-count
  report count turtles with [is-happy?]
end

to-report empty-patches
  report count patches with [not any? turtles-here]
end

to-report unhappy-count
  report count turtles with [not is-happy?]
end

to-report mean-similar-share
  ifelse any? turtles [
    report mean [similar-share] of turtles
  ] [
    report 0
  ]
end
@#$#@#$#@
GRAPHICS-WINDOW
233
10
670
448
-1
-1
13.0
1
10
1
1
1
0
1
1
1
-16
16
-16
16
1
1
1
ticks
30.0

BUTTON
20
20
95
53
NIL
setup
NIL
1
T
OBSERVER
NIL
NIL
NIL
NIL
1

BUTTON
110
20
185
53
NIL
go
T
1
T
OBSERVER
NIL
NIL
NIL
NIL
1

SLIDER
20
80
205
113
num-agents
num-agents
0
900
450.0
1
1
NIL
HORIZONTAL

SLIDER
20
125
205
158
similar-wanted
similar-wanted
0
100
30.0
1
1
%
HORIZONTAL

SLIDER
20
170
205
203
empty-rate
empty-rate
0
60
15.0
1
1
%
HORIZONTAL

MONITOR
690
20
820
65
Segregation %
segregation-index
2
1
11

MONITOR
690
80
820
125
Happy Agents
happy-count
0
1
11

MONITOR
690
140
820
185
Empty Patches
empty-patches
0
1
11

PLOT
690
210
1070
448
Segregation Over Time
ticks
percent
0.0
10.0
0.0
100.0
true
true
"" ""
PENS
"Segregation" 1.0 0 -2674135 true "" ""
"Happy %" 1.0 0 -13345367 true "" ""

@#$#@#$#@
## WHAT IS IT?

This is a standalone Schelling segregation model. Red and blue households want only a modest share of similar neighbors, yet the system still self-organizes into strongly separated clusters.

## HOW IT WORKS

Each agent looks at nearby occupied patches in radius 1. If the share of same-color neighbors is below `similar-wanted`, the agent is unhappy and moves to a random empty patch. Repeating that simple rule quickly amplifies local preferences into global segregation.

## HOW TO USE IT

Choose `num-agents`, `similar-wanted`, and `empty-rate`, click `setup`, then run `go`. The default settings are tuned so `similar-wanted = 30` typically settles near 80% segregation within a handful of ticks.

## THINGS TO NOTICE

Even tolerant agents create much more segregation than they explicitly ask for. The segregation plot usually rises quickly while the happy share approaches 100%.

## THINGS TO TRY

Compare `similar-wanted = 10`, `30`, and `50`. Also raise `num-agents` until empty space becomes scarce and movement slows down.

## EXTENDING THE MODEL

Try alternative neighborhood definitions, more than two groups, or movement rules that search for the best empty patch instead of a random one.

## NETLOGO FEATURES

The model stays pure NetLogo: no extensions, no includes, and BehaviorSpace metadata embedded directly in the `.nlogo` file.

## RELATED MODELS

The Schelling Segregation model in the NetLogo Models Library is the direct conceptual reference.

## CREDITS AND REFERENCES

Inspired by Thomas Schelling's classic segregation experiments and the broader agent-based modeling literature on emergent sorting.
@#$#@#$#@
default
true
0
Polygon -7500403 true true 150 5 40 250 150 205 260 250

circle
false
0
Circle -7500403 true true 0 0 300
@#$#@#$#@
NetLogo 7.0.0
@#$#@#$#@
@#$#@#$#@
@#$#@#$#@
<experiments>
  <experiment name="sweep-similar-wanted" repetitions="10" runMetricsEveryStep="false">
    <setup>setup</setup>
    <go>go</go>
    <exitCondition>happy-count = count turtles</exitCondition>
    <timeLimit steps="100"/>
    <metric>ticks</metric>
    <metric>segregation-index</metric>
    <metric>happy-count</metric>
    <metric>empty-patches</metric>
    <metric>mean-similar-share</metric>
    <enumeratedValueSet variable="num-agents">
      <value value="450"/>
    </enumeratedValueSet>
    <steppedValueSet variable="similar-wanted" first="10" step="5" last="50"/>
    <enumeratedValueSet variable="empty-rate">
      <value value="15"/>
    </enumeratedValueSet>
  </experiment>
</experiments>
@#$#@#$#@
@#$#@#$#@
default
0.0
-0.2 0 0.0 1.0
0.0 1 1.0 0.0
0.2 0 0.0 1.0
link direction
true
0
Line -7500403 true 150 150 90 180
Line -7500403 true 150 150 210 180
@#$#@#$#@
1
@#$#@#$#@
