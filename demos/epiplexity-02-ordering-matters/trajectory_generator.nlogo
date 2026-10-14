globals [
  tick-counter
  log-file
  simulation-seed
]

breed [foragers forager]

patches-own [
  resource-level
]

foragers-own [
  energy
  state
  age
  agent-id
]

to setup
  clear-all
  set simulation-seed 20260226
  random-seed simulation-seed

  resize-world -25 24 -25 24
  set-patch-size 10

  set log-file "data/trajectory-raw.txt"
  if file-exists? log-file [
    file-delete log-file
  ]

  ask patches [
    set resource-level random 11
    set pcolor scale-color green resource-level 0 10
  ]

  create-foragers 5 [
    set agent-id (word "agent" (who + 1))
    setxy random-xcor random-ycor
    set shape "person"
    set color orange
    set size 1.2
    set energy 50
    set state "hungry"
    set age 0
    set label agent-id
    set label-color black
  ]

  set tick-counter 0
  reset-ticks
end

to go
  if ticks >= 100 [
    stop
  ]

  set tick-counter ticks + 1

  ; Patch regeneration (+1, capped at 10).
  ask patches [
    set resource-level min list 10 (resource-level + 1)
    set pcolor scale-color green resource-level 0 10
  ]

  ask sort foragers [
    process-forager
  ]

  tick
end

to run-100
  setup
  repeat 100 [
    if not any? foragers [ stop ]
    go
  ]
end

to process-forager
  set age age + 1
  let action-name "rest"

  if energy < 30 [
    set state "hungry"
  ]

  if state = "hungry" [
    if [resource-level] of patch-here >= 2 [
      consume-resource
      set action-name "eat"
    ]

    if action-name != "eat" [
      ifelse move-to-rich-neighbor [
        set action-name "move"
      ] [
        deterministic-migrate
        set action-name "migrate"
      ]
    ]
  ]

  if energy > 70 and action-name = "rest" [
    set state "satiated"
  ]

  if member? state ["satiated" "resting"] and action-name = "rest" [
    set state "resting"
  ]

  set energy energy - 1

  if state = "resting" and energy <= 55 [
    set state "hungry"
  ]

  if energy <= 0 [
    set energy 0
    set action-name "die"
    log-event action-name
    die
    stop
  ]

  log-event action-name
end

to consume-resource
  set energy min list 100 (energy + 20)
  ask patch-here [
    set resource-level max list 0 (resource-level - 2)
    set pcolor scale-color green resource-level 0 10
  ]
end

to-report move-to-rich-neighbor
  let candidates patch-set patch-here neighbors4
  if not any? candidates [
    report false
  ]

  let sorted-candidates sort-by [[a b] ->
    ifelse-value ([resource-level] of a != [resource-level] of b)
      [[resource-level] of a > [resource-level] of b]
      [ifelse-value ([pxcor] of a != [pxcor] of b)
        [[pxcor] of a < [pxcor] of b]
        [[pycor] of a < [pycor] of b]]
  ] candidates

  let best first sorted-candidates
  if best = patch-here [
    report false
  ]

  move-to best
  report true
end

to deterministic-migrate
  let candidates sort neighbors4
  if any? candidates [
    let idx (who + ticks) mod count candidates
    move-to item idx candidates
  ]
end

to log-event [action-name]
  file-open log-file
  file-print (word
    tick-counter ","
    agent-id ","
    round xcor ","
    round ycor ","
    energy ","
    state ","
    action-name)
  file-close
end
@#$#@#$#@
GRAPHICS-WINDOW
214
10
704
501
-1
-1
9.5
1
10
1
1
1
0
0
0
1
-25
24
-25
24
0
0
1
ticks
30.0

BUTTON
16
18
88
51
setup
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
102
18
174
51
go
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

BUTTON
16
62
174
95
run-100
run-100
NIL
1
T
OBSERVER
NIL
NIL
NIL
NIL
1

MONITOR
16
110
120
155
foragers
count foragers
17
1
11

MONITOR
130
110
210
155
tick
ticks
17
1
11

TEXTBOX
16
168
199
267
Deterministic trajectory generator\nfor Epiplexity Demo 2.\n\nRun `run-100` to create:\n data/trajectory-raw.txt
11
0.0
1

@#$#@#$#@
## WHAT IS IT?

Deterministic trajectory generator for Epiplexity Demo 2 (Ordering Matters).

## HOW IT WORKS

- 5 foragers move on a 50x50 world with regenerating patch resources.
- Energy drives state transitions: hungry -> seek/eat, high-energy -> resting.
- Every active forager logs one row per tick to `data/trajectory-raw.txt`.
- The simulation runs for 100 ticks using a fixed random seed.

## OUTPUT FORMAT

`tick,agent_id,xcor,ycor,energy,state,action`

Example:
`1,agent1,10,15,49,hungry,move`

## HOW TO USE

1. Open this model.
2. Click `run-100`.
3. Confirm `data/trajectory-raw.txt` was generated.
4. Run `trajectory_analysis.py` in this same demo folder.
@#$#@#$#@
default
true
0
Polygon -7500403 true true 150 5 40 250 150 205 260 250

person
false
0
Circle -7500403 true true 110 5 80
Polygon -7500403 true true 105 90 120 195 90 285 105 300 135 195 165 300 180 285 150 195 165 90
Rectangle -7500403 true true 127 79 172 94
@#$#@#$#@
NetLogo 6.3.0
@#$#@#$#@
@#$#@#$#@
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
