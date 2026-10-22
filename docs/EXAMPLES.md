# NetLogo Multi-LLM Extension - Usage Examples

## Template System Examples

### Basic Template Usage

The extension supports YAML template files for structured prompting:

```netlogo
extensions [llm]

to setup
  llm:set-provider "openai"
  llm:set-model "gpt-6.1-sol"
end

to example-template-usage
  ; Use a template with variables
  let result llm:chat-with-template "demos/simple-template.yaml" (list
    ["task" "analyze data"]
    ["input" "sales figures: 100, 150, 200, 180"]
    ["context" "quarterly business review"]
  )
  
  print result
end
```

Example YAML template (`simple-template.yaml`):
```yaml
system: "You are a helpful assistant."
template: |
  Task: {task}
  Input: {input}
  Additional context: {context}
  
  Please complete the task with the given input and context.
```

### Code Evolution with Templates

Perfect for evolving agent behaviors:

```netlogo
extensions [llm]

turtles-own [
  current-code
]

to setup
  clear-all
  create-turtles 5
  llm:set-provider "openai"
  
  ask turtles [
    set current-code "fd 1"
    set color red
  ]
end

to evolve-turtle-code
  ask turtles [
    ; Track code evolution in history
    llm:chat (word "CODE_VERSION: " current-code)
    
    ; Get evolution history
    let code-history get-code-history
    
    ; Evolve using template
    let new-code llm:chat-with-template "demos/code-evolution-template.yaml" (list
      ["current_code" current-code]
      ["code_history" code-history]
      ["objective" "improve movement efficiency"]
      ["constraints" "NetLogo movement commands only"]
      ["performance_notes" (word "turtle " who " fitness: " random 100)]
    )
    
    set current-code new-code
    print (word "Turtle " who " evolved to: " new-code)
  ]
end

to-report get-code-history
  let history llm:history
  let versions []
  foreach history [ msg ->
    let role item 0 msg
    let content item 1 msg
    if role = "user" and (substring content 0 13) = "CODE_VERSION:" [
      set versions lput (substring content 14 (length content)) versions
    ]
  ]
  report (reduce word versions)
end
```

### Multi-Agent Analysis with Templates

Agents can use templates for consistent analysis:

```netlogo
extensions [llm]

breed [analysts analyst]

analysts-own [
  specialization
  analysis-count
]

to setup
  clear-all
  create-analysts 3
  llm:set-provider "openai"
  
  let specializations ["financial" "environmental" "social"]
  ask analysts [
    set specialization item who specializations
    set analysis-count 0
    set color (who * 45)
    set label specialization
  ]
end

to analyze-scenario
  let scenario user-input "Enter scenario to analyze:"
  if scenario = false [ stop ]
  
  ask analysts [
    let analysis llm:chat-with-template "demos/analysis-template.yaml" (list
      ["data" scenario]
      ["context" (word specialization " perspective")]
      ["goal" "identify key issues and recommendations"]
      ["trends" "current market conditions"]
    )
    
    set analysis-count analysis-count + 1
    print (word specialization " analyst says:")
    print analysis
    print "---"
  ]
end
```

### Template Variables and History Integration

Using conversation history with templates:

```netlogo
extensions [llm]

globals [
  session-context
]

to setup
  set session-context "scientific research discussion"
  llm:set-provider "openai"
end

to research-discussion
  ; Build context from conversation history
  let previous-discussion ""
  let history llm:history
  if length history > 0 [
    set previous-discussion (word "Previous discussion: " 
                                 (reduce word (map [msg -> item 1 msg] history)))
  ]
  
  let topic user-input "Research topic:"
  if topic = false [ stop ]
  
  let research-response llm:chat-with-template "demos/reasoning-template.yaml" (list
    ["problem" topic]
    ["available_info" previous-discussion]
    ["constraints" "academic rigor required"]
    ["success_criteria" "actionable research directions"]
  )
  
  print research-response
end
```

### Advanced Template Usage with Agent Memory

Agents maintaining their own template-based conversations:

```netlogo
extensions [llm]

breed [researchers researcher]

researchers-own [
  research-focus
  discovery-log
  hypothesis-count
]

to setup
  clear-all
  create-researchers 4
  llm:set-provider "openai"
  
  let focuses ["AI" "biology" "physics" "chemistry"]
  ask researchers [
    set research-focus item who focuses
    set discovery-log []
    set hypothesis-count 0
    setxy random-xcor random-ycor
    set color (who * 40)
    set label research-focus
  ]
end

to generate-hypothesis
  ask researchers [
    ; Each researcher uses their own conversation context
    let hypothesis llm:chat-with-template "demos/reasoning-template.yaml" (list
      ["problem" (word "Generate novel research hypothesis in " research-focus)]
      ["available_info" (word "Current knowledge in " research-focus)]
      ["constraints" "Must be testable and innovative"]
      ["success_criteria" "Advance field knowledge"]
    )
    
    set discovery-log lput hypothesis discovery-log
    set hypothesis-count hypothesis-count + 1
    
    print (word research-focus " researcher hypothesis #" hypothesis-count ":")
    print hypothesis
    print ""
  ]
end

to collaborate-on-problem
  let problem user-input "Enter interdisciplinary problem:"
  if problem = false [ stop ]
  
  ; Each researcher analyzes from their perspective
  ask researchers [
    let analysis llm:chat-with-template "demos/analysis-template.yaml" (list
      ["data" problem]
      ["context" (word research-focus " field expertise")]
      ["goal" "provide disciplinary insights"]
      ["trends" (word "recent advances in " research-focus)]
    )
    
    print (word research-focus " perspective:")
    print analysis
    print "---"
  ]
  
  ; Synthesize perspectives
  let all-perspectives ""
  ask researchers [
    let last-msg last llm:history
    set all-perspectives (word all-perspectives research-focus ": " item 1 last-msg "\n")
  ]
  
  ; Use one researcher to synthesize
  ask one-of researchers [
    let synthesis llm:chat-with-template "demos/reasoning-template.yaml" (list
      ["problem" (word "Synthesize interdisciplinary solutions for: " problem)]
      ["available_info" all-perspectives]
      ["constraints" "Integrate all disciplinary perspectives"]
      ["success_criteria" "Comprehensive interdisciplinary solution"]
    )
    
    print "\n=== INTERDISCIPLINARY SYNTHESIS ==="
    print synthesis
    print "======================================"
  ]
end
```

## Basic Usage Examples

### Simple Chat Bot

Create a basic chat interface in NetLogo:

```netlogo
extensions [llm]

to setup
  llm:load-config "config.txt"
  print "Chat bot ready! Use 'ask-question' to interact."
end

to ask-question
  let question user-input "What would you like to ask the AI?"
  if question != false [
    let response llm:chat question
    user-message response "AI Response"
  ]
end
```

### Multi-Provider Comparison

Compare responses from different providers:

```netlogo
extensions [llm]

globals [providers-list current-question]

to setup
  set providers-list ["openai" "anthropic" "gemini" "openrouter" "together"]
  set current-question ""
end

to compare-providers
  set current-question user-input "Enter question to compare across providers:"
  if current-question != false [
    foreach providers-list [ provider ->
      carefully [
        llm:set-provider provider
        llm:load-config (word provider "-config.txt")
        let response llm:chat current-question
        print (word provider ": " response)
        print "---"
      ] [
        print (word "Error with " provider ": " error-message)
      ]
    ]
  ]
end
```

## Agent-Based Examples

### Personality-Driven Agents

Create agents with distinct personalities:

```netlogo
extensions [llm]

turtles-own [
  personality
  conversation-style
  response-count
]

to setup
  clear-all
  create-turtles 5
  llm:load-config "config.txt"
  
  let personalities ["optimistic" "analytical" "creative" "skeptical" "practical"]
  let styles ["enthusiastic" "precise" "imaginative" "questioning" "straightforward"]
  
  ask turtles [
    set personality item who personalities
    set conversation-style item who styles
    set response-count 0
    
    ; Set initial conversation context for each turtle
    let context (word "You are a " personality " and " conversation-style " assistant. ")
    set context (word context "Always respond in character with your " personality " personality.")
    llm:set-history (list context (word "I understand. I'm " personality " and " conversation-style "."))
    
    set color scale-color red who 0 5
    set label personality
  ]
end

to group-discussion
  let topic user-input "What topic should the agents discuss?"
  if topic != false [
    ask turtles [
      let response llm:chat (word "What's your " personality " perspective on: " topic)
      print (word personality " turtle says: " response)
      set response-count response-count + 1
    ]
  ]
end
```

### Decision-Making Agents

Agents that make decisions based on their environment:

```netlogo
extensions [llm]

turtles-own [
  current-goal
  energy
  last-decision
]

patches-own [
  resource-type
  resource-amount
]

to setup
  clear-all
  create-turtles 10
  setup-environment
  llm:load-config "config.txt"
  
  ask turtles [
    set energy 50 + random 50
    set current-goal "explore"
    setxy random-xcor random-ycor
    set color blue
  ]
end

to setup-environment
  ask patches [
    if random 100 < 20 [
      set resource-type one-of ["food" "water" "shelter"]
      set resource-amount 1 + random 5
      set pcolor (ifelse resource-type = "food" [green]
                          resource-type = "water" [cyan]
                          [brown])
    ]
  ]
end

to make-decisions
  ask turtles [
    let context build-context
    let options ["move-forward" "turn-left" "turn-right" "collect-resource" "rest"]
    
    set last-decision llm:choose context options
    execute-decision last-decision
    
    set energy energy - 1
    if energy <= 0 [ die ]
  ]
end

to-report build-context
  let nearby-resources count patches in-radius 2 with [resource-amount > 0]
  let nearby-turtles count other turtles in-radius 3
  
  let context (word "I'm a turtle with " energy " energy. ")
  set context (word context "My goal is to " current-goal ". ")
  set context (word context "I see " nearby-resources " resource patches nearby and ")
  set context (word context nearby-turtles " other turtles. ")
  
  if any? patches in-radius 1 with [resource-amount > 0] [
    let resource-here [resource-type] of one-of patches in-radius 1 with [resource-amount > 0]
    set context (word context "There's " resource-here " right here. ")
  ]
  
  set context (word context "What should I do?")
  report context
end

to execute-decision [decision]
  if decision = "move-forward" [
    forward 1
    if energy < 30 [ set current-goal "find-food" ]
  ]
  if decision = "turn-left" [ left 45 + random 90 ]
  if decision = "turn-right" [ right 45 + random 90 ]
  if decision = "collect-resource" [
    if any? patches in-radius 1 with [resource-amount > 0] [
      let target one-of patches in-radius 1 with [resource-amount > 0]
      ask target [
        set resource-amount resource-amount - 1
        if resource-amount <= 0 [ set pcolor black ]
      ]
      set energy energy + 10
      set current-goal "explore"
    ]
  ]
  if decision = "rest" [
    set energy energy + 5
  ]
end
```

## Advanced Usage Examples

### Reasoning Models — Show the Model's Work

Use `llm:chat-with-thinking` to get both the final answer and the model's intermediate reasoning. Works with Anthropic Claude, Gemini 2.5 and 3.x, Ollama qwen3 / deepseek-r1, OpenRouter reasoning models, Groq gpt-oss, and Together AI reasoning models.

```netlogo
extensions [llm]

to setup-reasoning
  llm:load-config "config.txt"        ;; e.g. provider=together, model=deepseek-ai/DeepSeek-R1
  llm:set-thinking true
  llm:set-reasoning-effort "high"     ;; OpenAI o-series + OpenRouter + Together hybrid
  llm:set-thinking-budget 4096        ;; Anthropic + Gemini
end

to ask-with-reasoning [ question ]
  let result llm:chat-with-thinking question
  let answer   item 0 result
  let thinking item 1 result
  print (word "Q: " question)
  print (word "Reasoning: " thinking)
  print (word "Answer: " answer)
end

;; ask-with-reasoning "If a train leaves Chicago at 3pm going 60 mph, ..."
```

Note: bump `max_tokens` to 2000+ in your config for reasoning models; they often spend most of their token budget on the thinking phase before producing the final answer.

### OpenRouter — One Key, Many Models

Switch between vendors without juggling separate API keys:

```netlogo
extensions [llm]

to setup
  llm:set-provider "openrouter"
  llm:set-api-key "sk-or-your-key"
end

to compare-vendors-via-openrouter
  let prompt "Summarize agent-based modeling in one sentence."
  foreach ["openai/gpt-oss-20b" "anthropic/claude-haiku-5.5" "meta-llama/llama-3.3-70b-instruct"] [ m ->
    llm:set-model m
    print (word m ": " llm:chat prompt)
    llm:clear-history
  ]
end
```

### Async Processing with Multiple Requests

Handle multiple LLM requests simultaneously:

```netlogo
extensions [llm]

globals [
  request-queue
  completed-responses
  processing-count
]

to setup
  set request-queue []
  set completed-responses []
  set processing-count 0
  llm:load-config "config.txt"
end

to batch-process-questions
  let questions [
    "What is artificial intelligence?"
    "Explain machine learning"
    "How do neural networks work?"
    "What is deep learning?"
    "Describe natural language processing"
  ]
  
  print (word "Starting batch processing of " length questions " questions...")
  
  foreach questions [ question ->
    let awaitable llm:chat-async question
    set request-queue lput (list question awaitable ticks) request-queue
    set processing-count processing-count + 1
  ]
  
  print (word "All requests queued. Use 'check-progress' to monitor.")
end

to check-progress
  let still-pending []
  let newly-completed 0
  
  foreach request-queue [ request ->
    let question first request
    let awaitable item 1 request
    let start-time item 2 request
    
    carefully [
      let response runresult awaitable
      set completed-responses lput (list question response (ticks - start-time)) completed-responses
      set newly-completed newly-completed + 1
      print (word "✓ Completed: " question)
    ] [
      ; Still processing
      set still-pending lput request still-pending
    ]
  ]
  
  set request-queue still-pending
  set processing-count processing-count - newly-completed
  
  print (word "Progress: " length completed-responses " completed, " 
              processing-count " still processing")
  
  if processing-count = 0 [
    print "All requests completed!"
    show-results
  ]
end

to show-results
  print "\n=== BATCH PROCESSING RESULTS ==="
  foreach completed-responses [ result ->
    let question first result
    let response item 1 result
    let duration item 2 result
    print (word "Q: " question)
    print (word "A: " response)
    print (word "Time: " duration " ticks")
    print "---"
  ]
end
```

### Conversation Memory Management

Manage conversation history across simulation runs:

```netlogo
extensions [llm]

globals [
  conversation-log
  session-id
]

turtles-own [
  conversation-file
  memory-size
]

to setup
  clear-all
  create-turtles 3
  llm:load-config "config.txt"
  
  set session-id (word "session-" date-and-time)
  set conversation-log []
  
  ask turtles [
    set conversation-file (word "memory/turtle-" who "-memory.txt")
    set memory-size 10  ; Keep last 10 exchanges
    
    ; Load previous conversation if exists
    carefully [
      let saved-history read-conversation-file conversation-file
      if length saved-history > 0 [
        llm:set-history saved-history
        print (word "Turtle " who " loaded " (length saved-history / 2) " previous exchanges")
      ]
    ] [
      print (word "No previous memory found for turtle " who)
    ]
  ]
end

to-report read-conversation-file [filename]
  ; This is a simplified example - in practice you'd implement file I/O
  ; For now, return empty list
  report []
end

to chat-with-memory
  ask turtles [
    let question (word "Hello, I'm turtle " who ". How are you today?")
    let response llm:chat question
    
    ; Log the conversation
    set conversation-log lput (list who question response ticks) conversation-log
    
    ; Manage memory size
    let current-history llm:history
    if length current-history > (memory-size * 2) [
      ; Keep only recent exchanges
      let recent-history sublist current-history 
                                 (length current-history - (memory-size * 2))
                                 (length current-history)
      llm:set-history recent-history
    ]
    
    print (word "Turtle " who ": " response)
    
    ; Save conversation periodically
    if ticks mod 10 = 0 [
      save-conversation-file conversation-file llm:history
    ]
  ]
end

to save-conversation-file [filename history]
  ; This is a placeholder - implement actual file writing
  print (word "Saving " length history " messages to " filename)
end
```

### Multi-Modal Decision Trees

Complex decision-making with contextual reasoning:

```netlogo
extensions [llm]

globals [
  scenario-context
  decision-tree
]

breed [decision-makers decision-maker]
breed [options option]

decision-makers-own [
  role
  expertise
  decision-history
  confidence-level
]

to setup
  clear-all
  create-decision-makers 4
  llm:load-config "config.txt"
  
  set scenario-context "Resource allocation for sustainable city planning"
  
  ask decision-makers [
    set role one-of ["economist" "environmentalist" "urban-planner" "social-scientist"]
    set expertise random 100
    set decision-history []
    set confidence-level 0.5
    
    ; Set role-specific context
    let role-context get-role-context role
    llm:set-history (list role-context (word "I understand. I'm a " role " expert."))
    
    setxy random-xcor random-ycor
    set color get-role-color role
    set label role
  ]
end

to-report get-role-context [agent-role]
  let contexts table:make
  table:put contexts "economist" "You are an economic expert focused on cost-effectiveness and financial sustainability."
  table:put contexts "environmentalist" "You are an environmental expert focused on ecological impact and sustainability."
  table:put contexts "urban-planner" "You are an urban planning expert focused on infrastructure and community development."
  table:put contexts "social-scientist" "You are a social science expert focused on community needs and social equity."
  report table:get contexts agent-role
end

to-report get-role-color [agent-role]
  if agent-role = "economist" [ report red ]
  if agent-role = "environmentalist" [ report green ]
  if agent-role = "urban-planner" [ report blue ]
  if agent-role = "social-scientist" [ report yellow ]
  report gray
end

to collaborative-decision
  let problem user-input "Describe the decision problem:"
  if problem = false [ stop ]
  
  set scenario-context (word scenario-context ": " problem)
  
  ; Phase 1: Individual analysis
  ask decision-makers [
    let context (word "As a " role " expert, analyze this problem: " problem)
    set context (word context " Provide your perspective and concerns.")
    
    let analysis llm:chat context
    set decision-history lput (list "analysis" analysis ticks) decision-history
    
    print (word role " analysis:")
    print analysis
    print "---"
  ]
  
  ; Phase 2: Collaborative discussion
  let all-analyses []
  ask decision-makers [
    let latest-analysis last [item 1 item 0 decision-history] of decision-makers with [who = [who] of myself]
    set all-analyses lput (word role ": " latest-analysis) all-analyses
  ]
  
  ask decision-makers [
    let others-views reduce word all-analyses
    let discussion-prompt (word "Given these other expert perspectives: " others-views)
    set discussion-prompt (word discussion-prompt " How would you refine your recommendation?")
    
    let refined-view llm:chat discussion-prompt
    set decision-history lput (list "refinement" refined-view ticks) decision-history
    
    print (word role " refined view:")
    print refined-view
    print "---"
  ]
  
  ; Phase 3: Final recommendation
  synthesize-recommendations
end

to synthesize-recommendations
  let all-refined-views []
  ask decision-makers [
    let latest-refinement last [item 1 last decision-history] of decision-makers with [who = [who] of myself]
    set all-refined-views lput (word role ": " latest-refinement) all-refined-views
  ]
  
  ; Use one agent to synthesize
  ask one-of decision-makers [
    let synthesis-prompt "Given all expert perspectives below, provide a balanced synthesis and recommendation: "
    set synthesis-prompt (word synthesis-prompt reduce word all-refined-views)
    
    let final-recommendation llm:chat synthesis-prompt
    
    print "\n=== FINAL COLLABORATIVE RECOMMENDATION ==="
    print final-recommendation
    print "==========================================="
  ]
end
```

## Specialized Use Cases

### Scientific Hypothesis Generation

Generate and evaluate research hypotheses:

```netlogo
extensions [llm]

globals [
  research-domain
  hypotheses-generated
  evaluation-criteria
]

to setup
  set research-domain ""
  set hypotheses-generated []
  set evaluation-criteria ["novelty" "feasibility" "testability" "significance"]
  llm:load-config "config.txt"
end

to generate-hypotheses
  set research-domain user-input "Enter research domain (e.g., 'climate change', 'social behavior'):"
  if research-domain = false [ stop ]
  
  print (word "Generating hypotheses for: " research-domain)
  
  repeat 5 [
    let hypothesis-prompt (word "Generate a novel, testable research hypothesis in the field of " research-domain ". ")
    set hypothesis-prompt (word hypothesis-prompt "Focus on gaps in current knowledge and practical implications.")
    
    let hypothesis llm:chat hypothesis-prompt
    set hypotheses-generated lput hypothesis hypotheses-generated
    
    print (word "Hypothesis " length hypotheses-generated ": " hypothesis)
    print ""
    
    ; Clear history to get diverse hypotheses
    llm:clear-history
  ]
  
  evaluate-hypotheses
end

to evaluate-hypotheses
  print "=== HYPOTHESIS EVALUATION ==="
  
  let evaluation-results []
  
  foreach hypotheses-generated [ hypothesis ->
    let hypothesis-num (position hypothesis hypotheses-generated) + 1
    print (word "Evaluating Hypothesis " hypothesis-num "...")
    
    let scores []
    foreach evaluation-criteria [ criterion ->
      let eval-prompt (word "Rate the following research hypothesis on " criterion " ")
      set eval-prompt (word eval-prompt "from 1-10 (10 being highest). ")
      set eval-prompt (word eval-prompt "Provide only the number and brief justification: " hypothesis)
      
      let evaluation llm:chat eval-prompt
      ; Extract numeric score (simplified - would need better parsing)
      let score-str first (filter [s -> is-number? s] (map [s -> first s] (split evaluation " ")))
      let score read-from-string score-str
      
      set scores lput (list criterion score evaluation) scores
      llm:clear-history  ; Independent evaluations
    ]
    
    set evaluation-results lput (list hypothesis-num hypothesis scores) evaluation-results
  ]
  
  display-evaluation-results evaluation-results
end

to display-evaluation-results [results]
  print "\n=== EVALUATION SUMMARY ==="
  
  foreach results [ result ->
    let hyp-num first result
    let hypothesis item 1 result
    let scores item 2 result
    
    print (word "Hypothesis " hyp-num ":")
    print hypothesis
    
    let total-score 0
    foreach scores [ score-data ->
      let criterion first score-data
      let score item 1 score-data
      let justification item 2 score-data
      
      print (word "  " criterion ": " score " - " justification)
      set total-score total-score + score
    ]
    
    let average-score total-score / length scores
    print (word "  Average Score: " precision average-score 2)
    print "---"
  ]
end

to-report is-number? [str]
  let result false
  carefully [
    let num read-from-string str
    if is-number? num [ set result true ]
  ] [ ]
  report result
end

to-report split [text delimiter]
  ; Simplified string splitting - would need better implementation
  let parts []
  let current-part ""
  
  let i 0
  while [i < length text] [
    let char substring text i (i + 1)
    ifelse char = delimiter [
      if length current-part > 0 [
        set parts lput current-part parts
        set current-part ""
      ]
    ] [
      set current-part (word current-part char)
    ]
    set i i + 1
  ]
  
  if length current-part > 0 [
    set parts lput current-part parts
  ]
  
  report parts
end
```

## Performance and Optimization Examples

### Efficient Batch Processing

Optimize for high-throughput scenarios:

```netlogo
extensions [llm]

globals [
  batch-size
  processing-stats
  optimization-mode
]

to setup
  set batch-size 10
  set processing-stats table:make
  set optimization-mode "balanced"  ; "speed", "quality", "balanced"
  
  ; Initialize stats
  table:put processing-stats "total-requests" 0
  table:put processing-stats "total-time" 0
  table:put processing-stats "errors" 0
  
  llm:load-config "config.txt"
  optimize-for-mode optimization-mode
end

to optimize-for-mode [mode]
  if mode = "speed" [
    llm:set-model "gpt-6-luna"  ; Fastest model
    ; Set low temperature and tokens for speed
  ]
  if mode = "quality" [
    llm:set-model "gpt-6-astra"  ; Best model
    ; Allow higher tokens and temperature
  ]
  if mode = "balanced" [
    llm:set-model "gpt-6.1-sol"  ; Good balance
    ; Moderate settings
  ]
end

to benchmark-processing
  let test-prompts generate-test-prompts 50
  
  print (word "Benchmarking " length test-prompts " requests in " optimization-mode " mode...")
  
  let start-time timer
  let completed 0
  let errors 0
  
  let batches group-into-batches test-prompts batch-size
  
  foreach batches [ batch ->
    let batch-start timer
    let batch-results process-batch batch
    let batch-time timer - batch-start
    
    set completed completed + length batch-results
    set errors errors + count-errors batch-results
    
    print (word "Batch completed: " length batch-results " requests in " 
              precision batch-time 2 " seconds")
  ]
  
  let total-time timer - start-time
  
  ; Update stats
  table:put processing-stats "total-requests" 
           (table:get processing-stats "total-requests" + completed)
  table:put processing-stats "total-time" 
           (table:get processing-stats "total-time" + total-time)
  table:put processing-stats "errors" 
           (table:get processing-stats "errors" + errors)
  
  print-benchmark-results completed errors total-time
end

to-report generate-test-prompts [count]
  let prompts []
  repeat count [
    let prompt one-of [
      "What is the capital of France?"
      "Explain photosynthesis briefly."
      "What is 15 + 27?"
      "Name three primary colors."
      "What day comes after Tuesday?"
    ]
    set prompts lput prompt prompts
  ]
  report prompts
end

to-report group-into-batches [items batch-size]
  let batches []
  let current-batch []
  
  foreach items [ item ->
    set current-batch lput item current-batch
    if length current-batch = batch-size [
      set batches lput current-batch batches
      set current-batch []
    ]
  ]
  
  if length current-batch > 0 [
    set batches lput current-batch batches
  ]
  
  report batches
end

to-report process-batch [prompts]
  let awaitables []
  let results []
  
  ; Start all requests asynchronously
  foreach prompts [ prompt ->
    let awaitable llm:chat-async prompt
    set awaitables lput (list prompt awaitable) awaitables
  ]
  
  ; Collect results
  foreach awaitables [ awaitable-pair ->
    let prompt first awaitable-pair
    let awaitable last awaitable-pair
    
    carefully [
      let response runresult awaitable
      set results lput (list prompt response "success") results
    ] [
      set results lput (list prompt error-message "error") results
    ]
  ]
  
  report results
end

to-report count-errors [results]
  let error-count 0
  foreach results [ result ->
    if last result = "error" [
      set error-count error-count + 1
    ]
  ]
  report error-count
end

to print-benchmark-results [completed errors total-time]
  print "\n=== BENCHMARK RESULTS ==="
  print (word "Requests completed: " completed)
  print (word "Errors: " errors " (" precision (errors / completed * 100) 2 "%)")
  print (word "Total time: " precision total-time 2 " seconds")
  print (word "Requests per second: " precision (completed / total-time) 2)
  print (word "Average time per request: " precision (total-time / completed) 2 " seconds")
  
  ; Lifetime stats
  let lifetime-requests table:get processing-stats "total-requests"
  let lifetime-time table:get processing-stats "total-time"
  print (word "Lifetime stats: " lifetime-requests " requests in " 
              precision lifetime-time 2 " seconds")
  print "========================="
end
```

These examples demonstrate the full range of capabilities of the NetLogo Multi-LLM Extension, from simple chat interactions to complex multi-agent systems with sophisticated decision-making and memory management.
