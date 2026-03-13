# KEPPLR Build Roadmap

A high-level reference for the recommended build order of the KEPPLR 3D solar system simulator.

---

## Starting a New Chat Session

**Always begin every new Claude Code session with:**

> Please read CLAUDE.md, REDESIGN.md, and this roadmap in full before doing anything else. Then run `mvn test` and report any pre-existing failures. Then state which step of the roadmap we are on and which classes you expect to touch.

This ensures CC has the full architectural context, knows what has already been decided, and does not re-litigate settled choices.

---

## Key Decisions

These are non-obvious choices made during development. If CC questions them in a new session, refer it back to this section rather than re-opening the discussion.

**World-space units are kilometers.** Ephemeris data comes from SPICE in km and scene coordinates are in km. Precision at astronomical scales is handled by the floating origin, not by choosing different units.

**Floating origin.** Scene graph coordinates are always camera-relative, recentered each frame. This keeps JME's single-precision floats well within range regardless of true heliocentric distances. This must never be removed or bypassed.

**Anchor-based time model.** ET is computed as `currentET = anchorET + timeRate × (wallNow - anchorWall)` from an immutable anchor record replaced atomically via `AtomicReference`. Rate changes, pause/resume, and `setET()` all rebase the anchor at the current moment — no time jumps occur. `wallNow` is clamped at the moment of pause and resumes from the current wall time on unpause.

**`SimulationStateFxBridge` is the sole location of `Platform.runLater()` calls** with one additional sanctioned exception: GLFW window management callbacks (minimize, focus) may call `Platform.runLater()` through a dedicated `WindowManager` class in `com.kepplr.core`. No other class may call `Platform.runLater()`.

**`SimulationCommands` is also the Groovy scripting API.** The scripting layer (step 20) is a thin Groovy-friendly wrapper that delegates to `SimulationCommands`. Every method on `SimulationCommands` must be loggable so real-time recordings can be transcribed as executable Groovy scripts with `waitWall()` timing calls inserted between commands.

**The synodic frame "other body" is the currently targeted body.** No separate command or property is needed for this. If no targeted body exists, fall back to the inertial frame and log a warning.

**Spacecraft are always rendered as point sprites for now.** Shape model rendering for spacecraft is explicitly deferred. Do not implement it before the deferred shape model step.

**Ellipsoids only for body geometry.** `KEPPLREphemeris.getShape()` always returns an ellipsoid. Shape model rendering for irregular bodies is deferred. Do not use shape models before the deferred step.

**Orbit drag is camera-relative.** Right drag orbits around the camera's current screen-right and screen-up vectors, not fixed world axes. This must not be changed to world-axis orbit without explicit discussion.

**Zoom is exponential.** Each scroll or keyboard zoom step changes camera distance by a fixed percentage. Minimum distance is 1.1× the focus body radius; maximum is 1e15 km matching the far frustum boundary.

**Camera transitions are non-blocking.** `pointAt()` and `goTo()` initiate a transition and return immediately. Transition progress is tracked in `SimulationState`. The Groovy scripting layer uses `waitTransition()` to block a script until a transition completes; the simulation loop does not block.

**`goTo()` does not use light-time correction.** The camera moves along its current line of sight to the target body. Light-time correction applies to camera pointing in tracked mode (established in step 4) but not to the goTo translation path.

**Body selection does not move the camera.** Selecting a body updates the HUD and status display only. Targeting a body slews the camera to point at it (`pointAt`) but does not change camera position. Focusing a body points at it then moves to it (`pointAt` then `goTo`).

**The Sun halo always renders.** The Sun's body geometry is subject to the 2 px culling rule, but the halo renders regardless of cull state. The halo is suppressed only when the Sun is outside the view frustum entirely.

**The JavaFX control panel is a transparent overlay on the JME window (Option B).** The JME/LWJGL window renders at full performance. A transparent JavaFX stage with `StageStyle.TRANSPARENT` and `setAlwaysOnTop(true)` is positioned over it and kept in sync via GLFW position callbacks. On Linux, GLFW must be forced to X11 mode and the launcher script must configure the environment — native Wayland is not supported. On macOS, JME runs on the OS main thread and JavaFX is launched from a background thread. The JME→JavaFX shutdown hook is wired through `destroy()`. See the Spike Findings section for exact implementation details.

---

## Spike Findings — JavaFX Overlay on JME Window

This section records the implementation findings from a dedicated spike (separate throwaway project, not in the KEPPLR source tree). Step 19 must carry these forward exactly — do not rediscover or reinvent these solutions.

**Spike project location:** `../KEPPLR-overlay-spike/` — read the source before implementing step 19.

**Platforms confirmed working:** macOS 15.7.4 Sequoia (Apple M4), Ubuntu 25.10 Linux 6.17 x86_64 under XWayland. Native Wayland is not supported — see Linux workaround below. Retina display coordinate alignment not yet confirmed on a native MacBook display; both GLFW and JavaFX 25 use logical points on macOS so no correction is expected, but this must be verified before shipping.

### Workaround 1 — Linux: Force GLFW to X11 mode (required)

LWJGL 3.3.6's bundled GLFW does not read the `GLFW_PLATFORM` environment variable. On Wayland, GLFW enters native Wayland mode, crashes in libdecor (SEGV at null function pointer), and `glfwGetWindowPos` is unimplemented — position sync is structurally impossible.

Two changes are required, both in the spike source:

In the entry point, before the JME thread starts:
```java
GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, GLFW.GLFW_PLATFORM_X11);
```

In the Linux launcher script `kepplr.sh` (a production deliverable):
```bash
unset WAYLAND_DISPLAY
export GDK_BACKEND=x11
```

`unset WAYLAND_DISPLAY` alone is insufficient — libwayland-client falls back to `$XDG_RUNTIME_DIR/wayland-0` if the socket exists. Both changes are required together.

### Workaround 2 — macOS: Thread model (required)

GLFW on macOS requires all calls on the OS main thread. `Application.launch()` also wants the main thread. The fix is to run JME on the main thread and launch JavaFX from a non-daemon background thread. `Main.java` detects `os.name` and branches accordingly. The spike has the working implementation — carry it forward exactly.

### Workaround 3 — Both platforms: Minimize and focus behavior (required)

`setAlwaysOnTop(true)` is global — the overlay floats above all windows when JME is minimized or loses focus. Fix with GLFW callbacks in `simpleInitApp()`, encapsulated in `WindowManager`:
```java
GLFW.glfwSetWindowIconifyCallback(windowHandle, (win, iconified) ->
    Platform.runLater(() -> {
        if (iconified) overlayStage.hide();
        else           overlayStage.show();
    }));

GLFW.glfwSetWindowFocusCallback(windowHandle, (win, focused) ->
    Platform.runLater(() -> overlayStage.setAlwaysOnTop(focused)));
```

`overlayStage` is shared from the overlay to `WindowManager` via `WindowState`. These are the only `Platform.runLater()` calls permitted outside `SimulationStateFxBridge`.

### Workaround 4 — Shutdown hook through `destroy()` (architectural)

`jmeApp.start()` is non-blocking on Linux — it spawns "jME3 Main" and returns immediately. `stop()` is not called on all close paths. The correct and reliable JME→JavaFX shutdown hook is `destroy()`, called by `LwjglWindow` from "jME3 Main" as its final act on every close path on both platforms. Wire the shutdown through `destroy()`, not `stop()`.

### Build note — cross-platform development

When copying the project between Linux and macOS, stale JavaFX JARs in `target/javafx-mods/` cause a `FindException: Two versions of module` crash at launch. `mvn clean package` resolves it. Document this in the build instructions.

---

## Completed Steps

### 1. Project Scaffold
Maven project with dependency declarations for JMonkeyEngine, Picante, JavaFX, and Groovy. A blank JME window running alongside a blank JavaFX window, with the JME/JavaFX threading model resolved.

### 2. Ephemeris Layer
`KEPPLREphemeris` and `KEPPLRConfiguration` implemented with the singleton/ThreadLocal access pattern and full threading rules. Unit tests pass against known SPICE values.

### 3. First Render
Earth rendered as a textured sphere at its correct heliocentric position at the current wall clock time. Floating origin established — scene coordinates are camera-relative in km. World-space units and tessellation level defined in `KepplrConstants`.

### 4. SimulationState, SimulationCommands, and Interaction Modes
Concrete implementations of `SimulationState` (observable properties) and `SimulationCommands` (input interface). Selected, targeted, and focused interaction mode semantics per §4. Light-time correction per §6 applied to camera pointing.

### 5. Tracked Mode
Tracked mode per §4.6. Tracking anchor stored in `SimulationState` as normalized screen coordinates. `targetBody()` clears tracking as a side effect.

### 6. Synodic Camera Frame
Synodic frame definition and math per §5. The targeted body serves as the synodic "other body." `CameraFrame` enum (`INERTIAL`, `BODY_FIXED`, `SYNODIC`) and `setCameraFrame()` added to `SimulationCommands`. `BODY_FIXED` deferred. Degenerate case threshold (1e-3 radians) in `KepplrConstants`.

### 7. Time Control
Anchor-based ET advancement in `simpleUpdate()`. Pause/resume, time rate, `setET()`, `setUTC()`. Simulation starts at current wall clock time at 1x real time. Negative time rates supported. `deltaSimSecondsProperty()` exposed via `SimulationState` for downstream consumers.

### 8. JavaFX Control Window — Status Display
Programmatic JavaFX control window displaying all §10.2 fields. `SimulationStateFxBridge` is the sole location of `Platform.runLater()` calls. JME HUD overlay displaying current UTC time, updated on the JME render thread for frame-accurate consistency with `SimulationState`.

### 9. JavaFX Time Controls
Menu bar scaffolded with File, Time, Camera, Window menus. Time menu: Pause/Resume toggle, Set Time dialog (UTC + Julian Date bidirectional sync), Set Time Rate dialog. Ikonli added for menu icons. Parse errors reported in the status window.

### 10. Basic Camera Navigation
Mouse and keyboard camera controls consistent with Celestia. Left drag rotates in place; right drag orbits around focus body; scroll/PgUp/PgDn zooms. Arrow keys tilt/roll; Shift+arrows orbit. Orbit is camera-relative (screen-right and screen-up axes). Exponential zoom clamped at 1.1x body radius (minimum) and 1e15 km (maximum). All thresholds in `KepplrConstants`.

### 11. Multi-Body Rendering, Multi-Frustum, and Culling Rules
All bodies from `getBodies()` and `getSpacecraft()` rendered or culled each frame. Three-layer frustum assignment (Near/Mid/Far) with 10% overlap per §8. Sun as emissive light source. Spacecraft as point sprites. Bodies without orientation data rendered as untextured spheres per §12.3. Ellipsoids used throughout. Culling rules: apparent radius ≥ 2 px renders as full geometry; apparent radius < 2 px renders as point sprite for all bodies including satellites. Sprite cluster suppression: when two sprites fall within 2 px of each other on screen, only the body with the larger physical radius renders; the other is culled. Bodies currently held in selected, focused, targeted, or tracked state are exempt from cluster suppression. The 2 px geometry threshold and the 2 px cluster proximity threshold are separate named constants in `KepplrConstants`.

### 12. Orbital Trails
Trail rendering per §7.5. Trails sampled from SPICE at 180 samples per orbital period (approx. every 2 degrees), defaulting to 30 days if orbital period cannot be determined. Trails update dynamically with simulation time. Trail segments assignable to different frustum layers per §8.3. Trails drawable in heliocentric J2000 by default, optionally in other frames. Trail duration specifiable as an argument. A distinct rendering subsystem from body rendering with its own update cadence and memory management.

### 13. Vector Overlays
Instantaneous vector rendering using a strategy-interface design. `VectorType` is an interface with `computeDirection(int originNaifId, double et)`. `VectorTypes` provides static factory methods: `velocity()`, `bodyAxisX/Y/Z()`, `towardBody(int targetNaifId)`. `towardBody` covers Sun direction, Earth direction, and direction toward any arbitrary body by NAIF ID. Adding a new vector type is a data change, not a code change — no switch/case over vector type anywhere in rendering logic. Arrow length is a multiple of the focused body's mean radius (`VECTOR_ARROW_FOCUS_BODY_RADIUS_MULTIPLE` in `KepplrConstants`), recomputed each frame when the focused body changes.

### 14. Star Field
Star field renderer per §7.4 using the Yale Bright Star Catalog (`ybsc5.gz`) with proper motions applied at current simulation ET. Star catalog abstracted behind `StarCatalog`/`Star` interfaces; Gaia catalog stubbed but deferred pending user-downloaded files. Rendered in a separate frustum pass at effectively infinite distance. Magnitude cutoff and point sprite size constants in `KepplrConstants`.

### 15. Body-Fixed Camera Frame
`BODY_FIXED` implemented in `CameraFrame`. PCK rotation matrix from `KEPPLREphemeris` applied to camera orientation each frame for the focused body. Camera pose remains expressible in heliocentric J2000 at all times. Fallback to `INERTIAL` when focused body has no orientation data; fallback state exposed via `SimulationState`. Camera menu added to JavaFX control window with `Inertial`/`Body-Fixed`/`Synodic` radio items bound to `activeCameraFrameProperty()`. Synodic frame also fully applied to visualization in this step.

### 16. Rings, Shadows, and Eclipses

#### 16a. Saturn's Rings
Ring geometry for Saturn (NAIF ID 699 only) rendered as a flat annular disk with albedo and alpha texture encoding the Cassini Division and other gaps. Ring brightness and transparency profiles stored as inline data. Ring plane oriented by Saturn's PCK north pole axis rotated into J2000 each frame. Ring inner and outer radii in `KepplrConstants`. Ring geometry established as shadow caster, shadow receiver, and Sun halo occluder from the start.

#### 16b. Shadows and Eclipses
Analytic physical eclipse geometry per §9.3 hybrid Option C. Sun treated as extended source using radius from `KEPPLREphemeris.getShape(sunNaifId)` at point of use. All bodies except the Sun are shadow receivers and shadow casters. Saturn's rings are a first-class simultaneous caster and receiver. Caster/receiver loop is fully general — all bodies evaluated against all receivers including rings; no body hardcoded as sole caster or receiver. Penumbra fraction is continuous in [0,1]. Night side renders dimmer than day side with smooth terminator transition. Near-frustum shape-model refinement stubbed with marked comment. `RenderQuality` enum (`LOW`/`MEDIUM`/`HIGH`) controls shadow model (point-source vs. extended-source analytic), max occluders per receiver, trail density, and star magnitude cutoff. Sun halo quality stub present for step 17.

### 17. Sun Halo
Sun halo implemented as a world-space billboard with custom GLSL 150 shader (`SunHalo.vert/frag`). Halo size scales with Sun's apparent angular diameter recomputed each frame. Brightness and falloff parameterized as named constants tuned visually. Halo always renders regardless of Sun body cull state; suppressed only when Sun is outside view frustum. Bodies and Saturn's rings occlude the halo correctly per §8.4. Halo node assigned to FAR frustum layer with a marked comment noting layer reassignment if Sun ever enters MID or NEAR range. `KepplrConstants.SUN_NAIF_ID = 10` promoted from `BodyNodeFactory`. `RenderQuality` sun halo quality stub replaced with real per-tier values.

---

## Remaining Steps

### 18. Camera Transitions
`pointAt(int naifId, double durationSeconds)` and `goTo(int naifId, double apparentRadiusDeg, double durationSeconds)` added to `SimulationCommands`. Transitions execute on the JME render thread, progressing each frame in `simpleUpdate()`. Transitions are non-blocking — commands return immediately and progress is tracked in `SimulationState`. `pointAt` slews the camera orientation from current look direction to the target body direction over `durationSeconds` using spherical linear interpolation (slerp). `goTo` waits for any in-progress `pointAt` to complete, then translates the camera along its current line of sight until the target body subtends the requested apparent radius. Both transitions use linear interpolation initially; acceleration/deceleration is a deferred refinement. `goTo` does not apply light-time correction to the translation path. `SimulationState` exposes `transitionActiveProperty()` (boolean) and `transitionProgressProperty()` (double in [0,1]) so the UI and scripting layer can observe completion. If a new `pointAt` or `goTo` is issued while a transition is in progress, the in-progress transition is cancelled and the new one begins immediately. `waitTransition()` implemented as a blocking primitive that returns when `transitionActiveProperty()` becomes false — defined here alongside the property it depends on and exposed through the Groovy scripting wrapper in step 20. Existing `focusBody()` and `targetBody()` implementations updated to drive `pointAt`/`goTo` internally so interaction mode semantics are consistent with the new transition system. All duration and interpolation constants in `KepplrConstants`.

### 19. UI — Overlay, Body Selection, and Camera Controls

**Pre-implementation requirement:** Read the spike project source at `../KEPPLR-overlay-spike/` in full before writing any code. The spike contains working implementations of all platform workarounds described in the Spike Findings section. Carry those implementations forward exactly — do not reinvent them. Also read `../KEPPLR-pre/src/main/java/kepplr/ui/fx/FxUiController.java` for layout reference.

The existing two-window layout is replaced with a transparent JavaFX overlay stage positioned over the JME window per the Option B architecture confirmed by the spike. `WindowManager` in `com.kepplr.core` encapsulates the overlay stage, GLFW position/minimize/focus callbacks, and the sanctioned `Platform.runLater()` calls for window management. All four spike workarounds (Linux X11 forcing, macOS thread model, minimize/focus callbacks, `destroy()` shutdown hook) must be applied exactly as implemented in the spike. The Linux launcher script `kepplr.sh` is a production deliverable. Retina display coordinate alignment must be confirmed on a native MacBook display before marking done.

Control panel layout: a collapsible sidebar panel anchored to the right edge of the render view containing: (1) current body readout — selected, focused, targeted, tracked as labeled rows with body name and action buttons (Focus, Target, Clear) on the selected body row; (2) time display and rate control from step 9; (3) camera frame indicator and transition progress bar (visible only during active transition, collapses when idle).

Body list panel: a separate collapsible panel showing all bodies from `KEPPLREphemeris.getKnownBodies()`, grouped in a tree by primary body, ordered by distance from the Sun. Top-level entries are bodies with Solar System Barycenter as primary (Sun, planet barycenters, spacecraft, asteroids, comets). Each planet barycenter expands to its satellites. Body names resolved via `SpiceBundle.getObjectName()`. List refreshes when `KEPPLRConfiguration.reload()` is called. Clicking a body selects it; double-clicking focuses it.

Menus: File menu — Load Configuration (`KEPPLRConfiguration.reload(Path)`), parse errors reported in status panel. View menu (renamed from Camera) — Camera Frame submenu (Inertial/Body-Fixed/Synodic radio items bound to `activeCameraFrameProperty()`), Stop Tracking, Field of View control, camera frame fallback indicator. Time menu — unchanged from step 9. Window menu — preset window sizes (1280×720, 1280×1024, 1920×1080, 2560×1440); resizes JME window and repositions overlay accordingly.

Keyboard shortcuts wired through the JME input handler (not JavaFX), all calling `SimulationCommands`: G — go to focused body (`goTo`); F — follow/track focused body; T — target selected body; Escape — stop tracking; Space — pause/resume; `[`/`]` — decrease/increase time rate.

`SimulationStateFxBridge` extended with: `selectedBodyActiveProperty()` (boolean), `trackedBodyActiveProperty()` (boolean), `cameraFrameFallbackActiveProperty()` (boolean), `formatBodyName(int id)` (separate from existing `formatBodyId`), `transitionActiveProperty()`, `transitionProgressProperty()`.

Input fields accept either a name or a NAIF ID in a single field — distinguished by whether the input parses as an integer. Name resolution via `BodyLookupService` in `kepplr.ephemeris`; no name resolution logic in `ui/`.

### 20. Groovy Scripting Layer
Groovy-friendly wrapper delegating to `SimulationCommands`. Exposes `waitSim()` and `waitWall()` timing primitives wired into the time model from step 7, and `waitTransition()` from step 18. Every `SimulationCommands` call must be loggable so real-time recordings can be transcribed as valid Groovy scripts with `waitWall()` calls inserted for timing. No generic `wait()` function per §11.2.

---

## Deferred / Out of Scope for v0.1

- Camera transition acceleration/deceleration (linear interpolation used initially)
- Camera navigation inertia and damping (§14.2)
- Full camera control bindings spec (§14.2)
- Object search and autocomplete UI (§14.3)
- Visibility layer toggles (§14.4)
- Labels and HUD decluttering policy (§14.5)
- Shape model rendering for irregular bodies (§14.6)
- Determinism and reproducible replay (§14.1)
- Performance acceptance criteria and LOD rules (§14.7)
- Gaia star catalog (requires user-downloaded files)
- Native Wayland support (Linux runs under XWayland)
