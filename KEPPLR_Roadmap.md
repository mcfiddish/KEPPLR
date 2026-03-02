# KEPPLR Build Roadmap

A high-level reference for the recommended build order of the KEPPLR 3D solar system simulator.

---

## Recommended Build Order

### 1. Project Scaffold
Maven project with dependency declarations for JMonkeyEngine, Picante, JavaFX, and Groovy. Goal: a blank JME window running alongside a blank JavaFX window. This step is deceptively tricky due to threading model differences between JME and JavaFX and is worth solving in isolation before anything else is layered on.

### 2. Ephemeris Layer
Implement `KEPPLREphemeris` and `KEPPLRConfiguration` with the singleton/ThreadLocal access pattern and full threading rules. Write unit tests against known SPICE values (e.g., Earth's heliocentric position at a fixed ET). This layer is load-bearing for everything else — don't proceed until the tests are green.

### 3. First Render
A single textured sphere (Earth) at its correct heliocentric position. No camera controls yet — the sole goal is proving the JME ↔ ephemeris pipeline works end to end.

### 4. Camera & Interaction Modes
Implement the focused/targeted/tracked/selected body semantics. This is also the natural point to wire up `SimulationState` (observable properties) and `SimulationCommands` (the input interface), since camera behavior depends on both.

### 5. Multi-Frustum Rendering
Add the multi-frustum depth management system once basic rendering is solid. This requires stable camera and body-position infrastructure from step 4.

---

## Capabilities Not Yet Addressed at Step 5

The following systems are required by the architecture but are not yet built by the end of step 5. They each represent meaningful standalone work.

**SimulationState / SimulationCommands full wiring**
The observable property bridge between the JME thread and JavaFX thread. Step 4 establishes the interfaces, but the full wiring — including `Platform.runLater(...)` marshalling at the designated boundary layer — needs explicit attention as a complete deliverable.

**JavaFX Control Window**
The actual UI controls: time rate, pause/resume, body selection, label display. The threading problem is solved in step 1, but the control window itself is never built.

**Time Control**
Pause, time rate setting, and the critical semantic that `timeRate = 3x` means an absolute value of 3.0 — not a multiplier applied to the current rate. This distinction has caused bugs before and warrants its own focused step.

**Orbital Trails**
A distinct rendering subsystem (`render/trail/`) separate from body rendering. Trails have their own update cadence, memory management, and LOD considerations.

**Star Field**
A background renderer that's easy to forget since it's not interactive, but is a distinct system in `render/`.

**Groovy Scripting Layer**
The `waitSim` / `waitWall` API and the broader Groovy scripting surface. This is its own integration effort, touching the simulation loop's time model and threading.

---

## Suggested Steps 6–9

| Step | Capability |
|------|------------|
| 6 | Time control + full SimulationState/Commands wiring |
| 7 | JavaFX control window (UI layer) |
| 8 | Orbital trails + star field |
| 9 | Groovy scripting layer |
