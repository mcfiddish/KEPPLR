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

**`SimulationStateFxBridge` is the sole location of `Platform.runLater()` calls** with one additional sanctioned exception: `KepplrApp.destroy()` may call `Platform.runLater()` for lifecycle shutdown — this call site must have a comment explaining the sanctioned use. No other class may call `Platform.runLater()`.

**`SimulationCommands` is also the Groovy scripting API.** The scripting layer
(step 20) is a thin Groovy-friendly wrapper (`KepplrScript`) that delegates to
`SimulationCommands`. Every method on `SimulationCommands` must be loggable so
real-time recordings can be transcribed as executable Groovy scripts with
`waitWall()` timing calls inserted between commands.

**`CommandRecorder` is a decorator on `SimulationCommands`, not a subclass.**
Interactive use (status window, camera input handler) is wired through the
recorder so all user actions are capturable. Script execution (`ScriptRunner`)
uses raw `SimulationCommands` directly — scripts are never self-recording. See
D-024, D-025.

**`VectorType` carries a `toScript()` method for script serialization.**
Because `VectorType` is a strategy interface (not an enum), `CommandRecorder`
cannot introspect which factory method produced a given instance. Each concrete
implementation returned by `VectorTypes` factory methods implements `toScript()`
to return the exact Groovy expression that recreates it. Without this,
`setVectorVisible` calls in recorded scripts would emit an unrunnable placeholder.
See D-026.

**The synodic frame "other body" is the currently selected body.** No separate command or property is needed for this. If no selected body exists, fall back to the inertial frame and log a warning.

**GLB shape models replace ellipsoids and sprites for configured bodies (Step 21).** Bodies with a `BodyBlock.shapeModel()` path use a GLB attached to `bodyFixedNode`; the ellipsoid is retained as a detached `EclipseShadowManager` proxy (never rendered). Spacecraft with `SpacecraftBlock.shapeModel()` replace the point sprite with a GLB scaled by `0.001 × SpacecraftBlock.scale()`. Fallback to ellipsoid/sprite on load failure. See D-027, D-028.

**Spacecraft FK frames are unified with PCK body-fixed frames.** `hasBodyFixedFrame()` and `getJ2000ToBodyFixed()` both return results for spacecraft (via their configured FK frame from `NH_SPACECRAFT` or equivalent), not just for natural bodies with PCK data. `BodySceneManager` calls `updateRotation()` for spacecraft every frame. See D-029.

**Spacecraft camera proximity scales with SpacecraftBlock.scale().** Minimum zoom distance and `goTo` arrival distance use `scale × 0.001 km` as the effective radius rather than a fixed 1 km fallback. See D-030.

**Orbit drag is camera-relative.** Right drag orbits around the camera's current screen-right and screen-up vectors, not fixed world axes. This must not be changed to world-axis orbit without explicit discussion.

**Zoom is exponential.** Each scroll or keyboard zoom step changes camera distance by a fixed percentage. Minimum distance is 1.1× the focus body radius; maximum is 1e15 km matching the far frustum boundary.

**Camera transitions are non-blocking.** `pointAt()` and `goTo()` initiate a transition and return immediately. Transition progress is tracked in `SimulationState`. The Groovy scripting layer uses `waitTransition()` to block a script until a transition completes; the simulation loop does not block.

**`goTo()` does not use light-time correction.** The camera moves along its current line of sight to the target body. Light-time correction applies to camera pointing in tracked mode (established in step 4) but not to the goTo translation path.

**Body selection does not move the camera.** Selecting a body updates the HUD and status display only. Targeting a body slews the camera to point at it (`pointAt`) but does not change camera position. Focusing a body points at it then moves to it (`pointAt` then `goTo`).

**The Sun halo always renders.** The Sun's body geometry is subject to the 2 px culling rule, but the halo renders regardless of cull state. The halo is suppressed only when the Sun is outside the view frustum entirely.

**The JavaFX control panel is a separate OS window (two-window layout).** The transparent overlay (Option B) was prototyped and rejected — position-sync lag on macOS is unacceptable and the platform complexity is not justified for v0.1. The JavaFX stage is a normal, non-transparent, non-always-on-top window. No `WindowManager` class. No GLFW position/minimize/focus callbacks. Do not revisit this decision.

**Tracking is not a separate camera behavior.** The F key is the shortcut for switching the camera frame between Synodic and Inertial. `trackedBodyId`, `trackingAnchor`, `trackBody()`, and `stopTracking()` do not exist in the codebase. Pressing F with a selected body set switches to the Synodic frame; pressing F in Synodic switches back to Inertial. Stop Tracking no longer exists as a menu item — selecting Inertial in the Camera Frame submenu is the equivalent action.

**Mouse picking is screen-space only.** Body selection via click projects all visible bodies to screen space and finds candidates within `PICK_MIN_SCREEN_RADIUS_PX` of the click point. No 3D ray cast. When multiple candidates exist, the body with the largest actual screen-space radius wins. Click on empty space does nothing.

**Overlay visibility toggles are per-body in the API, global in the GUI.** Labels, orbit trails, and vector overlays each have per-body enable/disable on `SimulationCommands` (for scripting) and a global toggle in the Overlays menu (for interactive use). GUI global toggles call the per-body API on all known bodies.

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

### 5. Synodic Camera Frame (formerly Tracked Mode)
Synodic frame definition and math per §5. The selected body serves as the synodic "other body." `CameraFrame` enum (`INERTIAL`, `BODY_FIXED`, `SYNODIC`) and `setCameraFrame()` added to `SimulationCommands`. `BODY_FIXED` deferred. Degenerate case threshold (1e-3 radians) in `KepplrConstants`. Note: the original step 5 implemented a separate "tracked mode" with a tracking anchor; this was replaced during step 19 — tracking is now equivalent to switching to the Synodic frame.

### 6. Synodic Camera Frame
Synodic frame definition and math per §5. The selected body serves as the synodic "other body." `CameraFrame` enum (`INERTIAL`, `BODY_FIXED`, `SYNODIC`) and `setCameraFrame()` added to `SimulationCommands`. `BODY_FIXED` deferred. Degenerate case threshold (1e-3 radians) in `KepplrConstants`.

### 7. Time Control
Anchor-based ET advancement in `simpleUpdate()`. Pause/resume, time rate, `setET()`, `setUTC()`. Simulation starts at current wall clock time at 1x real time. Negative time rates supported. `deltaSimSecondsProperty()` exposed via `SimulationState` for downstream consumers.

### 8. JavaFX Control Window — Status Display
Programmatic JavaFX control window displaying all §10.2 fields. `SimulationStateFxBridge` is the sole location of `Platform.runLater()` calls (plus the sanctioned use in `KepplrApp.destroy()`). JME HUD overlay displaying current UTC time, updated on the JME render thread for frame-accurate consistency with `SimulationState`.

### 9. JavaFX Time Controls
Menu bar scaffolded with File, Time, Camera, Window menus. Time menu: Pause/Resume toggle, Set Time dialog (UTC + Julian Date bidirectional sync), Set Time Rate dialog. Ikonli added for menu icons. Parse errors reported in the status window.

### 10. Basic Camera Navigation
Mouse and keyboard camera controls consistent with Celestia. Left drag rotates in place; right drag orbits around focus body; scroll/PgUp/PgDn zooms. Arrow keys tilt/roll; Shift+arrows orbit. Orbit is camera-relative (screen-right and screen-up axes). Exponential zoom clamped at 1.1x body radius (minimum) and 1e15 km (maximum). All thresholds in `KepplrConstants`.

### 11. Multi-Body Rendering, Multi-Frustum, and Culling Rules
All bodies from `getBodies()` and `getSpacecraft()` rendered or culled each frame. Three-layer frustum assignment (Near/Mid/Far) with 10% overlap per §8. Sun as emissive light source. Spacecraft as point sprites. Bodies without orientation data rendered as untextured spheres per §12.3. Ellipsoids used throughout. Culling rules: apparent radius ≥ 2 px renders as full geometry; apparent radius < 2 px renders as point sprite for all bodies including satellites. Sprite cluster suppression: when two sprites fall within 2 px of each other on screen, only the body with the larger physical radius renders; the other is culled. Bodies currently held in selected, focused, or targeted state are exempt from cluster suppression. The 2 px geometry threshold and the 2 px cluster proximity threshold are separate named constants in `KepplrConstants`.

### 12. Orbital Trails
Trail rendering per §7.5. Trails sampled from SPICE at 180 samples per orbital period (approx. every 2 degrees), defaulting to 30 days if orbital period cannot be determined. Trails update dynamically with simulation time. Trail segments assignable to different frustum layers per §8.3. Trails drawable in heliocentric J2000 by default, optionally in other frames. Trail duration specifiable as an argument. A distinct rendering subsystem from body rendering with its own update cadence and memory management.

### 13. Vector Overlays
Instantaneous vector rendering using a strategy-interface design. `VectorType` is an interface with `computeDirection(int originNaifId, double et)`. `VectorTypes` provides static factory methods: `velocity()`, `bodyAxisX/Y/Z()`, `towardBody(int targetNaifId)`. `towardBody` covers Sun direction, Earth direction, and direction toward any arbitrary body by NAIF ID. Adding a new vector type is a data change, not a code change — no switch/case over vector type anywhere in rendering logic. Arrow length is a multiple of the focused body's mean radius (`VECTOR_ARROW_FOCUS_BODY_RADIUS_MULTIPLE` in `KepplrConstants`), recomputed each frame when the focused body changes. Note: UI toggles for vector overlays are added in step 19b.

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

### 18. Camera Transitions
`pointAt(int naifId, double durationSeconds)` and `goTo(int naifId, double apparentRadiusDeg, double durationSeconds)` added to `SimulationCommands`. Transitions execute on the JME render thread, progressing each frame in `simpleUpdate()`. Transitions are non-blocking — commands return immediately and progress is tracked in `SimulationState`. `pointAt` slews the camera orientation from current look direction to the target body direction over `durationSeconds` using spherical linear interpolation (slerp). `goTo` waits for any in-progress `pointAt` to complete, then translates the camera along its current line of sight until the target body subtends the requested apparent radius. Both transitions use linear interpolation initially; acceleration/deceleration is a deferred refinement. `goTo` does not apply light-time correction to the translation path. `SimulationState` exposes `transitionActiveProperty()` (boolean) and `transitionProgressProperty()` (double in [0,1]) so the UI and scripting layer can observe completion. If a new `pointAt` or `goTo` is issued while a transition is in progress, the in-progress transition is cancelled and the new one begins immediately. `waitTransition()` implemented as a blocking primitive that returns when `transitionActiveProperty()` becomes false — defined here alongside the property it depends on and exposed through the Groovy scripting wrapper in step 20. Existing `focusBody()` and `targetBody()` implementations updated to drive `pointAt`/`goTo` internally so interaction mode semantics are consistent with the new transition system. All duration and interpolation constants in `KepplrConstants`.

### 19. UI — Body Selection and Camera Controls

**Architecture decision (final):** Two-window layout. The JME render window
and the JavaFX control window are separate OS windows. The transparent overlay
(Option B) was prototyped and rejected — position-sync lag on macOS is
unacceptable and the platform complexity is not justified for v0.1. Do not
revisit this decision.

The JavaFX stage is a normal, non-transparent, non-always-on-top window. It
opens alongside the JME window at launch, positioned to its right. Both
windows close regardless of which one the user closes — closing either window
calls the same shutdown path as `destroy()`. No `WindowManager` class. No
GLFW position/minimize/focus callbacks. No Linux X11 forcing workaround. No
`kepplr.sh` launcher changes beyond what already exists.

**Layout reference:** Read
`../KEPPLR-pre/src/main/java/kepplr/ui/fx/FxUiController.java` before
implementing.

**Control panel layout — collapsible sidebar anchored to the right edge of
the JavaFX window:**

(1) Current body readout — three labeled rows: Selected, Focused, Targeted.
Each row shows the body name resolved via
`SimulationStateFxBridge.formatBodyName(int id)`. Each label must be bound
directly to the corresponding `SimulationStateFxBridge` observable property
— not set once at init. The Selected row has Focus, Target, and Clear action
buttons. If no body is active for a row, the label shows "—". There is no
Tracked row — the active camera frame indicator in the View menu serves this
purpose.

(2) Time display and rate control from step 9.

(3) Camera frame indicator and transition progress bar — visible only during
an active transition (bound to `transitionActiveProperty()`), collapses to
zero height when idle.

**Body list panel — separate collapsible panel:**

Populated from `KEPPLREphemeris.getKnownBodies()`, grouped in a tree by
primary body, ordered by distance from the Sun. Top-level entries are bodies
with Solar System Barycenter as primary. Each planet barycenter expands to its
satellites. Body names resolved via `SpiceBundle.getObjectName()`. List
refreshes on `KEPPLRConfiguration.reload()`.

Single click → `SimulationCommands.selectBody(naifId)`. The Selected row in
the control panel must update immediately. Double-click →
`SimulationCommands.focusBody(naifId)`.

**Menus:**
- File — Load Configuration (`KEPPLRConfiguration.reload(Path)`); parse errors
  reported in status panel. File picker defaults to properties files filter
  but also offers an "All Files" filter option.
- View — Camera Frame submenu (Inertial / Body-Fixed / Synodic radio items
  bound to `activeCameraFrameProperty()`), Field of View control, camera frame
  fallback indicator. Selecting Inertial in the Camera Frame submenu is the
  way to exit the Synodic frame via the menu.
- Time — unchanged from step 9.
- Window — preset sizes: 1280×720, 1280×1024, 1920×1080, 2560×1440; resizes
  the JME window only. JavaFX window is not resized programmatically.

**Keyboard shortcuts** — wired through the JME input handler, not JavaFX. All
call `SimulationCommands`:
- G — `goTo` focused body
- F — toggles camera frame between SYNODIC and INERTIAL. If camera frame is
  currently INERTIAL and a selected body is set, switches to SYNODIC. If
  camera frame is currently SYNODIC, switches to INERTIAL. No-op if frame is
  INERTIAL and no selected body is set.
- T — target selected body
- Space — pause/resume
- `[` / `]` — decrease / increase time rate

Escape has no keyboard binding. JavaFX default Escape-closes-stage behavior
must be suppressed on the control window. Escape must not close either window
under any circumstance.

**Tracking is not a separate camera behavior.** F is the keyboard shortcut
for toggling between the Synodic and Inertial camera frames. The Camera Frame
submenu is the menu equivalent. `trackedBodyId`, `trackingAnchor`,
`trackBody()`, and `stopTracking()` do not exist in the codebase. Any legacy
call sites are replaced with `setCameraFrame()` calls.

**Mouse picking in the JME window:**

Single click on a body → `SimulationCommands.selectBody(naifId)`. Double-click
on a body → `SimulationCommands.focusBody(naifId)`. No mouse-based targeting —
Target is only available via the T key or the control panel button.

Picking is entirely screen-space — no 3D ray cast:

1. Project every visible body to screen space and compute its actual
   screen-space radius from its projected size.
2. For each body compute effective pick radius =
   `max(actual_screen_radius, PICK_MIN_SCREEN_RADIUS_PX)`.
3. Find all bodies where the distance from the click point to the body's
   screen center ≤ effective pick radius.
4. If one or more candidates exist, return the one with the largest actual
   screen radius.
5. If no candidates exist, do nothing — do not clear the current selection.

`PICK_MIN_SCREEN_RADIUS_PX` is defined in `KepplrConstants` and must be
referenced in the pick logic. Double-click detection uses a timing threshold
constant also in `KepplrConstants`.

**`SimulationStateFxBridge` extensions** (add without removing anything
already present):
- `selectedBodyActiveProperty()` (boolean)
- `cameraFrameFallbackActiveProperty()` (boolean)
- `formatBodyName(int id)` — separate from existing `formatBodyId`
- `transitionActiveProperty()` (boolean)
- `transitionProgressProperty()` (double, [0,1])

**Input fields:** accept a body name or a NAIF ID in a single field —
distinguished by whether the input parses as an integer. Name resolution via
`BodyLookupService` in `kepplr.ephemeris`. No name resolution logic anywhere
in `ui/`.

**Styling constraints:**
- Panel backgrounds: semi-opaque dark fill, minimum rgba(0,0,0,0.72).
- All text: white or near-white (#e0e0e0 minimum). No grey-on-grey or
  grey-on-black combinations anywhere.
- Menu bar and menu item text: explicitly styled white via JavaFX CSS.
- Do not rely on default JavaFX theme colors for any text that appears over
  a dark background.

**Hard constraints — violations block sign-off:**
- `Platform.runLater()` permitted only in `SimulationStateFxBridge` and in
  `KepplrApp.destroy()` for lifecycle shutdown. The `destroy()` call site
  must have a comment explaining the sanctioned use. Nowhere else.
- No name resolution logic inside `ui/`.
- The JME window must receive all mouse and keyboard events intended for it.
  The JavaFX window must not intercept input directed at the JME window.
- `trackBody()`, `stopTracking()`, `trackedBodyId`, and `trackingAnchor` do
  not exist anywhere in the codebase after this step.
- `mvn test` passes with no new failures.

### 19b. Overlays — Labels, HUD, Trails, and Vector Toggles

This step surfaces existing rendering capabilities (labels, trails from step
12, vectors from step 13) through `SimulationCommands` and the JavaFX UI.
All toggles must be scriptable — they are part of the `SimulationCommands`
API. No keyboard shortcuts for overlays.

**Labels:**

`SimulationCommands.setLabelVisible(int naifId, boolean visible)` — per-body
label visibility. Labels display the body name resolved via
`SpiceBundle.getObjectName()`. Labels render as JME screen-space text
attached to each body node.

Decluttering policy: labels are suppressed by proximity. A label is drawn
only if no other label with a larger-radius body is within
`LABEL_DECLUTTER_MIN_SEPARATION_PX` of its screen position. This naturally
produces the zoom-dependent behavior: at large distances, major planets are
labeled and satellites are suppressed because they cluster near their primary;
as the camera moves closer and satellites separate on screen, their labels
appear. `LABEL_DECLUTTER_MIN_SEPARATION_PX` is defined in `KepplrConstants`.

GUI: Overlays menu → Labels toggle (global, calls `setLabelVisible` on all
known bodies).

**HUD:**

Two independent HUD elements rendered by JME on the render thread:
- Time display — current simulation UTC, upper-right corner.
- Info display — focused body name and distance from camera, upper-left corner.

`SimulationCommands.setHudTimeVisible(boolean)` and
`SimulationCommands.setHudInfoVisible(boolean)` — independent toggles, both
on by default.

GUI: Overlays menu → HUD/Info toggle and Show Time toggle, each independently
bound to the corresponding command.

**Orbit trails:**

`SimulationCommands.setTrailVisible(int naifId, boolean visible)` — per-body
trail visibility. `SimulationCommands.setTrailDuration(int naifId, double
seconds)` — per-body trail duration in simulation seconds. Trails fade at
their trailing end. Trail duration defaults to one orbital period (or 30 days
if period cannot be determined), matching step 12 behavior.

Decluttering policy mirrors labels: trails for satellites are suppressed when
the satellite's screen position is within `TRAIL_DECLUTTER_MIN_SEPARATION_PX`
of its primary, expanding as the camera zooms in. Constant defined in
`KepplrConstants`.

GUI: Overlays menu → Show Trajectories toggle (global, calls `setTrailVisible`
on all known bodies). Duration is not exposed in the GUI — the default
per-body duration is used.

**Vector overlays:**

`SimulationCommands.setVectorVisible(int naifId, VectorType type, boolean
visible)` — per-body, per-type vector visibility. `VectorType` strategy
interface is unchanged from step 13.

GUI: Overlays menu → Current Target submenu with individual toggle items:
- Sun Direction (`VectorTypes.towardBody(SUN_NAIF_ID)`)
- Earth Direction (`VectorTypes.towardBody(EARTH_NAIF_ID)`)
- Velocity Direction (`VectorTypes.velocity()`)
- Trajectory (trail for the focused body — shortcut for `setTrailVisible`)
- Axes (`VectorTypes.bodyAxisX/Y/Z()`)

All submenu items operate on the currently focused body. The API supports
any body and any `VectorType`; the GUI exposes the common cases only.

**`SimulationCommands` additions** (all must be loggable for step 20):
- `setLabelVisible(int naifId, boolean visible)`
- `setHudTimeVisible(boolean visible)`
- `setHudInfoVisible(boolean visible)`
- `setTrailVisible(int naifId, boolean visible)`
- `setTrailDuration(int naifId, double seconds)`
- `setVectorVisible(int naifId, VectorType type, boolean visible)`

**`SimulationState` additions:**
- `labelVisibleProperty(int naifId)` (boolean)
- `hudTimeVisibleProperty()` (boolean)
- `hudInfoVisibleProperty()` (boolean)
- `trailVisibleProperty(int naifId)` (boolean)
- `trailDurationProperty(int naifId)` (double)
- `vectorVisibleProperty(int naifId, VectorType type)` (boolean)

**Hard constraints:**
- All new `SimulationCommands` methods must be loggable.
- No rendering logic in `ui/`. The Overlays menu calls `SimulationCommands`
  only.
- Decluttering logic lives in the rendering layer, not in `SimulationCommands`
  or `ui/`.
- `mvn test` passes with no new failures.

---

### 19c. Camera Scripting API
Camera navigation commands (`zoom`, `orbit`, `tilt`, `roll`, `yaw`, `setFov`,
`setCameraPosition`, `setCameraLookDirection`, `setSynodicFrame`) added to
`SimulationCommands`. `CameraInputHandler` refactored to delegate to these
commands with `durationSeconds = 0` for all mouse and keyboard navigation.
`DEFAULT_CAMERA_TRANSITION_DURATION_SECONDS`, `FOV_MIN_DEG`, and `FOV_MAX_DEG`
added to `KepplrConstants`. Synodic frame override IDs (`synodicFrameFocusId`,
`synodicFrameTargetId`) added to `DefaultSimulationState` for
`setSynodicFrame()` without disturbing interaction state. All new methods fully
Javadoc'd with usage examples per the hard constraint in the step entry.

### 20. Groovy Scripting Layer
Groovy scripting API implemented via three new classes in `kepplr.scripting`:

- `KepplrScript` — the `kepplr` binding object exposed to scripts. Delegates
  all `SimulationCommands` methods plus String-name overloads for every method
  that takes a NAIF ID. Name resolution via `BodyLookupService` in
  `kepplr.ephemeris`; unresolvable names log the error and throw
  `IllegalArgumentException`, stopping the script. No camera math or
  simulation logic in this layer.

- `ScriptRunner` — loads and executes `.groovy` files via JSR 223 on a
  dedicated daemon thread (`kepplr-groovy-script`), separate from the JME
  render thread and JavaFX thread. If `Run Script` is invoked while a script
  is already running, a confirmation dialog is shown; if confirmed, the current
  thread is interrupted and `cancelTransition()` is called before the new
  script starts.

- `CommandRecorder` — decorator on `SimulationCommands` that intercepts every
  method call and records method name, arguments, and wall timestamp. On stop,
  serializes the log as a runnable Groovy script with `waitWall()` calls
  inserted between commands. Instant camera commands (`durationSeconds == 0`)
  are coalesced within a 250ms window using a hybrid pose-snapshot / delta
  strategy rather than recorded verbatim (see D-024). Commands with
  `durationSeconds > 0` are never coalesced.

Timing primitives on `KepplrScript`: `waitWall(double seconds)`,
`waitSim(double seconds)`, `waitUntilSim(double etSeconds)`,
`waitUntilSim(String utc)`, `waitTransition()`. No generic `wait()` per §11.2.
`waitSim` and `waitUntilSim` poll at `SCRIPT_WAIT_POLL_INTERVAL_MS` intervals;
both block indefinitely if the simulation is paused or the time rate works
against the target — documented in Javadoc.

`cancelTransition()` added to `SimulationCommands` and implemented through
`TransitionController` to support clean script interruption.

`File → Run Script` and `File → Start/Stop Recording` (CheckMenuItem) added
to `KepplrStatusWindow`. Recording start wraps the active `SimulationCommands`
in a `CommandRecorder`; stop unwraps it and opens a file-save dialog.

`VectorType.toScript()` added to the strategy interface so `CommandRecorder`
can serialize `setVectorVisible` calls correctly (see D-026).

`SCRIPT_WAIT_POLL_INTERVAL_MS` and `RECORDER_COALESCE_WINDOW_MS` added to
`KepplrConstants`.

---

### 21. GLB Shape Model Rendering
    This step replaces ellipsoid and point sprite geometry with GLB shape models
    for bodies and spacecraft that have them configured, while leaving all existing
    rendering paths intact for bodies without models.
    What this step delivers:

- GLTFUtils ported from the prototype — reads the
  modelToBodyFixedQuat quaternion from GLB JSON extras with no
  third-party JSON library
- resourcesFolder() registered as a JME FileLocator at startup so
  shape model paths from BodyBlock and SpacecraftBlock resolve correctly
- BodyNodeFactory updated to load a GLB and attach it as glbModelRoot
  under bodyFixedNode when BodyBlock.shapeModel() is non-null; KEPPLR's
  body material pipeline (equirectangular mapping, texture alignment,
  center-longitude) applies as before
- Spacecraft GLBs loaded similarly under their scene node; GLB-embedded PBR
  materials and textures are used as-is and lit by the scene sun light;
  uniform scale of 0.001 × SpacecraftBlock.scale() converts meters to km
- Graceful fallback to ellipsoid (bodies) or point sprite (spacecraft) on
  null path, missing file, or load failure — WARN log, no crash

** Frame semantics **: modelToBodyFixedQuat is applied once at load time as
glbModelRoot's local rotation, composing with bodyFixedNode's
time-varying SPICE frame rotation. It is never updated per-frame.
**Out of scope for this step:** LOD; shadow refinement using shape model geometry (§9.3).

**Post-completion refinements (same branch):**
- Spacecraft FK frame registered in `stateTransformMap`; `hasBodyFixedFrame()` / `getJ2000ToBodyFixed()` unified to cover both PCK and FK frames; `BodySceneManager` calls `updateRotation()` for spacecraft each frame (D-029)
- Camera min-zoom and `goTo` arrival distance scale with `SpacecraftBlock.scale() × 0.001` rather than a fixed 1 km fallback (D-030)
- `BodySceneManager.dispose()` + `KepplrApp.rebuildBodyScene()` + `KepplrStatusWindow.configReloadCallback` enable shape model hot-reload when a new config file is loaded (D-031)
- `VectorRenderer` and `TransitionController` fall back to `BODY_DEFAULT_RADIUS_KM` for shape-less bodies instead of skipping them entirely

---

### 22. Instrument Frustum Overlays

Translucent frustum pyramid overlays for instrument fields of view, loaded from IK
kernels via `KEPPLREphemeris.getInstruments()`.

- `InstrumentFrustumManager` builds one pyramid mesh per instrument at load time,
  recomputes vertex positions in-place each frame via `FloatBuffer` mutation, and
  assigns the geometry to the correct frustum layer based on the spacecraft apex
  distance — matching the `VectorRenderer` pattern (apex distance, not apex + extent).
- CIRCLE FOVs (1 bound vector) and ELLIPSE FOVs (2 bound vectors) are approximated
  as `INSTRUMENT_FRUSTUM_CIRCLE_APPROX_SIDES = 32`-sided polygons: CIRCLE via
  Rodrigues' rotation of the bound vector around the boresight axis; ELLIPSE via
  `cos(t)·a + sin(t)·b` parameterization. RECTANGLE and POLYGON FOVs use SPICE
  bound vectors directly. Effective bounds are computed once at load time and stored
  in `FrustumEntry.effectiveBounds` (see D-033).
- `SimulationCommands.setFrustumVisible(int naifCode, boolean)` and
  `setFrustumVisible(String name, boolean)` added. Visibility state held in
  `DefaultSimulationState` via a per-instrument `ConcurrentHashMap`. `KepplrApp.syncFrustums()`
  propagates state → manager each frame (idempotent on repeated same-value calls).
- `InstrumentFrustumManager.reload()` called from `rebuildBodyScene()` — entries
  are rebuilt when a new configuration is loaded.
- `rebuildBodyScene()` expanded to a full render manager restart: `TrailManager`,
  `SunHaloRenderer`, and `LabelManager` each gained `dispose()` methods; all
  managers are torn down and reconstructed on every config reload (see D-032).

**Out of scope for this step:** boresight line rendering; frustum shortening when the
boresight intersects a body surface; per-instrument color configuration (hardcoded
cyan); distance culling of frustums.

---

## v0.1 Complete

Steps 1–22 constitute the v0.1 release. All items below are post-v0.1 work.

---

## Known Limitations

**macOS: JME window requires a click to receive keyboard focus.**
Moving the mouse from the JavaFX control window into the JME render window
does not automatically transfer keyboard focus on macOS. The user must click
the JME window before keyboard shortcuts will respond. This is a macOS window
management constraint. Behavior on Linux (X11) is untested and may differ.

---

## v0.2 Steps

### 23. Rendering Enhancements ✓

Custom body surface shading and ring scattering improvements, based on
side-by-side comparison with Cosmographia (Saturn, March 2026).

**Delivered:**

- **sRGB color-space correction.** Textures loaded as sRGB; shader converts to
  linear for lighting math, back to sRGB on output. Resolved oversaturated
  colors (vivid yellows → muted creamy tones). Night-side ambient lowered to
  linear 0.001 (≈ sRGB 3%) to preserve day/night contrast after gamma lift.

- **Wrap lighting (soft terminator).** Replaced hard `smoothstep(−0.05, 0.05,
  NdotL)` with `(NdotL + wrap) / (1 + wrap)` where `BODY_WRAP_FACTOR = 0.15`.
  Smooth gradient across the terminator instead of a hard cutoff.

- **Minnaert limb darkening.** `pow(NdotL, k) × pow(NdotV, k−1)` with
  `BODY_LIMB_DARKENING_K = 1.3`. Darkens disk edges, giving spheres perceived
  depth. View direction obtained via `g_CameraPosition` world parameter.

- **Ring angle-dependent scattering.** Replaced inverted phase function in
  `SaturnRings.frag` with correct forward/backscatter model. Backscatter
  (same-side) brightness driven by phase angle; forward scatter (opposite-side)
  boosted by `1 + strength × pow(cosForward, exponent)`. Three new constants:
  `RING_FORWARD_SCATTER_STRENGTH = 0.8`, `RING_FORWARD_SCATTER_EXPONENT = 3.0`,
  `RING_UNLIT_SIDE_BRIGHTNESS = 0.2`.

- All constants in `KepplrConstants`, passed as shader uniforms.
- Eclipse shadow system unchanged — penumbra/ring shadow layer on top.
- Spacecraft GLBs unaffected (embedded PBR materials preserved per D-028).

---

### 24. Cinematic Camera Commands and Transition Easing

Two related improvements to the camera transition system:

**Cinematic commands.** `truck(double km, double durationSeconds)`,
`crane(double km, double durationSeconds)`, and
`dolly(double km, double durationSeconds)` added to `SimulationCommands`.
These translate the camera along its screen-right, screen-up, and look
direction axes respectively, preserving orientation. All three are
non-blocking, use `TransitionController`, and are loggable by
`CommandRecorder`. Unlike `goTo` (which targets a body), these are pure
spatial translations with no body reference — useful for framing shots in
scripts. Coalescing in `CommandRecorder` follows the same hybrid strategy as
`orbit`/`zoom` (D-024). `CameraInputHandler` does not delegate to these —
they are script-only primitives unless a future UI binding is added. See
D-023 for the command structure pattern established in step 19c.

**Transition easing.** Replace linear interpolation with
acceleration/deceleration curves for `pointAt`, `goTo`, and the new cinematic
commands. A smoothstep or cubic ease-in-out applied to the interpolation
parameter `t` before it reaches the slerp/lerp call is the minimal change.
Easing constants in `KepplrConstants`. The scripting API and
`CommandRecorder` are unaffected — easing is internal to
`TransitionController`.

---

### 25. Screenshot Capture and Animation Sequences

Two capabilities:

**(1) Single screenshot.** `File → Save Screenshot` menu action and
`SimulationCommands.saveScreenshot(Path)` (with a String overload on
`KepplrScript`). Writes the current JME framebuffer to a PNG file.
`ViewPort.addProcessor()` with a `ScreenshotAppState` or equivalent is the
JME pattern — the capture must wait for the frame to finish rendering. The
command is loggable by `CommandRecorder`. The GUI action opens a file-save
dialog; the scripting API writes to the specified path directly.

**(2) Animation sequences.** `captureSequence(String outputDir,
double startET, int frameCount, double etStep)` on `KepplrScript`. Sets ET
to `startET`, pauses the simulation, then loops `frameCount` times: advance
ET by `etStep`, wait for one frame to render, capture a screenshot to
`outputDir/frame_NNNN.png`. The sequence runs on the script thread and must
block until each frame is rendered before capturing. The user combines the
resulting PNGs into video externally (e.g., ffmpeg). After the sequence
completes, the simulation remains paused at the final ET.

The single-screenshot primitive is a prerequisite for the sequence — the
sequence is implemented as a loop calling the single-frame capture.

---

### 26. State Snapshot Strings

A compact serialized encoding of the current simulation state as a single
copy-pasteable string.

**Format:** Base64-encoded (URL-safe variant), versioned with a leading
version byte so future field additions don't break older strings. Internal
representation TBD — JSON for readability during development, packed binary
if string length becomes a concern.

**Minimal field set:** ET, time rate, paused flag, camera position and
orientation in heliocentric J2000 (the canonical frame per §1.4), camera
frame enum, focused/targeted/selected NAIF IDs, FOV. Overlay visibility
(labels, trails, vectors, frustums) deferred to a future "full snapshot"
extension.

**Integration points:**
- `SimulationCommands.getStateString()` / `setStateString(String)` — scripts
  can capture and restore snapshots. `setStateString` jumps instantly (no
  transition animation) for predictable script behavior; a future
  `setStateStringAnimated` could be added if smooth restoration is wanted.
- GUI: `Edit → Copy State` copies to clipboard; `Edit → Paste State` reads
  from clipboard and applies. Both are loggable by `CommandRecorder`.

---

### 27. Script Configuration Reload and Menu Tooltips

Two small items bundled as a cleanup step.

**Script-initiated configuration reload.** Add
`loadConfiguration(String path)` to `SimulationCommands` (and a String
overload on `KepplrScript`). Delegates to the same
`KEPPLRConfiguration.reload(Path)` + `rebuildBodyScene()` path already used
by `File → Load Configuration`. This allows a script to switch kernel sets
mid-execution — e.g., load a comet configuration, run a flyby sequence, then
reload the default. The command is loggable by `CommandRecorder`. Error
handling matches the interactive path: parse errors are reported via the
status window and the script continues with the previous configuration.

**Menu item tooltips.** Add descriptive tooltips to all menu items in the
JavaFX control window. Tooltip text describes the action and shows the
keyboard shortcut if one exists (e.g., "Point the camera at the selected
body (T)"). JavaFX `MenuItem` supports tooltips indirectly via a custom
graphic node or by setting a tooltip on the internal label. This is a polish
pass with no architectural impact.

**Status window layout improvements.** Several usability changes to
`KepplrStatusWindow`:

- **Body readout:** Each row now shows "Name (NAIF_ID)" with camera-to-body
  distance right-aligned on the same line. Distance auto-switches units: metres
  (< 1 km), km (< 0.01 AU), AU (≥ 0.01 AU). Row order changed to Focused →
  Targeted → Selected (focused is the camera anchor, displayed first). Clear
  button removed; only Focus and Target buttons remain on the Selected row.
  (See D-036.)

- **Window:** Width increased from 380px to 440px. Always-on-top enabled
  (`stage.setAlwaysOnTop(true)`). JavaFX `Separator` lines added between body
  readout, status section, and body list sections.

- **Live body filtering:** The search field filters the body tree as the user
  types (case-insensitive match on display name or NAIF ID). Parent groups are
  shown expanded when any child matches. Enter still resolves exact NAIF IDs
  and selects the body. Section header renamed to "Select Body".

- **Transition bar removed.** Camera transitions are fast enough that the
  progress bar was not useful.

- **KEPPLRConfiguration.reload() race fix.** `load(PropertiesConfiguration)`
  now builds into a local variable and assigns `instance` atomically at the
  end, eliminating the window where `getInstance()` would throw
  `IllegalStateException` during config reload. (See D-036, D-037.)

- **Script output panel.** Groovy script stdout/stderr captured via
  `ScriptOutputListener` and `LineForwardingWriter`; output displayed in a
  `TextArea` below the body tree. `ConcurrentLinkedQueue` + `AnimationTimer`
  drain pattern avoids `Platform.runLater()` from the script thread. Capped at
  200 lines.

- **Draggable SplitPane.** Body tree and script output panel wrapped in a
  vertical `SplitPane` (default 75/25 split) so the user can resize the script
  output area.

- **Consistent CheckMenuItem toggles.** All menu toggles (Overlays, Instruments,
  File > Record Session, body tree context menu) use `CheckMenuItem` instead
  of a mix of `CheckBox` + `CustomMenuItem` and `CheckMenuItem`. Removed
  `menuCheckBox` helper and `CheckBox` import.

- **Bidirectional overlay / context menu sync.** The "Current Focus" submenu
  items now bind to `SimulationState` visibility properties for the focused
  body. Changes from any source (context menu, scripts, etc.) update the
  overlays menu checkmarks automatically. Listeners rebind on focus change.
  (See D-038.)

- **Dynamic context menu.** Right-click on a body tree item opens a context menu
  with Focus, Target, and toggle items (Trail, Label, Axes) reflecting current
  state. (See D-039.)

- **Vector arrowheads.** Vectors render as arrows: line shaft + 8-segment cone
  arrowhead (12% of shaft length). Shaft line width increased to 2px.

- **Body-fixed axes scale to origin body.** `VectorType.usesOriginBodyRadius()`
  (default `false`, `true` for body axes) tells `VectorRenderer` to use the
  origin body's rendered radius (via `BodySceneManager.getEffectiveBodyRadiusKm`)
  instead of the focused body's. Spacecraft axes now scale proportionally to the
  GLB bounding radius including the configured `scale()` factor. (See D-040.)

**Step 27 is complete.**

---

## Backlog (unsequenced, post-v0.2)

- Camera navigation inertia and damping (§16.2)
- Full camera control bindings spec (§16.2)
- Object search and autocomplete UI (§16.3)
- Determinism and reproducible replay (§16.1)
- Performance acceptance criteria and LOD rules (§16.7)
- Gaia star catalog (requires user-downloaded files)
- Native Wayland support (Linux runs under XWayland)
- Instrument boresight line rendering (frustum overlays implemented in Step 22; boresight as a separate line is deferred)
- Frustum shortening when boresight intersects a body surface
- Per-instrument frustum color configuration (hardcoded cyan for now)