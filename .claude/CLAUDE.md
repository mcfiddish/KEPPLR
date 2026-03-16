# KEPPLR — Claude Code Agent Instructions

This file defines standing instructions for all Claude Code sessions on the KEPPLR project.
Read it fully at the start of every session before making any changes.

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

## Architecture Rules

These rules exist to prevent the class-of-bugs that plagued the prototype.
Do not deviate from them without explicit discussion.

### Rule 1 — UI classes must contain no simulation logic

JavaFX controllers and FXML-backed classes are **views only**. They must:
- Bind to `SimulationState` observable properties (read)
- Forward user input to `SimulationCommands` (write)
- Contain no physics, no ephemeris calls, no camera math

If you find yourself writing a calculation inside a JavaFX controller, stop and move it.

### Rule 2 — State flows in one direction

```
Simulation Core → SimulationState (ObservableProperties) → JavaFX UI (bindings)
User Input      → SimulationCommands                     → Simulation Core
```

- `SimulationState` is the single source of truth for everything the UI displays.
- `SimulationState` properties are updated on the JME thread; JavaFX bindings must
  marshal to the FX thread using `Platform.runLater(...)` at the boundary layer
  (`SimulationStateFxBridge`), not scattered through UI code. The one additional
  sanctioned use is `KepplrApp.destroy()` for lifecycle shutdown — that call site
  must have a comment explaining the exception. No other class may call
  `Platform.runLater(...)`.
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

Cross-thread communication must go through `SimulationState` (JME→FX) or
`SimulationCommands` (FX→JME). No other crossing is permitted.

### Rule 5 — Constants over magic numbers

All thresholds from `REDESIGN.md` (frustum ranges, overlap %, pixel cutoffs, sample
densities, etc.) must be defined as named constants in a dedicated `KepplrConstants`
class or an appropriate enum. No magic numbers in logic code.

---

## Package Structure

```
kepplr
├── core/           # Simulation loop, time, state machine
├── ephemeris/      # KEPPLREphemeris interface + Picante implementation
├── config/         # KEPPLRConfiguration singleton
├── render/         # JME scene, frustums, body renderers, star field
│   ├── frustum/
│   ├── body/
│   └── trail/
├── camera/         # Camera modes, frame definitions, synodic frame
├── state/          # SimulationState (observable properties)
├── commands/       # SimulationCommands interface + implementations
├── ui/             # JavaFX controllers, FXML — NO logic here
├── scripting/      # Groovy API, waitSim/waitWall
└── util/           # KepplrConstants, math helpers
```

When creating a new class, place it in the correct package before writing its contents.
If no package clearly fits, ask before creating a new one.

---

## Key Interfaces (establish these early; do not change signatures without discussion)

```java
// Sole authority for all ephemeris and frame data (REDESIGN.md §1.1)
public interface KEPPLREphemeris { ... }

// Single source of truth for UI-visible state
public interface SimulationState {
    ReadOnlyObjectProperty<Body> selectedBodyProperty();
    ReadOnlyObjectProperty<Body> focusedBodyProperty();
    ReadOnlyObjectProperty<Body> targetedBodyProperty();
    ReadOnlyDoubleProperty currentEtProperty();
    ReadOnlyDoubleProperty timeRateProperty();
    ReadOnlyBooleanProperty pausedProperty();
    // ... extend as needed
}

// All user-initiated actions enter the simulation through this interface
public interface SimulationCommands {
    void selectBody(int naifId);
    void focusBody(int naifId);
    void targetBody(int naifId);
    void setCameraFrame(CameraFrame frame);
    void setTimeRate(double simSecondsPerWallSecond);
    void setPaused(boolean paused);
    void cancelTransition();   // clean up on script interruption — see D-025
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

- Do not add ephemeris calls, camera math, or SPICE logic to `ui/` classes
- Do not use `Platform.runLater(...)` outside `SimulationStateFxBridge` or `KepplrApp.destroy()` (the latter requires a comment explaining the sanctioned use)
- Do not use `Thread.sleep(...)` in the simulation or render loop
- Do not create a `wait(...)` function in the Groovy scripting API (use `waitSim` / `waitWall`)
- Do not add a generic getter for `KEPPLRConfiguration` or `KEPPLREphemeris` to any domain class
- Do not treat `timeRate = 3x` as "multiply existing rate by 3" — it means `timeRate = 3.0` absolutely

---

## Session Start Checklist

At the start of each session, before writing code:

1. Re-read this file
2. Run `mvn test` and note any pre-existing failures
3. State which section of `REDESIGN.md` you are implementing
4. Read `DECISIONS.md` and note any entries relevant to the current task
5. Identify which existing classes you expect to touch
6. Check whether any of those classes are user-owned (see User-Owned Classes above)
7. Flag any conflicts between the task and these instructions before proceeding
