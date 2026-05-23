<!-- refreshed: 2026-04-25 -->
# Architecture

**Analysis Date:** 2026-04-25

## System Overview

```text
┌─────────────────────────────────────────────────────────────┐
│                    Launchers and User Input                  │
├──────────────────┬──────────────────┬───────────────────────┤
│ CLI applications │  JavaFX controls │   Groovy scripting    │
│ `src/main/java/kepplr/apps` │ `src/main/java/kepplr/ui` │ `src/main/java/kepplr/scripting` │
└────────┬─────────┴────────┬─────────┴──────────┬────────────┘
         │                  │                     │
         ▼                  ▼                     ▼
┌─────────────────────────────────────────────────────────────┐
│                  Command and State Boundary                  │
│ `src/main/java/kepplr/commands` + `src/main/java/kepplr/state` │
└─────────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────────┐
│               JME Simulation and Rendering Loop              │
│ `src/main/java/kepplr/render/KepplrApp.java`                 │
├──────────────────┬──────────────────┬───────────────────────┤
│ Camera/control   │ Ephemeris/config │ Render managers       │
│ `src/main/java/kepplr/camera` │ `src/main/java/kepplr/ephemeris` + `src/main/java/kepplr/config` │ `src/main/java/kepplr/render` │
└────────┬─────────┴────────┬─────────┴──────────┬────────────┘
         │                  │                     │
         ▼                  ▼                     ▼
┌─────────────────────────────────────────────────────────────┐
│                 SPICE kernels, resources, output             │
│ `src/main/resources/resources/spice`, `src/main/resources/resources/maps`, `src/main/resources/resources/shapes` │
└─────────────────────────────────────────────────────────────┘
```

## Component Responsibilities

| Component | Responsibility | File |
|-----------|----------------|------|
| GUI launcher | Parse CLI options, load configuration, launch the JME app with optional script/state inputs. | `src/main/java/kepplr/apps/KEPPLR.java` |
| JME application shell | Own the JMonkeyEngine lifecycle, scene graph, render managers, JavaFX window wiring, frame update loop, capture hooks, and platform startup details. | `src/main/java/kepplr/render/KepplrApp.java` |
| Command API | Provide the only write-oriented API for user/script actions and translate commands into state, clock, transition, capture, and reload effects. | `src/main/java/kepplr/commands/SimulationCommands.java`, `src/main/java/kepplr/commands/DefaultSimulationCommands.java` |
| Simulation state | Hold JavaFX properties for all UI-visible state; expose read-only properties through `SimulationState` and mutable setters through `DefaultSimulationState`. | `src/main/java/kepplr/state/SimulationState.java`, `src/main/java/kepplr/state/DefaultSimulationState.java` |
| JavaFX bridge | Marshal state observations from the JME thread to JavaFX properties used by the status window. | `src/main/java/kepplr/ui/SimulationStateFxBridge.java`, `src/main/java/kepplr/ui/KepplrStatusWindow.java` |
| Configuration singleton | Load Jackfruit/Commons Configuration properties, body/spacecraft blocks, Log4j settings, resource folders, and thread-local ephemeris services. | `src/main/java/kepplr/config/KEPPLRConfiguration.java` |
| Ephemeris service | Wrap Picante/SPICE data for positions, rotations, shapes, instruments, spacecraft, and time conversion. | `src/main/java/kepplr/ephemeris/KEPPLREphemeris.java`, `src/main/java/kepplr/ephemeris/spice/SpiceBundle.java` |
| Body rendering | Maintain body/spacecraft scene nodes, culling decisions, frustum assignment, ring/shadow updates, and visible-body summaries. | `src/main/java/kepplr/render/body/BodySceneManager.java` |
| Overlay rendering | Manage stars, labels, trails, vectors, instrument frustums, HUD, and sun halo as state-driven render subsystems. | `src/main/java/kepplr/render/StarFieldManager.java`, `src/main/java/kepplr/render/label/LabelManager.java`, `src/main/java/kepplr/render/trail/TrailManager.java`, `src/main/java/kepplr/render/vector/VectorManager.java`, `src/main/java/kepplr/render/InstrumentFrustumManager.java`, `src/main/java/kepplr/render/KepplrHud.java`, `src/main/java/kepplr/render/SunHaloRenderer.java` |
| Camera system | Interpret input, transitions, body-fixed and synodic frame behavior, and camera navigation operations. | `src/main/java/kepplr/camera/CameraInputHandler.java`, `src/main/java/kepplr/camera/TransitionController.java`, `src/main/java/kepplr/camera/BodyFixedFrame.java`, `src/main/java/kepplr/camera/SynodicFrameApplier.java` |
| Groovy scripting | Run scripts on a daemon script thread and expose a typed script facade backed by `SimulationCommands`. | `src/main/java/kepplr/scripting/ScriptRunner.java`, `src/main/java/kepplr/scripting/KepplrScript.java`, `src/main/java/kepplr/scripting/CommandRecorder.java` |
| Star catalogs | Define star catalog abstractions, tiled catalogs, Yale BSC loading, Gaia tooling, and star rendering inputs. | `src/main/java/kepplr/stars`, `src/main/java/kepplr/stars/catalogs` |
| Utility apps | Provide command-line tools for dumping config, printing ephemeris, GLB viewing, and PNG-to-movie conversion. | `src/main/java/kepplr/apps/DumpConfig.java`, `src/main/java/kepplr/apps/PrintEphemeris.java`, `src/main/java/kepplr/apps/GlbModelViewer.java`, `src/main/java/kepplr/apps/PngToMovie.java` |

## Pattern Overview

**Overall:** Desktop simulation app with a command/state boundary and a single authoritative JME render loop.

**Key Characteristics:**
- Use `kepplr.commands.SimulationCommands` as the action boundary. UI, input handlers, and scripts should call commands rather than mutating render objects directly.
- Use `kepplr.state.SimulationState` for read-only observation and `kepplr.state.DefaultSimulationState` only inside command/core/render orchestration code that legitimately writes state.
- Keep render-time work on the JME thread. `KepplrApp.simpleUpdate()` advances time, applies camera work, syncs state to render managers, updates overlays, and writes visible state each frame.
- Acquire `KEPPLRConfiguration.getInstance().getEphemeris()` at point-of-use instead of storing ephemeris references across components or threads.
- Bridge JavaFX through `SimulationStateFxBridge`; JavaFX views bind to bridge properties instead of touching `SimulationState` or scattering `Platform.runLater`.

## Layers

**Launch and Tools:**
- Purpose: Start the GUI or run standalone utility workflows.
- Location: `src/main/java/kepplr/apps`
- Contains: `KEPPLR`, `PrintEphemeris`, `DumpConfig`, `GlbModelViewer`, `PngToMovie`.
- Depends on: `src/main/java/kepplr/templates`, `src/main/java/kepplr/config`, `src/main/java/kepplr/render`, Apache Commons CLI.
- Used by: Users and Maven exec/package workflows.

**Application Orchestration:**
- Purpose: Own native windows, engine lifecycle, initial wiring, and per-frame coordination.
- Location: `src/main/java/kepplr/render/KepplrApp.java`
- Contains: `SimpleApplication` subclass, multi-frustum viewport setup, manager construction, state/command/bridge wiring, capture/reload fences.
- Depends on: `src/main/java/kepplr/state`, `src/main/java/kepplr/commands`, `src/main/java/kepplr/camera`, `src/main/java/kepplr/render/*`, `src/main/java/kepplr/config`, JavaFX, JME, LWJGL.
- Used by: `src/main/java/kepplr/apps/KEPPLR.java` and `KepplrApp.main()` for default-template launch.

**Command Boundary:**
- Purpose: Normalize user, input, and script actions into state changes, clock operations, transition requests, and asynchronous callbacks.
- Location: `src/main/java/kepplr/commands`
- Contains: `SimulationCommands` interface and `DefaultSimulationCommands` implementation.
- Depends on: `src/main/java/kepplr/state`, `src/main/java/kepplr/core`, `src/main/java/kepplr/camera`, `src/main/java/kepplr/config`.
- Used by: `src/main/java/kepplr/ui/KepplrStatusWindow.java`, `src/main/java/kepplr/camera/CameraInputHandler.java`, `src/main/java/kepplr/scripting/KepplrScript.java`, `src/main/java/kepplr/scripting/CommandRecorder.java`.

**State Layer:**
- Purpose: Provide the shared observable state model for interaction, time, camera, overlays, render quality, visible bodies, snapshots, and messages.
- Location: `src/main/java/kepplr/state`
- Contains: JavaFX property-backed state interfaces/classes and state snapshot codec/value objects.
- Depends on: JavaFX properties, camera enums, render quality, vector types.
- Used by: commands, render managers, JavaFX bridge, scripting, tests.

**Ephemeris and Configuration:**
- Purpose: Load configuration, SPICE kernels, body metadata, spacecraft metadata, time conversion, positions, rotations, shapes, and instruments.
- Location: `src/main/java/kepplr/config`, `src/main/java/kepplr/ephemeris`
- Contains: Jackfruit-generated config block interfaces/factories, singleton `KEPPLRConfiguration`, Picante-backed `KEPPLREphemeris`, `SpiceBundle`.
- Depends on: Picante, Jackfruit, Commons Configuration, Log4j.
- Used by: almost every simulation/rendering component at point-of-use.

**Rendering Managers:**
- Purpose: Convert ephemeris/state into JME scene graph updates.
- Location: `src/main/java/kepplr/render`, `src/main/java/kepplr/render/body`, `src/main/java/kepplr/render/frustum`, `src/main/java/kepplr/render/label`, `src/main/java/kepplr/render/trail`, `src/main/java/kepplr/render/vector`
- Contains: body nodes, culling, frustum assignment, shadow/ring support, stars, HUD, labels, trails, vectors, instrument frustums, GLTF helpers.
- Depends on: JME, Picante vectors/frames, `SimulationState`, `KEPPLRConfiguration`.
- Used by: `src/main/java/kepplr/render/KepplrApp.java` from the JME render thread.

**Camera Layer:**
- Purpose: Manage direct input, scripted transitions, camera pointing/navigation, and non-inertial camera frames.
- Location: `src/main/java/kepplr/camera`
- Contains: transition controller, input handler, frame enum, body-fixed/synodic frame appliers, navigation and pointing helpers.
- Depends on: JME camera/math, Picante ephemeris data via configuration, `SimulationState`.
- Used by: `KepplrApp`, `DefaultSimulationCommands`, and tests.

**UI Layer:**
- Purpose: Present status/control windows and dialogs through JavaFX.
- Location: `src/main/java/kepplr/ui`
- Contains: status window, log window/appender, time/FOV/rate dialogs, FX dispatch timer, state bridge.
- Depends on: JavaFX, commands, bridge properties, configuration for body/instrument menus.
- Used by: `KepplrApp` and Log4j.

**Scripting Layer:**
- Purpose: Execute Groovy automation with blocking and queued semantics layered over commands.
- Location: `src/main/java/kepplr/scripting`
- Contains: script runner, script facade, command recorder, transition waits, output listener, line forwarding writer.
- Depends on: Groovy JSR-223, `SimulationCommands`, `SimulationState`.
- Used by: startup scripts and JavaFX scripting controls.

**Resources and Assets:**
- Purpose: Provide shaders, textures, shapes, SPICE kernels, and star catalogs.
- Location: `src/main/resources`
- Contains: JME shader definitions under `src/main/resources/kepplr/shaders`, maps and shapes under `src/main/resources/resources`, star catalog under `src/main/resources/kepplr/stars/catalogs/yaleBSC`.
- Depends on: Maven resource packaging and runtime resource/file locators.
- Used by: config, ephemeris, render managers, asset manager.

## Data Flow

### Primary GUI Launch Path

1. CLI parses `-config`, optional `-script`, and optional `-state` in `src/main/java/kepplr/apps/KEPPLR.java:50`.
2. The launcher loads the active configuration via `KEPPLRConfiguration.load(...)` in `src/main/java/kepplr/apps/KEPPLR.java:60`.
3. The launcher calls `KepplrApp.run(...)` in `src/main/java/kepplr/apps/KEPPLR.java:62`.
4. `KepplrApp.run(...)` performs platform-specific JavaFX/GLFW startup, creates `KepplrApp`, stores startup script/state, and starts JME in `src/main/java/kepplr/render/KepplrApp.java:1103`.
5. `KepplrApp.simpleInitApp()` registers assets, creates `DefaultSimulationState`, `SimulationClock`, `TransitionController`, `DefaultSimulationCommands`, JavaFX bridge/window, render managers, and camera input in `src/main/java/kepplr/render/KepplrApp.java:207`.
6. The first frame and every subsequent frame flow through `KepplrApp.simpleUpdate()` in `src/main/java/kepplr/render/KepplrApp.java:417`.

### Per-Frame Simulation and Render Path

1. `simulationClock.advance()` updates ET/time state on the JME thread in `src/main/java/kepplr/render/KepplrApp.java:433`.
2. Pending state restores and input are consumed before camera transitions in `src/main/java/kepplr/render/KepplrApp.java:438` and `src/main/java/kepplr/render/KepplrApp.java:457`.
3. `TransitionController.update(...)` advances scripted/user camera motion in `src/main/java/kepplr/render/KepplrApp.java:469`.
4. Body-fixed or synodic frame corrections are applied, then camera position, FOV, orientation, and body-fixed coordinates are written back to state in `src/main/java/kepplr/render/KepplrApp.java:471`.
5. Multi-frustum slave cameras and sun lights sync to the master camera in `src/main/java/kepplr/render/KepplrApp.java:525`.
6. `BodySceneManager.update(...)` acquires ephemeris, computes positions/culling/frustum assignment, updates body nodes, and returns visible bodies in `src/main/java/kepplr/render/KepplrApp.java:546` and `src/main/java/kepplr/render/body/BodySceneManager.java:142`.
7. Overlay state is synced to trail/vector/label/frustum managers in `src/main/java/kepplr/render/KepplrApp.java:548`.
8. Trails, vectors, frustums, stars, sun halo, labels, and HUD update in `src/main/java/kepplr/render/KepplrApp.java:562`.
9. Visible bodies are written back to `DefaultSimulationState` for UI consumption in `src/main/java/kepplr/render/KepplrApp.java:574`.
10. The three custom frustum scene roots update their geometric state before render in `src/main/java/kepplr/render/KepplrApp.java:578`.

### Command and UI Flow

1. JavaFX controls call `SimulationCommands` through `CommandRecorder`/`DefaultSimulationCommands`; camera input also uses the same command interface through `CameraInputHandler`.
2. Commands write `DefaultSimulationState`, delegate time changes to `SimulationClock`, and queue transition requests to `TransitionController`; the command class stores the writable state in `src/main/java/kepplr/commands/DefaultSimulationCommands.java:49`.
3. `SimulationStateFxBridge` observes `SimulationState` properties on the JME thread and dispatches formatted JavaFX properties through one bridge in `src/main/java/kepplr/ui/SimulationStateFxBridge.java:30`.
4. `KepplrStatusWindow` binds to bridge properties and submits user actions back through `SimulationCommands`.

### Configuration Reload Flow

1. UI or script calls `SimulationCommands.loadConfiguration(path)`.
2. `DefaultSimulationCommands.loadConfiguration(...)` validates the file and calls `KEPPLRConfiguration.reload(...)` in `src/main/java/kepplr/commands/DefaultSimulationCommands.java:697`.
3. If a scene rebuild callback is installed, commands create a latch and call the callback in `src/main/java/kepplr/commands/DefaultSimulationCommands.java:714`.
4. `KepplrApp.simpleInitApp()` wires that callback to enqueue `rebuildBodyScene()` on the JME thread in `src/main/java/kepplr/render/KepplrApp.java:245`.
5. `KepplrApp.simpleUpdate()` counts down the post-rebuild latch after the first full update with the new configuration in `src/main/java/kepplr/render/KepplrApp.java:585`.
6. Commands run the post-reload UI callback in `src/main/java/kepplr/commands/DefaultSimulationCommands.java:728`.

### Script Execution Flow

1. `ScriptRunner.runScript(Path)` stops any existing script and starts a daemon `kepplr-groovy-script` thread in `src/main/java/kepplr/scripting/ScriptRunner.java:80`.
2. The runner creates `KepplrScript` with `SimulationCommands` and `SimulationState` in `src/main/java/kepplr/scripting/ScriptRunner.java:156`.
3. Script methods delegate to commands, with public semantics documented as Immediate, Queued, Blocking, or Immediate + Queued in `src/main/java/kepplr/scripting/KepplrScript.java:35`.
4. Blocking script operations rely on latches, render fences, transition waits, and state observations rather than direct render-thread access.

**State Management:**
- `DefaultSimulationState` is the single mutable state object for UI-visible simulation state. It uses JavaFX properties and exposes read-only views through `SimulationState`.
- State mutations are expected on the JME thread; cross-thread script/UI operations should enter through `SimulationCommands` or explicit callbacks/latches.
- `KEPPLRConfiguration` is a global singleton with `ThreadLocal<KEPPLREphemeris>`; each thread gets its own ephemeris instance through `getEphemeris()`.
- Render manager local caches are allowed for scene graph objects and frame-to-frame rendering data, such as `BodySceneManager.bodyNodes`, `lastAssignedLayers`, and `sceneBodyRadiiKm`.

## Key Abstractions

**SimulationCommands:**
- Purpose: Stable write API for all user-visible operations.
- Examples: `src/main/java/kepplr/commands/SimulationCommands.java`, `src/main/java/kepplr/commands/DefaultSimulationCommands.java`, `src/main/java/kepplr/scripting/CommandRecorder.java`
- Pattern: Interface plus concrete state-transition implementation plus recorder/decorator.

**SimulationState:**
- Purpose: Read-only observable model for interaction, time, camera, overlays, render quality, bodies-in-view, snapshots, and messages.
- Examples: `src/main/java/kepplr/state/SimulationState.java`, `src/main/java/kepplr/state/DefaultSimulationState.java`, `src/main/java/kepplr/state/StateSnapshot.java`, `src/main/java/kepplr/state/StateSnapshotCodec.java`
- Pattern: JavaFX property-backed mutable implementation hidden behind a read-only interface for consumers.

**KEPPLRConfiguration and KEPPLREphemeris:**
- Purpose: Centralize configuration and SPICE/Picante access.
- Examples: `src/main/java/kepplr/config/KEPPLRConfiguration.java`, `src/main/java/kepplr/ephemeris/KEPPLREphemeris.java`, `src/main/java/kepplr/ephemeris/spice/SpiceBundle.java`
- Pattern: Singleton config with thread-local ephemeris; acquire at point-of-use.

**Render Managers:**
- Purpose: Encapsulate one render concern per manager and update JME scene objects in-place each frame.
- Examples: `src/main/java/kepplr/render/body/BodySceneManager.java`, `src/main/java/kepplr/render/trail/TrailManager.java`, `src/main/java/kepplr/render/vector/VectorManager.java`, `src/main/java/kepplr/render/label/LabelManager.java`, `src/main/java/kepplr/render/InstrumentFrustumManager.java`
- Pattern: Long-lived manager constructed by `KepplrApp`, `update(...)` called from `simpleUpdate()`, `dispose()` when applicable.

**Frustum Layers:**
- Purpose: Render solar-system scale content across near, mid, and far viewports with stable depth precision.
- Examples: `src/main/java/kepplr/render/frustum/FrustumLayer.java`, `src/main/java/kepplr/render/KepplrApp.java`, `src/main/java/kepplr/render/body/BodySceneManager.java`
- Pattern: Three JME cameras/viewports sharing orientation/FOV, with layer-specific near/far planes and scene roots.

**Camera Transitions and Frames:**
- Purpose: Separate command requests from frame-by-frame camera interpolation and coordinate-frame behavior.
- Examples: `src/main/java/kepplr/camera/TransitionController.java`, `src/main/java/kepplr/camera/CameraTransition.java`, `src/main/java/kepplr/camera/BodyFixedFrame.java`, `src/main/java/kepplr/camera/SynodicFrameApplier.java`
- Pattern: Commands enqueue/request transitions; `KepplrApp.simpleUpdate()` advances them and writes state.

**Config Blocks:**
- Purpose: Represent top-level, body, SPICE, and spacecraft configuration entries.
- Examples: `src/main/java/kepplr/config/KEPPLRConfigBlock.java`, `src/main/java/kepplr/config/BodyBlock.java`, `src/main/java/kepplr/config/SPICEBlock.java`, `src/main/java/kepplr/config/SpacecraftBlock.java`
- Pattern: Jackfruit annotation-driven interfaces with generated `*Factory` classes referenced from `KEPPLRConfiguration`.

**Star Catalogs:**
- Purpose: Abstract star data sources and support tiled catalog lookup/rendering.
- Examples: `src/main/java/kepplr/stars/StarCatalog.java`, `src/main/java/kepplr/stars/TiledStarCatalog.java`, `src/main/java/kepplr/stars/catalogs/yaleBSC/YaleBrightStarCatalog.java`, `src/main/java/kepplr/stars/catalogs/gaia/GaiaCatalog.java`
- Pattern: Generic catalog interfaces plus concrete catalog loaders and build tools.

## Entry Points

**Main GUI:**
- Location: `src/main/java/kepplr/apps/KEPPLR.java`
- Triggers: Command-line invocation of the packaged app.
- Responsibilities: Parse CLI options, load configuration, invoke `KepplrApp.run(...)`.

**JME App Default Launch:**
- Location: `src/main/java/kepplr/render/KepplrApp.java`
- Triggers: Direct class launch for a template/default configuration.
- Responsibilities: Create a default configuration and run the GUI.

**Utility Applications:**
- Location: `src/main/java/kepplr/apps/PrintEphemeris.java`
- Triggers: CLI tool invocation.
- Responsibilities: Print ephemeris values from configured kernels.

**Utility Applications:**
- Location: `src/main/java/kepplr/apps/DumpConfig.java`
- Triggers: CLI tool invocation.
- Responsibilities: Dump or inspect generated/default configuration.

**Utility Applications:**
- Location: `src/main/java/kepplr/apps/GlbModelViewer.java`
- Triggers: CLI tool invocation.
- Responsibilities: Run a standalone JME GLB model viewer.

**Utility Applications:**
- Location: `src/main/java/kepplr/apps/PngToMovie.java`
- Triggers: CLI tool invocation.
- Responsibilities: Convert PNG sequences to movies using external process support.

**Gaia Catalog Tools:**
- Location: `src/main/java/kepplr/stars/catalogs/gaia/tools`
- Triggers: CLI tool invocation.
- Responsibilities: Build source indexes, convert CSV star data to tile packs, and merge tile packs.

**Python Shape Tool:**
- Location: `src/main/python/apps/convert_to_normalized_glb.py`
- Triggers: Python command-line invocation.
- Responsibilities: Convert/normalize GLB shape assets outside the Java runtime.

## Architectural Constraints

- **Threading:** JME owns simulation and render mutation. JavaFX receives state through `SimulationStateFxBridge`. Groovy scripts run on a separate daemon thread and must use command APIs, latches, waits, or render fences for synchronization.
- **Global state:** `KEPPLRConfiguration.instance` is a global singleton in `src/main/java/kepplr/config/KEPPLRConfiguration.java`; `VectorTypes.setSimulationState(...)` installs state for built-in vector implementations in `src/main/java/kepplr/render/vector/VectorTypes.java`; Log4j configuration is global through `src/main/java/kepplr/util/Log4j2Configurator.java`.
- **Ephemeris lifetime:** Do not store or pass `KEPPLREphemeris` references between components/threads. Use `KEPPLRConfiguration.getInstance().getEphemeris()` at point-of-use as documented in `src/main/java/kepplr/config/KEPPLRConfiguration.java:49`.
- **Coordinate units:** Distances are kilometers, velocities are km/s, time is ET/TDB seconds past J2000, and render scene positions are camera-relative floating-origin vectors.
- **Scene graph mutation:** Add/update/detach JME spatial objects from the JME render thread. Scene rebuilds are enqueued through `KepplrApp.enqueue(...)`.
- **Generated code:** Jackfruit and Immutables-generated sources are expected under Maven build output, not committed under `src/main/java`.
- **Circular imports:** No explicit circular package dependency was detected from the sampled architecture, but `render/KepplrApp.java` is intentionally central and depends on most runtime layers.

## Anti-Patterns

### Direct JavaFX Dispatch From Feature Code

**What happens:** Calling `Platform.runLater(...)` directly from render, command, or manager code bypasses the central bridge.
**Why it's wrong:** It scatters thread marshaling and can flood the JavaFX run queue or make tests need a live FX toolkit.
**Do this instead:** Add bridge properties or dispatcher behavior in `src/main/java/kepplr/ui/SimulationStateFxBridge.java`, then bind UI controls in `src/main/java/kepplr/ui/KepplrStatusWindow.java`.

### Holding Ephemeris References

**What happens:** A component stores `KEPPLREphemeris` in a field and reuses it across frames or threads.
**Why it's wrong:** The project contract makes ephemeris thread-local and reloadable through the configuration singleton.
**Do this instead:** Acquire `KEPPLRConfiguration.getInstance().getEphemeris()` inside the method that needs it, following `src/main/java/kepplr/render/body/BodySceneManager.java:142` and `src/main/java/kepplr/config/KEPPLRConfiguration.java:49`.

### Bypassing SimulationCommands

**What happens:** UI, scripts, or input handlers mutate `DefaultSimulationState`, `TransitionController`, or render managers directly.
**Why it's wrong:** It bypasses state-transition rules, recorder support, synchronization callbacks, and script semantics.
**Do this instead:** Add or use a method on `src/main/java/kepplr/commands/SimulationCommands.java` and implement it in `src/main/java/kepplr/commands/DefaultSimulationCommands.java`.

### Recreating Geometry Every Frame

**What happens:** Render code constructs new scene nodes/materials for stable bodies each frame.
**Why it's wrong:** It increases GC pressure and can destabilize frustum assignment and attachment state.
**Do this instead:** Follow `BodySceneManager`: create body nodes with `computeIfAbsent(...)`, then update position/rotation/attachment in-place in `src/main/java/kepplr/render/body/BodySceneManager.java:215`.

## Error Handling

**Strategy:** Runtime operations favor logging and graceful continuation where possible; hard startup failures stop the app when required data is missing.

**Patterns:**
- Log configuration load failures and return without changing scene state in `src/main/java/kepplr/commands/DefaultSimulationCommands.java:697`.
- Log missing ephemeris positions and stop startup if the default focus body cannot be resolved in `src/main/java/kepplr/render/KepplrApp.java:301`.
- Convert checked configuration load errors into runtime failures in `src/main/java/kepplr/config/KEPPLRConfiguration.java:276`.
- Catch render update exceptions around input and sun halo updates, log warnings, and skip the rest of the frame in `src/main/java/kepplr/render/KepplrApp.java:457` and `src/main/java/kepplr/render/KepplrApp.java:567`.
- Use latches with timeouts for configuration reload, state restore, screenshots, and render fences to avoid indefinite script/UI blocking.

## Cross-Cutting Concerns

**Logging:** Log4j is used throughout runtime classes. Configuration reload applies log level/pattern through `src/main/java/kepplr/util/Log4j2Configurator.java` from `src/main/java/kepplr/config/KEPPLRConfiguration.java`.
**Validation:** CLI options use Apache Commons CLI via `src/main/java/kepplr/templates/KEPPLRTool.java`; configuration parsing uses Commons Configuration plus Jackfruit factories in `src/main/java/kepplr/config`; commands validate missing paths and numeric ranges locally.
**Authentication:** Not applicable; this is a local desktop simulation/tooling codebase with no detected auth layer.
**Resource Loading:** Use Maven resources for packaged assets and `KEPPLRConfiguration.resourcesFolder()` as a JME `FileLocator` in `src/main/java/kepplr/render/KepplrApp.java:229`.
**Testing Boundary:** Tests mirror package layout under `src/test/java/kepplr` and use `src/test/java/kepplr/testsupport/TestHarness.java` plus test SPICE resources under `src/test/resources/spice`.

---

*Architecture analysis: 2026-04-25*
