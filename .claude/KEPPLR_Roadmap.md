# KEPPLR Build Roadmap

A high-level reference for the recommended build order of the KEPPLR 3D solar
system simulator.

---

## Starting a New Chat Session

**Always begin every new Claude Code session with:**

> Please read AGENTS.md, REDESIGN.md, this roadmap, and DECISIONS.md in full
> before doing anything else. Then run `mvn test` and report any pre-existing
> failures. Then state which step of the roadmap we are on and which classes you
> expect to touch.

This ensures CC has the full architectural context, knows what has already been
decided, and does not re-litigate settled choices.

---

## Key Decisions

These are non-obvious choices made during development. If CC questions them in a
new session, refer it back to this section rather than re-opening the
discussion.

**World-space units are kilometers.** Ephemeris data comes from SPICE in km and
scene coordinates are in km. Precision at astronomical scales is handled by the
floating origin, not by choosing different units.

**Floating origin.** Scene graph coordinates are always camera-relative,
recentered each frame. This keeps JME's single-precision floats well within
range regardless of true heliocentric distances. This must never be removed or
bypassed.

**Anchor-based time model.** ET is computed as `currentET = anchorET + timeRate
× (wallNow - anchorWall)` from an immutable anchor record replaced atomically
via `AtomicReference`. Rate changes, pause/resume, and `setET()` all rebase the
anchor at the current moment — no time jumps occur. `wallNow` is clamped at the
moment of pause and resumes from the current wall time on unpause.

**`SimulationStateFxBridge` is the sole location of `Platform.runLater()`
calls** with one additional sanctioned exception: `KepplrApp.destroy()` may call
`Platform.runLater()` for lifecycle shutdown — this call site must have a
comment explaining the sanctioned use. No other class may call
`Platform.runLater()`.

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

**`VectorType` carries a `toScript()` method for script serialization.** Because
`VectorType` is a strategy interface (not an enum), `CommandRecorder` cannot
introspect which factory method produced a given instance. Each concrete
implementation returned by `VectorTypes` factory methods implements `toScript()`
to return the exact Groovy expression that recreates it. Without this,
`setVectorVisible` calls in recorded scripts would emit an unrunnable
placeholder. See D-026.

**The synodic frame "other body" is the currently selected body.** No separate
command or property is needed for this. If no selected body exists, fall back to
the inertial frame and log a warning.

**GLB shape models replace ellipsoids and sprites for configured bodies (Step
21).** Bodies with a `BodyBlock.shapeModel()` path use a GLB attached to
`bodyFixedNode`; the ellipsoid is retained as a detached `EclipseShadowManager`
proxy (never rendered). Spacecraft with `SpacecraftBlock.shapeModel()` replace
the point sprite with a GLB scaled by `0.001 × SpacecraftBlock.scale()`.
Spacecraft GLB rendering prioritizes KEPPLR illumination/body-shadow behavior
over full PBR material fidelity. Fallback to ellipsoid/sprite on load failure.
See D-027 and D-074.

**Spacecraft FK frames are unified with PCK body-fixed frames.**
`hasBodyFixedFrame()` and `getJ2000ToBodyFixed()` both return results for
spacecraft (via their configured FK frame from `NH_SPACECRAFT` or equivalent),
not just for natural bodies with PCK data. `BodySceneManager` calls
`updateRotation()` for spacecraft every frame. See D-029.

**Spacecraft camera proximity scales with SpacecraftBlock.scale().** Minimum
zoom distance and `goTo` arrival distance use `scale × 0.001 km` as the
effective radius rather than a fixed 1 km fallback. See D-030.

**Orbit drag is camera-relative.** Right drag orbits around the camera's current
screen-right and screen-up vectors, not fixed world axes. This must not be
changed to world-axis orbit without explicit discussion.

**Zoom is exponential.** Each scroll or keyboard zoom step changes camera
distance by a fixed percentage. Minimum distance is 1.1× the focus body radius;
maximum is 1e15 km matching the far frustum boundary.

**Camera transitions are non-blocking.** `pointAt()` and `goTo()` initiate a
transition and return immediately. Transition progress is tracked in
`SimulationState`. The Groovy scripting layer uses `waitTransition()` to block a
script until a transition completes; the simulation loop does not block.

**`goTo()` does not use light-time correction.** The camera moves along its
current line of sight to the target body. Light-time correction applies to
camera pointing in tracked mode (established in step 4) but not to the goTo
translation path.

**Body selection does not move the camera.** Selecting a body updates the HUD
and status display only. Targeting a body slews the camera to point at it
(`pointAt`) but does not change camera position. Focusing a body points at it
then moves to it (`pointAt` then `goTo`).

**The Sun halo always renders.** The Sun's body geometry is subject to the 2 px
culling rule, but the halo renders regardless of cull state. The halo is
suppressed only when the Sun is outside the view frustum entirely.

**The JavaFX control panel is a separate OS window (two-window layout).** The
transparent overlay (Option B) was prototyped and rejected — position-sync lag
on macOS is unacceptable and the platform complexity is not justified for v0.1.
The JavaFX stage is a normal, non-transparent, non-always-on-top window. No
`WindowManager` class. No GLFW position/minimize/focus callbacks. Do not revisit
this decision.

**Tracking is not a separate camera behavior.** Selecting `SYNODIC` in the
Camera Frame submenu switches into the synodic view using the focused body plus
the currently selected body. Selecting `INERTIAL` exits it. `trackedBodyId`,
`trackingAnchor`, `trackBody()`, and `stopTracking()` do not exist in the
codebase. Stop Tracking no longer exists as a menu item — selecting Inertial in
the Camera Frame submenu is the equivalent action.

**Mouse picking is screen-space only.** Body selection via click projects all
visible bodies to screen space and finds candidates within
`PICK_MIN_SCREEN_RADIUS_PX` of the click point. No 3D ray cast. When multiple
candidates exist, the body with the largest actual screen-space radius wins.
Click on empty space does nothing.

**Overlay visibility toggles are per-body in the API, global in the GUI.**
Labels, orbit trails, and vector overlays each have per-body enable/disable on
`SimulationCommands` (for scripting) and a global toggle in the Overlays menu
(for interactive use). GUI global toggles call the per-body API on all known
bodies.

---

## Completed Steps

### 1. Project Scaffold
Maven project with dependency declarations for JMonkeyEngine, Picante, JavaFX,
and Groovy. A blank JME window running alongside a blank JavaFX window, with the
JME/JavaFX threading model resolved.

### 2. Ephemeris Layer
`KEPPLREphemeris` and `KEPPLRConfiguration` implemented with the
singleton/ThreadLocal access pattern and full threading rules. Unit tests pass
against known SPICE values.

### 3. First Render
Earth rendered as a textured sphere at its correct heliocentric position at the
current wall clock time. Floating origin established — scene coordinates are
camera-relative in km. World-space units and tessellation level defined in
`KepplrConstants`.

### 4. SimulationState, SimulationCommands, and Interaction Modes
Concrete implementations of `SimulationState` (observable properties) and
`SimulationCommands` (input interface). Selected, targeted, and focused
interaction mode semantics per §4. Light-time correction per §6 applied to
camera pointing.

### 5. Synodic Camera Frame (formerly Tracked Mode)
Synodic frame definition and math per §5. The selected body serves as the
synodic "other body." `CameraFrame` enum (`INERTIAL`, `BODY_FIXED`, `SYNODIC`)
and `setCameraFrame()` added to `SimulationCommands`. `BODY_FIXED` deferred.
Degenerate case threshold (1e-3 radians) in `KepplrConstants`. Note: the
original step 5 implemented a separate "tracked mode" with a tracking anchor;
this was replaced during step 19 — tracking is now equivalent to switching to
the Synodic frame.

### 6. Synodic Camera Frame
Synodic frame definition and math per §5. The selected body serves as the
synodic "other body." `CameraFrame` enum (`INERTIAL`, `BODY_FIXED`, `SYNODIC`)
and `setCameraFrame()` added to `SimulationCommands`. `BODY_FIXED` deferred.
Degenerate case threshold (1e-3 radians) in `KepplrConstants`.

### 7. Time Control
Anchor-based ET advancement in `simpleUpdate()`. Pause/resume, time rate,
`setET()`, `setUTC()`. Simulation starts at current wall clock time at 1x real
time. Negative time rates supported. `deltaSimSecondsProperty()` exposed via
`SimulationState` for downstream consumers.

### 8. JavaFX Control Window — Status Display
Programmatic JavaFX control window displaying all §10.2 fields.
`SimulationStateFxBridge` is the sole location of `Platform.runLater()` calls
(plus the sanctioned use in `KepplrApp.destroy()`). JME HUD overlay displaying
current UTC time, updated on the JME render thread for frame-accurate
consistency with `SimulationState`.

### 9. JavaFX Time Controls
Menu bar scaffolded with File, Time, Camera, Window menus. Time menu:
Pause/Resume toggle, Set Time dialog (UTC + Julian Date bidirectional sync), Set
Time Rate dialog. Ikonli added for menu icons. Parse errors reported in the
status window.

### 10. Basic Camera Navigation
Mouse and keyboard camera controls consistent with Celestia. Left drag rotates
in place; right drag orbits around focus body; scroll/PgUp/PgDn zooms. Arrow
keys tilt/roll; Shift+arrows orbit. Orbit is camera-relative (screen-right and
screen-up axes). Exponential zoom clamped at 1.1x body radius (minimum) and 1e15
km (maximum). All thresholds in `KepplrConstants`.

### 11. Multi-Body Rendering, Multi-Frustum, and Culling Rules
All bodies from `getBodies()` and `getSpacecraft()` rendered or culled each
frame. Three-layer frustum assignment (Near/Mid/Far) with 10% overlap per §8.
Sun as emissive light source. Spacecraft as point sprites. Bodies without
orientation data rendered as untextured spheres per §12.3. Ellipsoids used
throughout. Culling rules: apparent radius ≥ 2 px renders as full geometry;
apparent radius < 2 px renders as point sprite for all bodies including
satellites. Sprite cluster suppression: when two sprites fall within 2 px of
each other on screen, only the body with the larger physical radius renders; the
other is culled. Bodies currently held in selected, focused, or targeted state
are exempt from cluster suppression. The 2 px geometry threshold and the 2 px
cluster proximity threshold are separate named constants in `KepplrConstants`.

### 12. Orbital Trails
Trail rendering per §7.5. Trails sampled from SPICE at 180 samples per orbital
period (approx. every 2 degrees), defaulting to 30 days if orbital period cannot
be determined. Trails update dynamically with simulation time. Trail segments
assignable to different frustum layers per §8.3. Trails drawable in heliocentric
J2000 by default, optionally in other frames. Trail duration specifiable as an
argument. A distinct rendering subsystem from body rendering with its own update
cadence and memory management.

### 13. Vector Overlays
Instantaneous vector rendering using a strategy-interface design. `VectorType`
is an interface with `computeDirection(int originNaifId, double et)`.
`VectorTypes` provides static factory methods: `velocity()`, `bodyAxisX/Y/Z()`,
`towardBody(int targetNaifId)`. `towardBody` covers Sun direction, Earth
direction, and direction toward any arbitrary body by NAIF ID. Adding a new
vector type is a data change, not a code change — no switch/case over vector
type anywhere in rendering logic. Arrow length is a multiple of the focused
body's mean radius (`VECTOR_ARROW_FOCUS_BODY_RADIUS_MULTIPLE` in
`KepplrConstants`), recomputed each frame when the focused body changes. Note:
UI toggles for vector overlays are added in step 19b.

### 14. Star Field
Star field renderer per §7.4 using the Yale Bright Star Catalog (`ybsc5.gz`)
with proper motions applied at current simulation ET. Star catalog abstracted
behind `StarCatalog`/`Star` interfaces; Gaia catalog stubbed but deferred
pending user-downloaded files. Rendered in a separate frustum pass at
effectively infinite distance. Magnitude cutoff and point sprite size constants
in `KepplrConstants`.

### 15. Body-Fixed Camera Frame
`BODY_FIXED` implemented in `CameraFrame`. PCK rotation matrix from
`KEPPLREphemeris` applied to camera orientation each frame for the focused body.
Camera pose remains expressible in heliocentric J2000 at all times. Fallback to
`INERTIAL` when focused body has no orientation data; fallback state exposed via
`SimulationState`. Camera menu added to JavaFX control window with
`Inertial`/`Body-Fixed`/`Synodic` radio items bound to
`activeCameraFrameProperty()`. Synodic frame also fully applied to visualization
in this step.

### 16. Rings, Shadows, and Eclipses

#### 16a. Saturn's Rings
Ring geometry for Saturn (NAIF ID 699 only) rendered as a flat annular disk with
albedo and alpha texture encoding the Cassini Division and other gaps. Ring
brightness and transparency profiles stored as inline data. Ring plane oriented
by Saturn's PCK north pole axis rotated into J2000 each frame. Ring inner and
outer radii in `KepplrConstants`. Ring geometry established as shadow caster,
shadow receiver, and Sun halo occluder from the start.

#### 16b. Shadows and Eclipses
Analytic physical eclipse geometry per §9.3 hybrid Option C. Sun treated as
extended source using radius from `KEPPLREphemeris.getShape(sunNaifId)` at point
of use. All bodies except the Sun are shadow receivers and shadow casters.
Saturn's rings are a first-class simultaneous caster and receiver.
Caster/receiver loop is fully general — all bodies evaluated against all
receivers including rings; no body hardcoded as sole caster or receiver.
Penumbra fraction is continuous in [0,1]. Night side renders dimmer than day
side with smooth terminator transition. Near-frustum shape-model refinement
stubbed with marked comment. `RenderQuality` enum (`LOW`/`MEDIUM`/`HIGH`)
controls shadow model (point-source vs. extended-source analytic), max occluders
per receiver, trail density, and star magnitude cutoff. Sun halo quality stub
present for step 17.

### 17. Sun Halo
Sun halo implemented as a world-space billboard with custom GLSL 150 shader
(`SunHalo.vert/frag`). Halo size scales with Sun's apparent angular diameter
recomputed each frame. Brightness and falloff parameterized as named constants
tuned visually. Halo always renders regardless of Sun body cull state;
suppressed only when Sun is outside view frustum. Bodies and Saturn's rings
occlude the halo correctly per §8.4. Halo node assigned to FAR frustum layer
with a marked comment noting layer reassignment if Sun ever enters MID or NEAR
range. `KepplrConstants.SUN_NAIF_ID = 10` promoted from `BodyNodeFactory`.
`RenderQuality` sun halo quality stub replaced with real per-tier values.

### 18. Camera Transitions
`pointAt(int naifId, double durationSeconds)` and `goTo(int naifId, double
apparentRadiusDeg, double durationSeconds)` added to `SimulationCommands`.
Transitions execute on the JME render thread, progressing each frame in
`simpleUpdate()`. Transitions are non-blocking — commands return immediately and
progress is tracked in `SimulationState`. `pointAt` slews the camera orientation
from current look direction to the target body direction over `durationSeconds`
using spherical linear interpolation (slerp), and also updates selected/targeted
state to match the explicit target body. `goTo` waits for any in-progress
`pointAt` to complete, then translates the camera along its current line of
sight until the target body subtends the requested apparent radius. `goTo` also
updates selected/focused/targeted state to match the approached body and
prepends a default `pointAt` so the approach path is centered on the target.
Both transitions use linear interpolation initially; acceleration/deceleration
is a deferred refinement. `goTo` does not apply light-time correction to the
translation path. `SimulationState` exposes `transitionActiveProperty()`
(boolean) and `transitionProgressProperty()` (double in [0,1]) so the UI and
scripting layer can observe completion. If a new `pointAt` or `goTo` is issued
while a transition is in progress, the in-progress transition is cancelled and
the new one begins immediately. `waitTransition()` implemented as a blocking
primitive that returns when `transitionActiveProperty()` becomes false — defined
here alongside the property it depends on and exposed through the Groovy
scripting wrapper in step 20. `centerBody()`, `targetBody()`, and `selectBody()`
remain available as direct state setters when scripts or UI actions want to
update interaction state without moving the camera. All duration and
interpolation constants in `KepplrConstants`.

### 19. UI — Body Selection and Camera Controls

**Architecture decision (final):** Two-window layout. The JME render window and
the JavaFX control window are separate OS windows. The transparent overlay
(Option B) was prototyped and rejected — position-sync lag on macOS is
unacceptable and the platform complexity is not justified for v0.1. Do not
revisit this decision.

The JavaFX stage is a normal, non-transparent, non-always-on-top window. It
opens alongside the JME window at launch, positioned to its right. Both windows
close regardless of which one the user closes — closing either window calls the
same shutdown path as `destroy()`. No `WindowManager` class. No GLFW
position/minimize/focus callbacks. No Linux X11 forcing workaround. No
`kepplr.sh` launcher changes beyond what already exists.

**Layout reference:** Read
`../KEPPLR-pre/src/main/java/kepplr/ui/fx/FxUiController.java` before
implementing.

**Control panel layout — collapsible sidebar anchored to the right edge of the
JavaFX window:**

(1) Current body readout — three labeled rows: Selected, Focused, Targeted. Each
row shows the body name resolved via `SimulationStateFxBridge.formatBodyName(int
id)`. Each label must be bound directly to the corresponding
`SimulationStateFxBridge` observable property — not set once at init. The
Selected row has Focus, Target, and Clear action buttons. If no body is active
for a row, the label shows "—". There is no Tracked row — the active camera
frame indicator in the View menu serves this purpose.

(2) Time display and rate control from step 9.

(3) Camera frame indicator and transition progress bar — visible only during an
active transition (bound to `transitionActiveProperty()`), collapses to zero
height when idle.

**Body list panel — separate collapsible panel:**

Populated from `KEPPLREphemeris.getKnownBodies()`, grouped in a tree by primary
body, ordered by distance from the Sun. Top-level entries are bodies with Solar
System Barycenter as primary. Each planet barycenter expands to its satellites.
Body names resolved via `SpiceBundle.getObjectName()`. List refreshes on
`KEPPLRConfiguration.reload()`.

Single click → `SimulationCommands.selectBody(naifId)`. The Selected row in the
control panel must update immediately. Double-click →
`SimulationCommands.centerBody(naifId)` followed by
`SimulationCommands.goTo(naifId, ...)`.

**Menus:**
- File — Load Configuration (`KEPPLRConfiguration.reload(Path)`); parse errors
  reported in status panel. File picker defaults to properties files filter but
  also offers an "All Files" filter option.
- View — Camera Frame submenu (Inertial / Body-Fixed / Synodic radio items bound
  to `activeCameraFrameProperty()`), Field of View control, camera frame
  fallback indicator. Selecting Inertial in the Camera Frame submenu is the way
  to exit the Synodic frame via the menu.
- Time — unchanged from step 9.
- Window — preset sizes: 1280×720, 1280×1024, 1920×1080, 2560×1440; resizes the
  JME window only. JavaFX window is not resized programmatically.

**Keyboard shortcuts** — wired through the JME input handler, not JavaFX. All
call `SimulationCommands`:
- Arrow keys — tilt / roll
- Shift + Arrow keys — orbit
- Page Up / Page Down — zoom
- Space — pause/resume
- `[` / `]` — decrease / increase time rate

Escape has no keyboard binding. JavaFX default Escape-closes-stage behavior must
be suppressed on the control window. Escape must not close either window under
any circumstance.

**Tracking is not a separate camera behavior.** F is the keyboard shortcut for
toggling between the Synodic and Inertial camera frames. The Camera Frame
submenu is the menu equivalent. `trackedBodyId`, `trackingAnchor`,
`trackBody()`, and `stopTracking()` do not exist in the codebase. Any legacy
call sites are replaced with `setCameraFrame()` calls.

**Mouse picking in the JME window:**

Single click on a body → `SimulationCommands.selectBody(naifId)`. Double-click
on a body → `SimulationCommands.centerBody(naifId)` then
`SimulationCommands.goTo(naifId, ...)`. No mouse-based targeting — Point At is
available via explicit UI actions rather than click-picking.

Picking is entirely screen-space — no 3D ray cast:

1. Project every visible body to screen space and compute its actual
   screen-space radius from its projected size.
2. For each body compute effective pick radius = `max(actual_screen_radius,
   PICK_MIN_SCREEN_RADIUS_PX)`.
3. Find all bodies where the distance from the click point to the body's screen
   center ≤ effective pick radius.
4. If one or more candidates exist, return the one with the largest actual
   screen radius.
5. If no candidates exist, do nothing — do not clear the current selection.

`PICK_MIN_SCREEN_RADIUS_PX` is defined in `KepplrConstants` and must be
referenced in the pick logic. Double-click detection uses a timing threshold
constant also in `KepplrConstants`.

**`SimulationStateFxBridge` extensions** (add without removing anything already
present):
- `selectedBodyActiveProperty()` (boolean)
- `cameraFrameFallbackActiveProperty()` (boolean)
- `formatBodyName(int id)` — separate from existing `formatBodyId`
- `transitionActiveProperty()` (boolean)
- `transitionProgressProperty()` (double, [0,1])

**Input fields:** accept a body name or a NAIF ID in a single field —
distinguished by whether the input parses as an integer. Name resolution via
`BodyLookupService` in `kepplr.ephemeris`. No name resolution logic anywhere in
`ui/`.

**Styling constraints:**
- Panel backgrounds: semi-opaque dark fill, minimum rgba(0,0,0,0.72).
- All text: white or near-white (#e0e0e0 minimum). No grey-on-grey or
  grey-on-black combinations anywhere.
- Menu bar and menu item text: explicitly styled white via JavaFX CSS.
- Do not rely on default JavaFX theme colors for any text that appears over a
  dark background.

**Hard constraints — violations block sign-off:**
- `Platform.runLater()` permitted only in `SimulationStateFxBridge` and in
  `KepplrApp.destroy()` for lifecycle shutdown. The `destroy()` call site must
  have a comment explaining the sanctioned use. Nowhere else.
- No name resolution logic inside `ui/`.
- The JME window must receive all mouse and keyboard events intended for it. The
  JavaFX window must not intercept input directed at the JME window.
- `trackBody()`, `stopTracking()`, `trackedBodyId`, and `trackingAnchor` do not
  exist anywhere in the codebase after this step.
- `mvn test` passes with no new failures.

### 19b. Overlays — Labels, HUD, Trails, and Vector Toggles

This step surfaces existing rendering capabilities (labels, trails from step 12,
vectors from step 13) through `SimulationCommands` and the JavaFX UI. All
toggles must be scriptable — they are part of the `SimulationCommands` API. No
keyboard shortcuts for overlays.

**Labels:**

`SimulationCommands.setLabelVisible(int naifId, boolean visible)` — per-body
label visibility. Labels display the body name resolved via
`SpiceBundle.getObjectName()`. Labels render as JME screen-space text attached
to each body node.

Decluttering policy: labels are suppressed by proximity. A label is drawn only
if no other label with a larger-radius body is within
`LABEL_DECLUTTER_MIN_SEPARATION_PX` of its screen position. This naturally
produces the zoom-dependent behavior: at large distances, major planets are
labeled and satellites are suppressed because they cluster near their primary;
as the camera moves closer and satellites separate on screen, their labels
appear. `LABEL_DECLUTTER_MIN_SEPARATION_PX` is defined in `KepplrConstants`.

GUI: Overlays menu → Labels toggle (global, calls `setLabelVisible` on all known
bodies).

**HUD:**

Two independent HUD elements rendered by JME on the render thread:
- Time display — current simulation UTC, upper-right corner.
- Info display — focused body name and distance from camera, upper-left corner.

`SimulationCommands.setHudTimeVisible(boolean)` and
`SimulationCommands.setHudInfoVisible(boolean)` — independent toggles, both on
by default.

GUI: Overlays menu → HUD/Info toggle and Show Time toggle, each independently
bound to the corresponding command.

**Orbit trails:**

`SimulationCommands.setTrailVisible(int naifId, boolean visible)` — per-body
trail visibility. `SimulationCommands.setTrailDuration(int naifId, double
seconds)` — per-body trail duration in simulation seconds. Trails fade at their
trailing end. Trail duration defaults to one orbital period (or 30 days if
period cannot be determined), matching step 12 behavior.

Decluttering policy mirrors labels: trails for satellites are suppressed when
the satellite's screen position is within `TRAIL_DECLUTTER_MIN_SEPARATION_PX` of
its primary, expanding as the camera zooms in. Constant defined in
`KepplrConstants`.

GUI: Overlays menu → Show Trajectories toggle (global, calls `setTrailVisible`
on all known bodies). Duration is not exposed in the GUI — the default per-body
duration is used.

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

All submenu items operate on the currently focused body. The API supports any
body and any `VectorType`; the GUI exposes the common cases only.

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
`setCameraPosition`, `setCameraOrientation`, `setCameraPose`, `setSynodicFrame`)
added to `SimulationCommands`. `CameraInputHandler` refactored to delegate to
these commands with `durationSeconds = 0` for all mouse and keyboard navigation.
`DEFAULT_CAMERA_TRANSITION_DURATION_SECONDS`, `FOV_MIN_DEG`, and `FOV_MAX_DEG`
added to `KepplrConstants`. Synodic frame override IDs (`synodicFrameFocusId`,
`synodicFrameTargetId`) added to `DefaultSimulationState` for
`setSynodicFrame()` without disturbing interaction state. All new methods fully
Javadoc'd with usage examples per the hard constraint in the step entry.

`setCameraPose(...)` is the compound camera command for scripts that need
position and orientation to change as one operation. It has focus-relative and
explicit-origin NAIF overloads. For `durationSeconds > 0`,
`TransitionController` runs a single `CAMERA_POSE` transition that lerps
position and slerps orientation with the same eased interpolation parameter. For
`durationSeconds <= 0`, the pose snaps on the next render frame. This avoids the
cancellation behavior that occurs when separate animated
`setCameraPosition(...)` and `setCameraOrientation(...)` calls are issued
back-to-back. (See D-072.)

### 20. Groovy Scripting Layer
Groovy scripting API implemented via three new classes in `kepplr.scripting`:

- `KepplrScript` — the `kepplr` binding object exposed to scripts. Delegates all
  `SimulationCommands` methods plus String-name overloads for every method that
  takes a NAIF ID. Name resolution via `BodyLookupService` in
  `kepplr.ephemeris`; unresolvable names log the error and throw
  `IllegalArgumentException`, stopping the script. No camera math or simulation
  logic in this layer.

- `ScriptRunner` — loads and executes `.groovy` files via JSR 223 on a dedicated
  daemon thread (`kepplr-groovy-script`), separate from the JME render thread
  and JavaFX thread. If `Run Script` is invoked while a script is already
  running, a confirmation dialog is shown; if confirmed, the current thread is
  interrupted and `cancelTransition()` is called before the new script starts.

- `CommandRecorder` — decorator on `SimulationCommands` that intercepts every
  method call and records method name, arguments, and wall timestamp. On stop,
  serializes the log as a runnable Groovy script with `waitWall()` calls
  inserted between commands. Instant camera commands (`durationSeconds == 0`)
  are coalesced within a 250ms window rather than recorded verbatim: deltas such
  as orbit/yaw/truck are accumulated, multiplicative zoom is multiplied, and
  absolute camera state commands such as `setFov`, `setCameraPosition`,
  `setCameraOrientation`, and `setCameraPose` use last-value-wins semantics (see
  D-024 and D-072). Commands with `durationSeconds > 0` are never coalesced.

Timing primitives on `KepplrScript`: `waitRenderFrames(int frameCount)`,
`waitWall(double seconds)`, `waitSim(double seconds)`, `waitUntilSim(double
etSeconds)`, `waitUntilSim(String utc)`, `waitTransition()`. No generic `wait()`
per §11.2. `waitRenderFrames()` is a blocking render-thread fence for queued
scene work such as window resize, HUD message display, overlay updates, and
other changes that must be visible in the framebuffer before a screenshot or
`captureSequence()` begins. `waitTransition()` now uses that frame fence to
ensure a just-queued camera transition has actually been consumed by the JME
thread before it starts waiting for completion. `waitSim` and `waitUntilSim`
poll at `SCRIPT_WAIT_POLL_INTERVAL_MS` intervals; both block indefinitely if the
simulation is paused or the time rate works against the target — documented in
Javadoc.

`cancelTransition()` added to `SimulationCommands` and implemented through
`TransitionController` to support clean script interruption.

`File → Run Script` and `File → Start/Stop Recording` (CheckMenuItem) added to
`KepplrStatusWindow`. Recording start wraps the active `SimulationCommands` in a
`CommandRecorder`; stop unwraps it and opens a file-save dialog.

`VectorType.toScript()` added to the strategy interface so `CommandRecorder` can
serialize `setVectorVisible` calls correctly (see D-026).

`SCRIPT_WAIT_POLL_INTERVAL_MS` and `RECORDER_COALESCE_WINDOW_MS` added to
`KepplrConstants`.

---

### 21. GLB Shape Model Rendering
    This step replaces ellipsoid and point sprite geometry with GLB shape models
    for bodies and spacecraft that have them configured, while leaving all
    existing rendering paths intact for bodies without models. What this step
    delivers:

- GLTFUtils ported from the prototype — reads the modelToBodyFixedQuat
  quaternion from GLB JSON extras with no third-party JSON library
- resourcesFolder() registered as a JME FileLocator at startup so shape model
  paths from BodyBlock and SpacecraftBlock resolve correctly
- BodyNodeFactory updated to load a GLB and attach it as glbModelRoot under
  bodyFixedNode when BodyBlock.shapeModel() is non-null; KEPPLR's body material
  pipeline (equirectangular mapping, texture alignment, center-longitude)
  applies as before
- Spacecraft GLBs loaded similarly under their scene node; per-geometry base
  color/texture is preserved where possible, but the active renderer may use
  KEPPLR EclipseLighting for correct Sun/body-shadow response rather than full
  GLB/PBR fidelity; uniform scale of 0.001 × SpacecraftBlock.scale() converts
  meters to km
- Graceful fallback to ellipsoid (bodies) or point sprite (spacecraft) on null
  path, missing file, or load failure — WARN log, no crash

** Frame semantics **: modelToBodyFixedQuat is applied once at load time as
glbModelRoot's local rotation, composing with bodyFixedNode's time-varying SPICE
frame rotation. It is never updated per-frame. **Out of scope for this step:**
LOD; shadow refinement using shape model geometry (§9.3).

**Post-completion refinements (same branch):**
- Spacecraft FK frame registered in `stateTransformMap`; `hasBodyFixedFrame()` /
  `getJ2000ToBodyFixed()` unified to cover both PCK and FK frames;
  `BodySceneManager` calls `updateRotation()` for spacecraft each frame (D-029)
- Camera min-zoom and `goTo` arrival distance scale with
  `SpacecraftBlock.scale() × 0.001` rather than a fixed 1 km fallback (D-030)
- `BodySceneManager.dispose()` + `KepplrApp.rebuildBodyScene()` +
  `KepplrStatusWindow.configReloadCallback` enable shape model hot-reload when a
  new config file is loaded (D-031)
- `VectorRenderer` and `TransitionController` fall back to
  `BODY_DEFAULT_RADIUS_KM` for shape-less bodies instead of skipping them
  entirely

---

### 22. Instrument Frustum Overlays

Translucent frustum pyramid overlays for instrument fields of view, loaded from
IK kernels via `KEPPLREphemeris.getInstruments()`.

- `InstrumentFrustumManager` builds one pyramid mesh per instrument at load
  time, recomputes vertex positions in-place each frame via `FloatBuffer`
  mutation, and assigns the geometry to the correct frustum layer based on the
  spacecraft apex distance — matching the `VectorRenderer` pattern (apex
  distance, not apex + extent).
- CIRCLE FOVs (1 bound vector) and ELLIPSE FOVs (2 bound vectors) are
  approximated as `INSTRUMENT_FRUSTUM_CIRCLE_APPROX_SIDES = 32`-sided polygons:
  CIRCLE via Rodrigues' rotation of the bound vector around the boresight axis;
  ELLIPSE via `cos(t)·a + sin(t)·b` parameterization. RECTANGLE and POLYGON FOVs
  use SPICE bound vectors directly. Effective bounds are computed once at load
  time and stored in `FrustumEntry.effectiveBounds` (see D-033).
- `SimulationCommands.setFrustumVisible(int naifCode, boolean)` and
  `setFrustumVisible(String name, boolean)` added. Visibility state held in
  `DefaultSimulationState` via a per-instrument `ConcurrentHashMap`.
  `KepplrApp.syncFrustums()` propagates state → manager each frame (idempotent
  on repeated same-value calls).
- `InstrumentFrustumManager.reload()` called from `rebuildBodyScene()` — entries
  are rebuilt when a new configuration is loaded.
- `rebuildBodyScene()` expanded to a full render manager restart:
  `TrailManager`, `SunHaloRenderer`, and `LabelManager` each gained `dispose()`
  methods; all managers are torn down and reconstructed on every config reload
  (see D-032).
- Post-v0.1 footprint work now shortens frustum rays against the first
  intersected target ellipsoid, draws live surface footprints from the same
  intersection result, and can retain closed live footprints as continuous
  body-fixed surface swaths.
- `SimulationCommands`/`KepplrScript` expose frustum visibility, footprint
  persistence enable/disable, footprint clearing, and per-instrument frustum
  color control. Color can be specified as 8-bit RGB or `RRGGBB`/`#RRGGBB`;
  command recording serializes these methods as executable Groovy.
- Retained swaths are stored as body-fixed vector polygons per instrument/body
  pair, bridged between adjacent samples within the same recording segment, and
  rendered with per-vertex colors so previously retained geometry keeps its
  capture-time color when the live frustum color changes later.

**Still out of scope:** boresight line rendering; mesh-model surface
intersection for GLB-backed bodies; GUI color controls; distance culling of
frustums.

---

## v0.1 Complete

Steps 1–22 constitute the v0.1 release. All items below are post-v0.1 work.

---

## Footprint Capability Roadmap and Current Status

### Phase 1
Ellipsoid-clipped live frustums and live surface footprints.

Status: complete.

**Requirements**
- Instrument frustum rays stop at the first intersected body ellipsoid instead
  of always using the fixed default extent.
- A live surface footprint is drawn only when the frustum is visible and the
  frustum actually intersects a body surface.
- The live footprint is derived from the same intersection result used to clip
  the frustum.
- If there is no visible frustum or no valid surface hit, no footprint is drawn.
- The implementation works in body-fixed coordinates for the target body.

**Success criteria**
- A visible frustum aimed at a body visibly terminates at the surface.
- The corresponding footprint appears on the body surface in the expected
  location.
- A frustum that misses the body remains unclipped and produces no footprint.
- Existing frustum visibility controls still behave correctly.

### Phase 2
Persist only what was actually drawn while persistence is enabled.

Status: complete.

**Requirements**
- Add a per-instrument persistence-enabled state distinct from frustum
  visibility.
- While persistence is enabled, a live footprint is retained only when the
  frustum is visible and a live footprint is successfully drawn.
- While persistence is disabled, live footprints are never retained.
- Persisted footprints remain on the surface as static overlays while time
  advances and the live frustum moves away.
- Add a way to clear persisted footprints.
- Recording should accumulate into a continuous surface swath instead of a comb
  of separate frame-by-frame outlines.

**Success criteria**
- With persistence enabled, a visible intersecting frustum creates retained
  footprints over time.
- With persistence disabled, no retained footprints are created.
- A hidden frustum creates no retained footprints even if persistence remains
  enabled.
- A visible frustum that is not intersecting creates no retained footprints.
- Persisted footprints stay fixed on the target body surface across later
  frames.
- The retained result appears as a continuous filled swath rather than isolated
  per-frame segments.

### Phase 3
Continuous retained swaths and future-proof color model.

Status: complete. Continuous retained swaths are implemented. Render-path color
support exists for live footprints and retained swaths.

**Requirements**
- Persisted geometry for a given instrument/body pair is accumulated as a
  continuous body-fixed swath.
- The swath is rendered from retained vector geometry rather than a coarse
  global tile mask.
- Retained geometry preserves close-up edge fidelity without requiring a
  globally fine raster.
- Live frustum state is separated from persisted footprint state.
- Persistence-enabled state is treated as a recording mode, not as a one-shot
  capture command.
- The design remains compatible with future per-instrument live color and
  capture-time retained color.

**Success criteria**
- A recorded pass over the body appears as one continuous filled swath.
- Close-up views do not become obviously tile-limited.
- Changing an instrument's future live color does not require recoloring
  previously retained geometry.

**Current implementation notes**
- Retained swaths are stored as body-fixed vector polygons per instrument/body
  pair.
- Adjacent retained footprints are bridged into filled surface strips when they
  are part of the same recording segment.
- Retained swath rendering uses vertex colors, with each retained polygon
  copying the instrument's persistent footprint color at capture time.
- Live footprint color and persistent footprint color are separate internal
  render-manager state.
- Frustum color support flows through simulation state, scripting commands,
  command recording, and the render loop.
- Color synchronization is idempotent; repeatedly syncing the same color must
  not reset `persistenceSegmentActive` or split a continuous swath into
  per-frame segments.
- A retained swath color change starts a new persistent segment. This prevents
  the bridge strip between the last old-color polygon and the first new-color
  polygon from repainting the tail of the older retained swath.

### Phase 4
Script/API completion and tests.

Status: partially complete. Persistence enable/disable, footprint clearing, and
frustum color control are exposed through scripting and recording paths. Focused
unit coverage exists for command delegation, recorder serialization, color
parsing, pending clear queues, frustum intersection helpers, and the
color-change segment boundary rule for retained swaths.

**Requirements**
- Expose scripting/command methods for live frustum clipping display,
  persistence-enabled state, and footprint clearing.
- Record these commands through the command recorder.
- Add unit tests for command delegation, recorder serialization, state behavior,
  and intersection math.
- Add render-path tests where practical for layering and retained-swath rules.

**Success criteria**
- Scripts can enable persistence, accumulate retained footprints over a time
  interval, disable persistence, and clear footprints without using UI-only
  paths.
- Recorded scripts faithfully reproduce the footprint actions.
- Tests cover the "retain only when actually drawn and persistence is enabled"
  rule.
- Tests cover retained-swath continuity and clearing behavior.

**Deferred test scope**
- Full render-path/layering tests for retained swaths remain deferred until the
  render test harness can construct JME material-backed frustum entries without
  relying on the live application context. Current tests cover the non-rendering
  command, state, parser, and intersection surfaces; the color-sync idempotency
  requirement is documented in Phase 3 implementation notes.

### Phase 5
Mesh-model support.

Status: not started.

**Requirements**
- Add a body-fixed ray-to-mesh intersection path for GLB-backed bodies.
- Prefer mesh intersection when a usable mesh surface exists, otherwise fall
  back to ellipsoid intersection.
- Keep footprint geometry and persistence semantics unchanged from earlier
  phases.
- Define which mesh geometry counts as the surface and handle performance with
  an acceleration structure if needed.

**Success criteria**
- A GLB-backed body can receive clipped frustums and footprints based on its
  mesh rather than only its ellipsoid.
- Results are stable in body-fixed coordinates.
- Performance remains acceptable for per-frame updates.
- Ellipsoid fallback still works for bodies without mesh intersection support.

### Phase 6
User-configurable colors.

Status: partially complete. Script/API support for per-instrument frustum colors
is in place; UI controls are not.

**Requirements**
- Add per-instrument live frustum color control.
- Each retained swath segment copies the current live frustum color at the
  moment it is recorded.
- Previously retained swath geometry keeps its captured color even if the
  instrument color changes later.

**Success criteria**
- The user can record multiple swaths from the same instrument in different
  colors.
- Older retained geometry keeps its original color.
- Live frustum color and retained swath color behavior are consistent and
  predictable.

---

## Known Limitations

**macOS: JME window requires a click to receive keyboard focus.** Moving the
mouse from the JavaFX control window into the JME render window does not
automatically transfer keyboard focus on macOS. The user must click the JME
window before keyboard shortcuts will respond. This is a macOS window management
constraint. Behavior on Linux (X11) is untested and may differ.

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
- Spacecraft GLBs may use per-geometry EclipseLighting so their illumination
  remains consistent with the analytic Sun/body-shadow model (D-074).

---

### 24. Cinematic Camera Commands and Transition Easing *(complete)*

Two related improvements to the camera transition system:

**Cinematic commands.** `truck(double km, double durationSeconds)`,
`crane(double km, double durationSeconds)`, and `dolly(double km, double
durationSeconds)` added to `SimulationCommands`. These translate the camera
along its screen-right, screen-up, and look direction axes respectively,
preserving orientation. All three are non-blocking, use `TransitionController`,
and are loggable by `CommandRecorder`. Unlike `goTo` (which targets a body),
these are pure spatial translations with no body reference — useful for framing
shots in scripts. Coalescing in `CommandRecorder` follows the same hybrid
strategy as `orbit`/`zoom` (D-024). `CameraInputHandler` does not delegate to
these — they are script-only primitives unless a future UI binding is added. See
D-023 for the command structure pattern established in step 19c.

**Transition easing.** Replace linear interpolation with
acceleration/deceleration curves for `pointAt`, `goTo`, and the new cinematic
commands. A smoothstep or cubic ease-in-out applied to the interpolation
parameter `t` before it reaches the slerp/lerp call is the minimal change.
Easing constants in `KepplrConstants`. The scripting API and `CommandRecorder`
are unaffected — easing is internal to `TransitionController`.

**Also delivered:** Trail period fix for comparable-mass binary systems (D-041);
barycenter label filtering (D-042).

---

### 25. Screenshot Capture and Animation Sequences ✅

**(1) Single screenshot.** `SimulationCommands.saveScreenshot(String)` with a
`ScreenshotCallback` functional interface on `DefaultSimulationCommands`. The
callback uses a `CountDownLatch` pattern (same as `loadConfiguration`) to block
the calling thread until the JME thread completes the capture. `KepplrApp`
captures via `renderManager.getRenderer().readFrameBuffer(null, buf)`,
converting to `BufferedImage` with Y-flip and writing PNG via `ImageIO`.
Loggable by `CommandRecorder`. Exposed as `KepplrScript.saveScreenshot(String)`
and `File → Save Screenshot` menu item.

**(2) Capture sequences.** `CaptureService.captureSequence(outputDir, startET,
frameCount, etStep, commands, state)` in `kepplr.core` — a blocking loop that
pauses the simulation, advances ET per step, and captures each frame. An
overload adds `startFrameIndex` so multiple capture blocks can write a single
contiguous frame sequence into the same output directory. Frame filenames
auto-widen padding (4 digits up to 9999 frames, 5 for 10000+, etc.) based on the
highest emitted frame index. Writes `capture_info.json` sidecar with start ET,
step, frame count, starting frame index, resolution, and capture timestamp
(dimensions read from the first captured PNG). NOT on `SimulationCommands` —
called directly from `KepplrScript` and the GUI capture dialog.

`captureSequence(...)` intentionally has a fixed camera for the duration of one
blocking sequence; Groovy commands cannot be inserted inside its internal loop.
For camera-keyed animations, scripts should own the loop explicitly: set ET for
the frame, call `setCameraPose(..., 0.0)`, fence with `waitRenderFrames(2)`, and
then call `saveScreenshot(...)`. This is the supported path for deterministic
frame sequences with scripted camera motion. `doc/scripting_examples.rst`
includes a camera-keyed capture example.

**(3) GUI integration.** `File → Capture Sequence…` opens a dialog (Start UTC,
ET step, frame count) with a `DirectoryChooser`, runs on a dedicated daemon
thread. Mutual exclusion with the script runner (each checks the other's active
state).

**(4) PngToMovie update.** Supports flat directory layout (frame_*.png directly
in seqDir) with auto-detection; legacy manifest.json layout preserved. Optional
`capture_info.json` printed for informational display.

**(5) Post-render capture (D-043).** Screenshot capture runs after the render
pass (via `KepplrApp.update()` override) to ensure the framebuffer reflects the
current frame's scene graph with focus-body tracking applied, not the previous
frame's stale content.

**Also delivered:** Log window (`File → Show Log`) with ANSI color rendering
from the `%highlight` log4j2 pattern (D-044); Save Log button for plain-text
export. `setAllLabelsVisible(boolean)` and `setAllTrailsVisible(boolean)` on
`KepplrScript`. Synodic frame javadoc corrected from "targeted" to "selected."

---

### 26. State Snapshot Strings ✅

A compact serialized encoding of the current simulation state as a single
copy-pasteable string.

**Format:** Base64url-encoded (no padding), versioned with a leading version
byte so future field additions don't break older strings. Packed binary layout
chosen for compactness (~100-character strings). See D-049.

**Minimal field set:** ET, time rate, paused flag, camera position and
orientation in heliocentric J2000 (the canonical frame per §1.4), camera frame
enum, focused/targeted/selected NAIF IDs, FOV. Overlay visibility (labels,
trails, vectors, frustums) deferred to a future "full snapshot" extension.

**Integration points:**
- `SimulationCommands.getStateString()` / `setStateString(String)` — scripts can
  capture and restore snapshots. `setStateString` jumps instantly (no transition
  animation) for predictable script behavior; a future `setStateStringAnimated`
  could be added if smooth restoration is wanted.
- GUI: `File → Copy State` copies to clipboard; `File → Paste State` reads from
  clipboard and applies. Both are loggable by `CommandRecorder`.
- CLI: `-state <string>` restores state on startup (applied at the end of
  `simpleInitApp()` before any script runs). `-script <path>` runs a Groovy
  script on startup (equivalent to File → Run Script). Both are optional. State
  is applied first so the script sees the restored state. See D-052.

**Bug fixes (branch 56-update-documentation):**

Three bugs in the state restore path were diagnosed and fixed:

1. **Paused flag restored incorrectly.** `setStateString()` called
   `clock.setPaused(snap.paused())`, which unpaused a running simulation when
   restoring a snapshot taken while paused (or vice versa). The paused flag is
   caller state, not snapshot state. **Fix:** removed the `clock.setPaused()`
   call from `setStateString()`.

2. **Sync latch race condition.** The original latch implementation stored a
   `CountDownLatch` separately in `KepplrApp.postRestoreLatch`. A JME frame
   could run and count the latch down before `setPendingCameraRestore()` was
   called, causing `setStateString()` to unblock without the restore having been
   applied. **Fix:** the latch is embedded directly in `PendingCameraRestore` so
   the JME thread only counts it down when it actually consumes the restore
   record. See D-051.

3. **Focus-tracking anchor causes spurious displacement on ET jump (D-063).**
   `CameraInputHandler.applyFocusTracking()` maintains a `prevFocusPos` anchor
   from the previous frame. When state restore jumps ET backward (e.g., `et1` →
   `et0`), the delta `earthPos(et0) − prevFocusPos(et1)` (≈108,000 km for a
   1-hour jump) was added to the freshly restored camera position before
   body-following ran. Body-following corrected the distance but not the
   direction, producing wrong body-fixed lat/lon for every restore except the
   most recent snapshot. **Fix:**
   `CameraInputHandler.resetFocusTrackingAnchor()` nulls `prevFocusPos`; called
   from `KepplrApp.simpleUpdate()` immediately after consuming a
   `PendingCameraRestore`.

**Test coverage:** `TransitionControllerBodyFollowingTest` (5 tests) covers
body-following after `requestFollow`, ET advance across an hour, state string
encoding of body-relative offset, and a full save/advance-1h/restore round-trip
asserting ET, J2000 position, orientation, FOV, and body-fixed spherical
coordinates (distance, latitude, longitude).

**Step 26 is complete.**

---

### 27. Script Configuration Reload and Menu Tooltips

Two small items bundled as a cleanup step.

**Script-initiated configuration reload.** Add `loadConfiguration(String path)`
to `SimulationCommands` (and a String overload on `KepplrScript`). Delegates to
the same `KEPPLRConfiguration.reload(Path)` + `rebuildBodyScene()` path already
used by `File → Load Configuration`. This allows a script to switch kernel sets
mid-execution — e.g., load a comet configuration, run a flyby sequence, then
reload the default. The command is loggable by `CommandRecorder`. Error handling
matches the interactive path: parse errors are reported via the status window
and the script continues with the previous configuration.

**Menu item help text.** Plain JavaFX menu actions use standard `MenuItem` for
reliable first-click activation and normal popup lifecycle behavior (D-073). Do
not wrap ordinary actions in `CustomMenuItem` solely to attach tooltips.
`CustomMenuItem` remains acceptable for entries that embed actual controls or
dynamic nodes, such as camera-frame radio buttons.

**Status window layout improvements.** Several usability changes to
`KepplrStatusWindow`:

- **Body readout:** Each row now shows "Name (NAIF_ID)" with camera-to-body
  distance right-aligned on the same line. Distance auto-switches units: metres
  (< 1 km), km (< 0.01 AU), AU (≥ 0.01 AU). Row order is Center → Targeted →
  Selected. The Selected row provides Center, Go To, and Point At buttons. (See
  D-036.)

- **Window:** Width increased from 380px to 440px. JavaFX `Separator` lines
  added between body readout, status section, and body list sections. The stage
  remains a normal non-always-on-top window per the two-window layout decision.

- **Live body filtering:** The search field filters the body tree as the user
  types (case-insensitive match on display name or NAIF ID). Parent groups are
  shown expanded when any child matches. Enter still resolves exact NAIF IDs and
  selects the body. Section header renamed to "Select Body".

- **Transition bar removed.** Camera transitions are fast enough that the
  progress bar was not useful.

- **KEPPLRConfiguration.reload() race fix.** `load(PropertiesConfiguration)` now
  builds into a local variable and assigns `instance` atomically at the end,
  eliminating the window where `getInstance()` would throw
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
  File > Record Session, body tree context menu) use `CheckMenuItem` instead of
  a mix of `CheckBox` + `CustomMenuItem` and `CheckMenuItem`. Removed
  `menuCheckBox` helper and `CheckBox` import.

- **Bidirectional overlay / context menu sync.** The "Current Focus" submenu
  items now bind to `SimulationState` visibility properties for the focused
  body. Changes from any source (context menu, scripts, etc.) update the
  overlays menu checkmarks automatically. Listeners rebind on focus change. (See
  D-038.)

- **Dynamic context menu.** Right-click on a body tree item opens a context menu
  with Focus, Target, and toggle items (Trail, Label, Axes) reflecting current
  state. (See D-039.)

- **Vector arrowheads.** Vectors render as arrows: line shaft + 8-segment cone
  arrowhead (12% of shaft length). Shaft line width increased to 2px.

- **Body-fixed axes scale to origin body.** `VectorType.usesOriginBodyRadius()`
  (default `false`, `true` for body axes) tells `VectorRenderer` to use the
  origin body's rendered radius (via
  `BodySceneManager.getEffectiveBodyRadiusKm`) instead of the focused body's.
  Spacecraft axes now scale proportionally to the GLB bounding radius including
  the configured `scale()` factor. (See D-040.)

**Step 27 is complete.**

---

### 28. Script Enhancements

Four improvements to the scripting layer and its GUI integration.

**(1) Interactive script console.** A text input field in the JavaFX control
window where the user can type and execute single-line Groovy expressions (e.g.,
`kepplr.centerBody("Earth")`). Expressions are evaluated via JSR 223 with the
same `kepplr` binding as `ScriptRunner`. Execution runs on a dedicated daemon
thread (not the FX thread). Output and errors are displayed in the existing
script output panel. The console must not interfere with a running script — if a
script is active, console input is rejected with a message in the output panel.

**(2) Configuration access from scripts.** `KepplrScript.getConfiguration()`
returns `KEPPLRConfiguration.getInstance()` directly, giving scripts full access
to the configuration and ephemeris (e.g., `config = kepplr.getConfiguration();
eph = config.getEphemeris()`). This is a sanctioned exception to Rule 3 for the
scripting layer only — Groovy scripts are ephemeral and do not persist
references as class fields. The `KepplrScript.getConfiguration()` method itself
calls `KEPPLRConfiguration.getInstance()` at point-of-use, consistent with Rule
3 at the Java level. No other Java class gains a stored reference.

**(2b) Live simulation state access from scripts.** `KepplrScript.getState()`
returns the live `SimulationState` instance, giving scripts read access to all
observable properties — current ET, time rate, paused status, focused body ID,
camera position, camera orientation, etc. (e.g., `et =
kepplr.getState().currentEtProperty().get()`). This complements
`getConfiguration()` by exposing runtime state that is not available through the
configuration singleton. (See D-053.)

**(3) Script display messages.** `KepplrScript.displayMessage(String text)` and
`KepplrScript.displayMessage(String text, double durationSeconds)` show a
message on the JME HUD overlay. Messages appear in the lower-center of the
screen with a default duration of 5 seconds and fade out. Only one message is
visible at a time — a new message replaces any existing one. A
`HudMessageDisplay` class in `kepplr.render` manages the lifecycle on the JME
thread. `SCRIPT_MESSAGE_DEFAULT_DURATION_SEC` and
`SCRIPT_MESSAGE_FADE_DURATION_SEC` added to `KepplrConstants`.

**(4) Rename `setCameraOrientation` → `setCameraOrientation`.** Rename across
all layers: `SimulationCommands`, `DefaultSimulationCommands`,
`TransitionController`, `CommandRecorder`, `KepplrScript`, and all tests. No
behavioral change — purely a naming improvement.

**Additional enhancements delivered in Step 28:**

- **Frame-aware `setCameraPosition` and `setCameraOrientation`.** The offset /
  look / up vectors are now interpreted in the active camera frame (INERTIAL,
  SYNODIC, or BODY_FIXED) and transformed to J2000 internally by
  `TransitionController.frameToJ2000()`. In SYNODIC the three `Basis` vectors
  form the columns of the synodic-to-J2000 rotation matrix; in BODY_FIXED the
  transpose multiply `rot.mtxv()` converts body-fixed to J2000. (See D-045.)
  Javadoc on all `KepplrScript.setCameraPosition()` and `setCameraOrientation()`
  overloads updated to document that vectors are expressed in the current camera
  frame. (See D-054.)

- **Combined `setCameraPose`.** Position and orientation can now be set in one
  command through `SimulationCommands`, `KepplrScript`, and `CommandRecorder`.
  This is the preferred primitive for exact per-frame camera poses and for
  animated moves where translation and pointing must complete together. (See
  D-072.)

- **`setWindowSize(int, int)`** added to `SimulationCommands` / `KepplrScript` /
  `CommandRecorder`. Implementation uses a `BiConsumer<Integer, Integer>`
  callback set by `KepplrApp` that calls GLFW `glfwSetWindowSize` via
  `enqueue()`.

- **HUD info shows selected body** instead of focused body. `KepplrHud.update()`
  now receives `selectedBodyId` from `KepplrApp.simpleUpdate()`.

- **Body show/hide toggle.** `SimulationCommands.setBodyVisible(int, boolean)` /
  `SimulationState.bodyVisibleProperty(int)` with per-body `ConcurrentHashMap`
  (default `true`). `BodySceneManager` skips hidden bodies before pass 1.
  Barycenters (NAIF 0–9) default to hidden. Context menu "Visible" CheckMenuItem
  in body tree. `KepplrScript` exposes both int and String overloads. (See
  D-046.)

**Hard constraints:**
- `Platform.runLater()` permitted only in `SimulationStateFxBridge` and
  `KepplrApp.destroy()`. Console output drains via the existing `AnimationTimer`
  pattern in the script output panel.
- `KEPPLRConfiguration` and `KEPPLREphemeris` must not be stored or passed as
  fields/parameters (Rule 3).
- All new `SimulationCommands` methods (if any) must be loggable by
  `CommandRecorder`.
- `mvn test` passes with no new failures.

**Step 28 is complete.**

---

### Trail Reference Body and Camera-Frame Trail Rendering (branches 57–58) ✅

Two related improvements to orbital trail rendering.

#### Branch 57 — Configurable trail reference body (D-065)

All orbital trails were previously drawn in coordinates relative to a reference
body determined by a NAIF ID heuristic: natural satellites used their system
barycenter; everything else used heliocentric (Sun). For spacecraft with
negative NAIF IDs the heuristic always produced heliocentric coordinates,
causing spacecraft approach trails to drift across the scene as the target
planet moved.

`setTrailReferenceBody(int naifId, int referenceBodyId)` added to
`SimulationCommands` and `KepplrScript`. The reference body is stored as a
per-body property in `DefaultSimulationState`. `TrailManager` reads the property
at resample time; a changed reference body triggers an immediate resample.
`VelocityVectorType` reads the same property so the velocity arrow always points
in the same direction as the trail is drawn (D-065).

**Key implementation detail:** `TrailSampler.sample()` contains its own internal
NAIF heuristic and cannot be overridden by the caller. A new package-private
entry point `TrailSampler.sampleWithExplicitRef()` was added that bypasses the
heuristic entirely. `TrailManager` computes the barycenter anchor *before*
calling the sampler so that both the samples and the live anchor are consistent.

**Follow-up:** `BodyBlock.primaryID()` is now part of the default trail reference
resolution (D-076). If a body has a nonblank configured primary, `TrailSampler`
and `TrailManager` use that primary before falling back to NAIF arithmetic. This
covers asteroid satellites and other small-body systems whose parent cannot be
derived from the planet/satellite ID convention.

**Files changed:**
- `TrailSampler.java` — added `sampleWithExplicitRef()` and extracted
  `doSample()`
- `SimulationCommands.java` — added `setTrailReferenceBody(int, int)` (D-065)
- `DefaultSimulationState.java` — per-body trail reference body map; removed
  unused `getTrailReferenceBodyMap()`
- `DefaultSimulationCommands.java` — wires command to state
- `TrailManager.java` — reads reference body; calls `sampleWithExplicitRef()`
- `KepplrScript.java` / `CommandRecorder.java` — String overloads, recording
- `SimulationCommandsTest.java` / `TrailManagerTest.java` — coverage

#### Branch 58 — Orbit trails in current camera frame (D-066, D-067)

Orbit trails are now drawn in the **current active camera frame** rather than
always in heliocentric J2000:

- **INERTIAL** — unchanged; heliocentric J2000 (or barycenter-anchored for
  natural satellites and bodies with a configured reference body).
- **SYNODIC** — at resample time each sample's relative position `dP = body −
  ref` is projected onto the `SynodicFrame.Basis` computed at the sample ET,
  storing synodic coordinates `(sx, sy, sz)`. At render time they are
  re-expressed in J2000 via the *current* basis `B_now`, so the trail appears
  frozen in the synodic rotating frame. The focus/selected body IDs mirror the
  `synodicFrameFocusId` / `synodicFrameSelectedId` override pattern from
  KepplrApp (Step 19c). (See D-066.)
- **BODY_FIXED** — at resample time `bf = R_i · dP` where `R_i` is the J2000 →
  body-fixed rotation at the sample ET. At render time `J2000 = focusNow +
  R_now^T · bf`. The reference body in BODY_FIXED mode is **always the focus
  body**, regardless of any per-body `setTrailReferenceBody` configuration (see
  D-067). `TrailRenderer` is completely frame-unaware; all transforms happen in
  `TrailManager` before calling the renderer.

The trail reads `activeCameraFrameProperty()` (the post-fallback actual frame),
not `cameraFrameProperty()` (the requested frame), so trails are automatically
consistent with what the camera is actually doing when BODY_FIXED or SYNODIC
falls back to INERTIAL.

`TrailState` record extended to 10 fields: added `synodicFocusId`,
`synodicSelectedId`, `synodicSamples`, `bodyFixedFocusId`, `bodyFixedSamples`.
Staleness checks cover all new fields so a frame switch triggers an immediate
resample.

**Files changed:**
- `TrailManager.java` — SYNODIC and BODY_FIXED render paths; new private static
  helpers `computeSynodicSamples`, `projectSynodic`, `buildSynodicRenderList`,
  `synodicToJ2000`, `computeBodyFixedSamples`, `projectBodyFixed`,
  `buildBodyFixedRenderList`, `bodyFixedToJ2000`, `renderJ2000`
- `TrailManagerTest.java` — `SynodicProjectionTest` nested class with
  SPICE-backed round-trip and axis-alignment tests

**664 tests, 0 failures.**

---

### Bug Fix: Cross-thread capture timing (branch 50-clock-update-bug)

Two race conditions in the capture path caused incorrect camera positioning and
wrong first-frame timing during `captureSequence()` on Linux (macOS masked the
issue due to different thread scheduling).

**(1) SimulationClock.setET() cross-thread state mutation (D-047).** `setET()`
updated both the atomic `TimeAnchor` and called `state.setCurrentEt(et)`
directly. When the script thread called `setET()` while the JME thread was
mid-`simpleUpdate()`, focus tracking and the synodic frame applier saw different
ETs within the same frame — one read the old state, the other the new. This
produced a camera offset of `etStep × bodyVelocity` (≈84 km for New Horizons at
6-second steps). **Fix:** `setET()` now only updates the atomic anchor.
`advance()` on the JME thread reads the anchor and sets state consistently at
the start of each frame.

**(2) pendingCapture read after super.update() (D-048).** `KepplrApp.update()`
read the `pendingCapture` volatile after `super.update()`. If the JME thread had
already started `advance()` before the script thread set the new ET anchor, the
first capture frame rendered at the stale ET (e.g., 07:00:08 instead of
07:00:00). **Fix:** `update()` now reads and clears `pendingCapture` *before*
`super.update()`, ensuring `advance()` picks up the latest anchor. The
framebuffer capture still runs after the render pass.

**Files changed:**
- `SimulationClock.java` — removed `state.setCurrentEt(et)` from `setET()`
- `KepplrApp.java` — moved `pendingCapture` read before `super.update()`
- `SimulationClockTest.java` — added `clock.advance()` after
  `setET()`/`setUTC()`
- `DefaultSimulationCommandsTest.java` — added `clock.advance()` and pause

**Bug fix is complete.**

---

## v0.3 Proposed Milestones

These steps are the recommended next work after the completed v0.2 rendering,
capture, scripting, and UI polish work. The theme is to make KEPPLR both more
cinematic and more useful as a SPICE-based mission geometry tool.

### Recommended Implementation Order

The step numbers below are stable roadmap identifiers, not a strict execution
order. Use this table as the recommended build sequence for v0.3 work and
selected backlog items.

Completed before this sequence: `BodyBlock.primaryID()` is now used as the
authoritative configured primary for trail period calculation, trail anchoring,
and GUI trail decluttering (D-076).

| Order | Roadmap item | Reason / dependency |
|---:|---|---|
| 1 | Step 38 slice: performance policy and lightweight telemetry | Establishes target budgets and observability before adding heavier render features. Defer full LOD enforcement until more v0.3 layers exist. |
| 2 | Step 30 slice: `.kepplrscene` format and atomic load/apply validation | Creates the persistence contract for current state before shots, events, reference layers, and richer overlay state expand it. |
| 3 | Step 37 slice: render manifests and deterministic capture metadata | Gives existing capture paths traceability before shot capture builds on top of them. |
| 4 | Step 31: Object search, recents, favorites, and bookmarks | High user value with few blockers; benefits from explicit parent-body metadata. |
| 5 | Step 29 slice: shot/keyframe core | Build fixed-pose/body-look-at shots, playback, preview, and deterministic shot capture after scene files and manifests exist. Defer boresight/surface-intercept constraints until Steps 34 and 35. |
| 6 | Backlog: camera navigation inertia and damping | Align interactive camera feel with the shot/easing model after the shot core is established. |
| 7 | Backlog: full camera control bindings spec | Specify mouse, keyboard, trackpad, and possible gamepad mappings once the movement model is settled. |
| 8 | Step 32: Time slider and event timeline | Depends on scene persistence for marker groups and observation windows; benefits from hardened ET/capture behavior. |
| 9 | Step 33 slice: geometry computation service and basic readouts | Add SPICE-backed geometry accessors and simple HUD/label readouts before advanced instrument readouts. |
| 10 | Step 34: Instrument boresight and targeting tools | Builds on existing frustum/footprint work and uses ellipsoid intercepts first; also completes deferred GUI color controls. |
| 11 | Step 35: Mesh-based surface intersections | Add mesh intersections after telemetry exists and boresight/footprint consumers are factored around an intersection abstraction. |
| 12 | Step 33 slice: boresight intercept readouts and advanced label priority | Fold instrument intercept values into geometry readouts after Steps 34 and 35 provide reliable targeting data. |
| 13 | Step 36: Reference geometry layers | Reuse the command/state/render/persistence patterns from earlier v0.3 work. |
| 14 | Step 38 slice: full LOD and update-cadence enforcement | Finish quality preset enforcement once mesh intersections, reference layers, readouts, and retained swaths have concrete costs. |

### 29. Shot / Keyframe System

Add a first-class cinematic shot abstraction above the existing camera commands.
The goal is to make authored camera sequences reproducible without forcing every
user to hand-write interpolation loops in Groovy.

**Requirements**
- Add a `Shot` model containing name, start/end camera pose, duration, easing,
  FOV, camera frame, optional UTC/ET, optional time rate, and optional
  focused/targeted/selected body changes.
- Support look-at constraints where camera position interpolates while
  orientation continuously points at a body, fixed point, instrument boresight,
  or surface intercept.
- Support shot playback from scripts and the GUI without blocking the JME render
  thread.
- Support deterministic frame capture from a shot sequence at a requested FPS.
- Provide a small set of high-level shot helpers such as arc, push-in, pull-back,
  reveal, dolly, truck, crane, and orbit.
- Draw an optional camera path preview with keyframe markers.

**Success criteria**
- A script can define a list of named shots, preview them interactively, and
  render a PNG sequence whose camera motion matches playback.
- Shot capture produces the same camera pose at frame `N` regardless of
  interactive frame rate.
- Existing primitive camera commands remain available and unchanged.

### 30. Scene Presets and Full-State Snapshot Files

Extend the compact state string idea into full authored scene files. The compact
state string remains the fast copy/paste bookmark; scene files become the
portable, readable representation for tutorials, comparison views, and shot
setup.

**Requirements**
- Add a readable `.kepplrscene` format, preferably JSON, with a version field.
- Capture time state, camera pose/orientation/FOV/frame, selected/focused/
  targeted bodies, render quality, window size, body visibility, labels, trails,
  trail durations, trail reference bodies, vectors, frustums, footprint
  persistence settings, retained swath clear state where applicable, HUD
  visibility, and optional shot definitions.
- Load scene files from scripts and the GUI.
- Keep unknown future fields ignored but preserved where practical.
- Include validation errors that identify the failing field and do not leave the
  app in a partially applied scene.

**Success criteria**
- A scene file can recreate an authored visual setup without manually re-running
  all setup commands.
- Scene files are stable across normal configuration reloads when referenced
  bodies/instruments still exist.
- Compact state strings continue to work for quick bookmarks.

### 31. Object Search, Recents, Favorites, and Bookmarks

Make large kernel sets easier to navigate. The current filterable body tree is
useful, but KEPPLR needs a richer discovery layer as body, spacecraft, and
instrument counts grow.

**Requirements**
- Add autocomplete by body name, NAIF ID, spacecraft name, and instrument name.
- Show result metadata: object type, NAIF ID, parent/primary body, distance from
  camera, distance from Sun, available body-fixed frame, available shape model,
  and available instruments where applicable.
- Add recent objects and user-defined favorites/bookmarks.
- Add filters for visible bodies, bodies with trails, bodies with labels, bodies
  with shape models, spacecraft, instruments, and hidden barycenters.
- Make search actions explicit: select, center, go to, point at, show trail, show
  label, show instruments.

**Success criteria**
- A user can quickly find a spacecraft or instrument in a dense mission config
  without knowing its exact NAIF ID.
- Favorites survive app restart.
- Search and favorite actions route through `SimulationCommands`; no simulation
  logic enters JavaFX UI classes.

### 32. Time Slider and Event Timeline

Add a timeline control for interactive exploration. This should complement, not
replace, exact UTC/ET entry and script-based time control.

**Requirements**
- Add a time slider that maps a visible interval to ET.
- Allow the visible interval to be set from explicit start/end UTC strings,
  current time ± duration, loaded kernel coverage, or an event group.
- Label the slider with event markers. Initial marker types:
  closest approach, user-defined UTC markers, script-defined markers, kernel
  coverage start/end, and observation windows loaded from scene files.
- Support event marker actions: jump to event, play from event, set range around
  event, and copy event UTC.
- Keep drag behavior responsive by updating `SimulationClock` through the same
  command/state path as existing time controls.
- Provide a script API for defining markers and marker groups.

**Success criteria**
- A user can scrub across a flyby or orbit interval and see bodies, trails,
  frustums, and footprints update consistently.
- Event labels remain readable and decluttered when many markers are close
  together.
- Marker jumps are exact UTC/ET jumps, not approximate slider-position jumps.

### 33. Measurement Labels and Geometry Readouts

Expose SPICE-derived geometry directly in the visualization and HUD. This is a
major step toward KEPPLR being an analysis tool rather than only a viewer.

**Requirements**
- Add optional labels/readouts for camera range, target range, light time, phase
  angle, Sun-target-observer angle, subsolar latitude/longitude, sub-observer
  latitude/longitude, local solar time, body-relative velocity, spacecraft
  altitude, and instrument boresight intercept coordinates.
- Support per-body and selected-body display modes.
- Add script accessors for common geometry quantities so scripts can wait on or
  branch by computed conditions.
- Provide user-configurable label priority rules beyond the current proximity
  decluttering policy.

**Success criteria**
- Users can create screenshots and videos that carry explanatory geometry
  annotations without external post-processing.
- Geometry values are computed from the same SPICE-backed ephemeris/frame path
  used by rendering.
- Labels remain optional and do not clutter the default visual mode.

### 34. Instrument Boresight and Targeting Tools

Build on the existing instrument frustum and footprint work with operationally
useful targeting aids.

**Requirements**
- Render instrument boresight lines independently from frustum visibility.
- Show boresight intercept markers and intercept body names when a body surface
  is hit.
- Show angular separation between boresight and selected/targeted body center.
- Add commands to point the camera along an instrument boresight, follow a live
  footprint, or center the view on the current boresight intercept.
- Add GUI controls for per-instrument frustum, live footprint, and retained
  swath colors.
- Add instrument filters in the Instruments menu for selected spacecraft/current
  focus/all instruments.

**Success criteria**
- A user can inspect where an instrument is looking without enabling the full
  frustum volume.
- A script can follow an instrument footprint across a flyby.
- Color changes are reflected in command recording and scene files.

### 35. Mesh-Based Surface Intersections

Implement GLB mesh intersection for clipped frustums, live footprints, retained
swaths, altitude calculations, and near-surface camera aids.

**Requirements**
- Build a body-fixed ray-to-mesh intersection path for GLB-backed bodies.
- Prefer mesh intersection when a usable surface mesh exists; fall back to
  ellipsoid intersection otherwise.
- Define which GLB geometry counts as the physical surface.
- Add an acceleration structure suitable for per-frame instrument footprint
  updates.
- Preserve existing footprint persistence semantics: only retain what was
  actually drawn while persistence is enabled.
- Expose whether a live result is mesh-derived or ellipsoid-derived.

**Success criteria**
- Irregular GLB-backed bodies receive clipped frustums and footprints based on
  their actual mesh geometry.
- Mesh-derived footprints remain stable in body-fixed coordinates.
- Performance remains acceptable with common mission shape models.

### 36. Reference Geometry Layers

Add explanatory geometry layers that can be toggled and recorded like existing
labels, trails, vectors, and frustums.

**Requirements**
- Add body-fixed latitude/longitude grids, equator, prime meridian, terminator,
  local horizon plane, orbital plane, ecliptic plane, ring plane, Sun/anti-Sun
  line, velocity/anti-velocity line, and spacecraft nadir/zenith indicators.
- Support per-body visibility and script control.
- Make layer styling readable at multiple distances and compatible with render
  quality presets.
- Include reference geometry state in full scene snapshots.

**Success criteria**
- A user can explain body orientation, lighting, orbital geometry, and viewing
  geometry directly in KEPPLR.
- Reference layers do not require special-case UI logic; they follow the same
  command/state/render architecture as other overlays.

### 37. Deterministic Replay and Render Manifests

Formalize reproducibility for script playback and image sequence capture.

**Requirements**
- Define deterministic replay expectations and numeric tolerances for camera
  state, ET progression, rendered frame timing, and capture output metadata.
- Write a render manifest for captures containing app version, OS/platform,
  config path/hash, loaded kernel list and hashes, scene file hash, script hash,
  starting state, output resolution, FPS, frame count, render quality, and ET per
  frame.
- Add an option to replay a manifest where local paths and kernel hashes match.
- Add golden tests for state evolution and capture timing where practical.

**Success criteria**
- A rendered sequence can be traced back to the exact config, kernels, scene,
  script, and frame times that produced it.
- Re-running a manifest on the same platform reproduces camera/time state within
  documented tolerances.

### 38. Performance, LOD, and Quality Policy

Define measurable behavior before adding more heavy visual features.

**Requirements**
- Define target FPS and memory budgets for common 1080p and 4K scenarios.
- Define quality preset semantics for texture filtering, star density, trail
  sample density, shadow/penumbra quality, frustum/footprint update cadence,
  label budget, and mesh intersection budget.
- Add LOD rules for GLB models, point sprites, trails, labels, reference layers,
  star catalogs, and retained swaths.
- Add lightweight performance telemetry in the HUD/log output for frame time,
  visible body count, trail sample count, label count, and mesh intersection
  time.

**Success criteria**
- Render quality presets have testable meaning.
- Large scenes degrade predictably rather than becoming unusable.
- Future visual features have explicit budgets to design against.

---

## Backlog (unsequenced, post-v0.3)

- Camera navigation inertia and damping (§16.2), after the shot/keyframe system
  establishes the desired camera feel.
- Full camera control bindings spec (§16.2), including mouse, keyboard,
  trackpad, and possible gamepad mappings.
- Optional collision/ground avoidance for near-surface viewing (§16.2), likely
  dependent on mesh intersection from Step 35.
- Gaia star catalog support and UI controls for star density/magnitude cutoff
  (requires user-downloaded files).
- Native Wayland support; Linux currently runs under XWayland.
- Motion blur, exposure, depth-of-field, and other capture-only cinematic
  post-processing effects.
- Richer spacecraft/lander material handling that better preserves
  Cosmographia/Celestia CMOD material semantics while retaining KEPPLR's
  physically meaningful Sun/body-shadow lighting.
