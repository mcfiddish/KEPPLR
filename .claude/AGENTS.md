# AGENTS.md (Hybrid)

## Mode Selection

| Mode           | Behavior                |
| -------------- | ----------------------- |
| FAST (default) | Use compact rules       |
| STRICT         | Enforce full guardrails |

Default to STRICT if any of the following apply:

* task touches more than one file
* task is non-trivial (agent judgment: would a plan gap cause incorrect or irreversible output?)
* drift detected
* ambiguity impacts correctness
* agent violates a constraint

Otherwise default to FAST.

Once STRICT is activated in a session, it remains active for the remainder of the session unless explicitly reset by the user.

---

## Quick Reference

|           |                                                         |
| --------- | ------------------------------------------------------- |
| Modes     | `PLANNING` · `IMPLEMENTATION` · `REVIEW` · `DIFF`       |
| Default   | `PLANNING`                                              |
| Stop when | ambiguity · conflict · drift · CHECKPOINT               |
| Never     | code in PLANNING · out-of-scope edits · skip CHECKPOINT |
| End       | always output SESSION SUMMARY                           |

---

## Project Invariants (Always Active)

* Language / Framework / Naming conventions
* No external dependencies without approval
* Simulation logic uses sim-time only

If conflict:

* STOP
* State conflict (1 sentence)
* Await confirmation

---

## Core Rules (FAST)

* Follow MODE (default = PLANNING)
* No code outside IMPLEMENTATION
* Match output format exactly
* Prefer bullets, constraints, minimal diffs
* Never touch files outside `may-touch`, regardless of mode

---

## STRICT ADDITIONS (Activated when needed)

* Approved plan is binding
* Do not deviate or reinterpret
* Do not silently correct user input
* Do not introduce indirect behavior changes
* Do not batch work past a single logical unit
* Do not proceed after BLOCK

---

## MODES

### PLANNING

* No code
* Output: plan · assumptions · (≤2) questions

---

### IMPLEMENTATION

* Follow plan
* No redesign
* Justify abstractions (1 line)
* CHECKPOINT after each unit

If ambiguity or drift detected (by agent or user):

* STOP
* 1-line issue statement
* Await confirmation

Output: code only

---

### REVIEW

* Validate correctness + constraints
* Evaluate Acceptance Criteria if present

Output:

* PASS | WARN | BLOCK
* Issues
* Fixes

If BLOCK:

* STOP
* Await user revision or explicit override
* Do not self-resolve

---

### DIFF

* Minimal change only
* No indirect changes
* Stay within file scope

If extra changes required:

* List as "Required follow-on changes"
* STOP

---

## Task Template

```
MODE:

Goal:

Context:

Constraints:

Invariants:

Files:
  may-touch: []
  must-not-touch: []

Task:

Acceptance Criteria:

Output:
```

---

## Execution Rules

### Clarification

* Ask only if correctness impacted (≤2)
* Otherwise assume + state briefly

### Scope

* Only current step

### Failure

If constraint cannot be met:

* STOP
* 1-line explanation
* Do not attempt workaround unless requested

### Drift

If deviating from Goal or Task — whether detected by agent or user:

* STOP
* 1-line deviation statement
* Await confirmation

---

## Behavior Bias

* Prefer simplest valid solution
* Avoid over-engineering
* Do not repeat completed work

---

## External Actions

* No tools, installs, or network unless instructed

---

## SESSION SUMMARY (Required)

```
--- SESSION SUMMARY ---

Completed:

Decisions:

Deferred:

Next step:
```

---

## Final Rule

Clarity > Creativity
Constraints > Interpretation
Plan → then implement