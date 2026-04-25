# AGENTS.md (Hybrid)

This is the canonical agent-instructions document for KEPPLR.
Read it at the start of every session before making changes.

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

## Project Overview

KEPPLR is a deterministic, interactive 3D solar system simulator written in pure Java.

- **Rendering:** JMonkeyEngine (JME)
- **UI:** JavaFX control window (separate window, separate thread)
- **Ephemeris:** Picante (SPICE-compatible)
- **Build:** Maven
- **Scripting:** Groovy
- **Requirements authority:** `REDESIGN.md` in the `.claude` directory

When in doubt about intended behavior, consult `REDESIGN.md` before asking or guessing.

---

## Reference Code

The prototype codebase lives at `../KEPPLR-pre` (relative to this project root).
It exists solely as a reference — to understand prior decisions, algorithms,
and patterns. It is **not** a starting point to copy from.

### Rules for using reference code

- **Read it to understand intent.** If a requirement in `REDESIGN.md` is ambiguous,
  the prototype implementation may clarify what was meant.
- **Do not copy classes, methods, or logic blocks verbatim.** The prototype's structural
  problems are what this rewrite exists to fix. Copying code carries those problems forward.
- **Do not import or depend on anything in the reference directory.** It must never appear
  in `pom.xml` or any `import` statement in the new codebase.
- **Extracting an algorithm is acceptable** — re-implement it cleanly in the correct
  package with tests, rather than lifting the original.
- When referencing prototype code in a comment, note it explicitly:
  ```java
  // Algorithm derived from prototype: <ClassName> — reimplemented for new architecture
  ```

---

## User-Owned Classes

Certain classes are provided directly by the user and are considered **protected**.
They must not be regenerated, restructured, or have their public API changed without
explicit user approval.

### Currently protected classes

| Class | Package | Notes |
|---|---|---|
| `KEPPLRConfiguration` | `kepplr.config` | Singleton; ThreadLocal ephemeris access |
| `KEPPLREphemeris` | `kepplr.ephemeris` | Interface; sole ephemeris authority |
| Utility / math helpers | `kepplr.util` | Added by user over time; treat each as protected when dropped in |

### Rules for user-owned classes

- **Do not modify** a user-owned class without being asked.
- **Do not regenerate** a user-owned class if it already exists in the source tree —
  even if the task seems to require it.
- **If you identify a bug or design issue** in a user-owned class:
  1. Stop work on the current task.
  2. Clearly describe the issue and its potential impact.
  3. Wait for explicit user instruction before proceeding.
- **New classes may depend on user-owned classes** but must follow Rule 3 (no storing
  or passing `KEPPLRConfiguration` / `KEPPLREphemeris` as fields or parameters).
- When a new utility/math helper is dropped in by the user, treat it as protected
  immediately — do not assume it can be freely edited just because it is new.

---

## Behavior and Confirmation Rules

- **New code:** Act freely. Create new classes, tests, and files without asking.
- **Significant changes to existing code:** Confirm before proceeding. "Significant" means:
  - Changing a public API (method signatures, interface contracts)
  - Refactoring across more than one class
  - Deleting or renaming existing files
  - Modifying the threading model or state-flow architecture
- **Trivial fixes in existing code** (typos, obvious bugs, javadoc): Act freely.
- When you confirm, state *what* you plan to change and *why*, then wait for approval.

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

## Architecture Rules

These rules exist to prevent the class-of-bugs that plagued the prototype.
Do not deviate from them without explicit discussion.

### Rule 1 — UI classes must contain no simulation logic

JavaFX UI classes are **view / boundary code only**. They must:
- Bind to `SimulationState` observable properties (read)
- Forward user input to `SimulationCommands` (write)
- Contain no physics, no ongoing simulation state derivation, and no camera math

Limited ephemeris reads are currently tolerated in `KepplrStatusWindow` for one-shot
body / instrument menu population and global overlay toggles. Those reads must remain
bounded UI support code, not become a second simulation model.

If you find yourself writing a calculation inside a JavaFX class, stop and move it.

### Rule 2 — State flows in one direction

```
Simulation Core → SimulationState (ObservableProperties) → JavaFX UI (bindings)
User Input      → SimulationCommands                     → Simulation Core
```

- `SimulationState` is the single source of truth for everything the UI displays.
- `SimulationState` properties are updated on the JME thread; JavaFX bindings must
  marshal to the FX thread at the boundary layer (`SimulationStateFxBridge`), not
  scattered through UI code.
- `Platform.runLater(...)` remains restricted to `SimulationStateFxBridge` and the
  sanctioned lifecycle shutdown call in `KepplrApp.destroy()` (with a comment
  explaining the exception).
- `FxDispatch` is the additional sanctioned FX-thread queue for startup / window
  actions that need to run on the JavaFX application thread without expanding
  `Platform.runLater(...)` usage throughout the codebase.
- `SimulationCommands` is a plain interface; implementations are free to queue,
  validate, or execute commands immediately — that is the core's concern, not the UI's.

### Rule 3 — Ephemeris access follows the singleton/ThreadLocal rule (REDESIGN.md §3)

- Never store or pass `KEPPLRConfiguration` or `KEPPLREphemeris` as fields or parameters.
- Always acquire them at point-of-use:
  ```java
  KEPPLRConfiguration.getInstance().getEphemeris()
  ```
- If you see a constructor or method that accepts either of these as a parameter,
  flag it as a violation before proceeding.

### Rule 4 — JME and JavaFX threads are never mixed

| Thread        | Allowed operations                          |
|---------------|---------------------------------------------|
| JME render    | Scene graph, physics, ephemeris, camera     |
| JavaFX thread | UI node updates, bindings, event handlers   |

Cross-thread communication should normally go through `SimulationState` (JME→FX) or
`SimulationCommands` (FX→JME). The only additional sanctioned boundary helper is
`FxDispatch` for FX-thread lifecycle / window work.

### Rule 5 — Constants over magic numbers

All thresholds from `REDESIGN.md` (frustum ranges, overlap %, pixel cutoffs, sample
densities, etc.) must be defined as named constants in a dedicated `KepplrConstants`
class or an appropriate enum. No magic numbers in logic code.

---

## Package Structure

```
kepplr
├── apps/           # Standalone tools (CLI / utility apps)
├── camera/         # Camera modes, frame definitions, transitions, input
├── commands/       # SimulationCommands interface + implementation
├── config/         # KEPPLRConfiguration singleton + config records
├── core/           # Simulation loop, time, state machine
├── ephemeris/      # KEPPLREphemeris facade, lookup, SPICE support
│   └── spice/
├── render/         # JME scene, bodies, frustums, labels, trails, vectors, stars
│   ├── body/
│   ├── frustum/
│   ├── label/
│   ├── trail/
│   ├── util/
│   └── vector/
├── scripting/      # Groovy API, recorder, wait helpers, capture/reload hooks
├── stars/          # Star catalog abstractions + implementations
│   ├── catalogs/
│   └── services/
├── state/          # SimulationState (observable properties)
├── templates/      # Shared CLI/tool templates
├── ui/             # JavaFX status/control window, dialogs, FX bridge
└── util/           # KepplrConstants, math helpers
```

When creating a new class, place it in the correct package before writing its contents.
If no package clearly fits, ask before creating a new one.

---

## Key Interfaces

Representative signatures from the current codebase. Treat the source files as the
authoritative definition and do not change them casually.

```java
// Sole authority for all ephemeris and frame data (REDESIGN.md §1.1)
public interface KEPPLREphemeris { ... }

// Single source of truth for UI-visible state
public interface SimulationState {
    ReadOnlyIntegerProperty selectedBodyIdProperty();
    ReadOnlyIntegerProperty focusedBodyIdProperty();
    ReadOnlyIntegerProperty targetedBodyIdProperty();
    ReadOnlyDoubleProperty currentEtProperty();
    ReadOnlyDoubleProperty timeRateProperty();
    ReadOnlyBooleanProperty pausedProperty();
    ReadOnlyObjectProperty<CameraFrame> cameraFrameProperty();
    ReadOnlyObjectProperty<CameraFrame> activeCameraFrameProperty();
    // ... extend as needed
}

// All user-initiated actions enter the simulation through this interface
public interface SimulationCommands {
    void selectBody(int naifId);
    void centerBody(int naifId);
    void targetBody(int naifId);
    void pointAt(int naifId, double durationSeconds);
    void goTo(int naifId, double apparentRadiusDeg, double durationSeconds);
    void setCameraFrame(CameraFrame frame);
    void setTimeRate(double simSecondsPerWallSecond);
    void setPaused(boolean paused);
    void loadConfiguration(String path);
    // ... extend as needed
}
```

---

## Testing Rules

- Every class in `core/`, `ephemeris/`, `config/`, `camera/`, `commands/`, and
  `scripting/` must have a corresponding unit test class created at the same time
  as the class itself.
- UI classes (`ui/`) do not require unit tests but should have integration smoke tests.
- Use JUnit 5. Mockito is permitted for mocking `KEPPLREphemeris` in tests.
- Known-good SPICE values (e.g., Earth's heliocentric position at a fixed ET) must be
  used in ephemeris tests, not ad-hoc values.

---

## What "Done" Means for a Task

A task is complete when:
1. The feature works as specified in `REDESIGN.md`
2. Existing tests still pass (`mvn test`)
3. New tests cover the new code
4. No simulation logic has been added to a UI class
5. No new magic numbers exist in logic code

Do not consider a task done if only #1 is true.

---

## Things to Never Do

- Do not add ephemeris calls, camera math, or SPICE logic to `ui/` classes beyond the sanctioned bounded reads described above
- Do not use `Platform.runLater(...)` outside `SimulationStateFxBridge` or `KepplrApp.destroy()` (the latter requires a comment explaining the sanctioned use)
- Do not use `Thread.sleep(...)` in the simulation or render loop
- Do not create a `wait(...)` function in the Groovy scripting API (use `waitSim` / `waitWall`)
- Do not add a generic getter for `KEPPLRConfiguration` or `KEPPLREphemeris` to any domain class
- Do not treat `timeRate = 3x` as "multiply existing rate by 3" — it means `timeRate = 3.0` absolutely

---

## Session Start Checklist

At the start of each session, before writing code:

1. Re-read this file
2. Read `REDESIGN.md`, `KEPPLR_Roadmap.md`, and `DECISIONS.md`
3. Run `mvn test` and note any pre-existing failures
4. State which section of `REDESIGN.md` you are implementing
5. Identify which existing classes you expect to touch
6. Check whether any of those classes are user-owned (see User-Owned Classes above)
7. Flag any conflicts between the task and these instructions before proceeding

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
