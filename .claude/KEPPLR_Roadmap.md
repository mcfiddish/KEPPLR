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

**The JavaFX control panel is a separate OS window (two-window layout).** The transparent overlay (Option B) was prototyped and rejected — position-sync lag on macOS is unacceptable and the platform complexity is not justified for v0.1. The JavaFX stage is a normal, non-transparent, non-always-on-top window. No `WindowManager` class. No GLFW position/minimize/focus callbacks. Do not revisit this decision.

**Tracking is not a separate camera behavior.** The F key and Stop Tracking are shortcuts for switching the camera frame to Synodic and Inertial respectively. `trackedBodyId`, `trackingAnchor`, `trackBody()`, and `stopTracking()` do not exist in the codebase. Pressing F with a targeted body set switches to the Synodic frame; pressing F in Synodic switches back to Inertial.

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
Synodic frame definition and math per §5. The targeted body serves as the synodic "other body." `CameraFrame` enum (`INERTIAL`, `BODY_FIXED`, `SYNODIC`) and `setCameraFrame()` added to `SimulationCommands`. `BODY_FIXED` deferred. Degenerate case threshold (1e-3 radians) in `KepplrConstants`. Note: the original step 5 implemented a separate "tracked mode" with a tracking anchor; this was replaced during step 19 — tracking is now equivalent to switching to the Synodic frame.

### 6. Synodic Camera Frame
Synodic frame definition and math per §5. The targeted body serves as the synodic "other body." `CameraFrame` enum (`INERTIAL`, `BODY_FIXED`, `SYNODIC`) and `setCameraFrame()` added to `SimulationCommands`. `BODY_FIXED` deferred. Degenerate case threshold (1e-3 radians) in `KepplrConstants`.

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
  bound to `activeCameraFrameProperty()`), Stop Tracking, Field of View
  control, camera frame fallback indicator. Stop Tracking calls
  `SimulationCommands.setCameraFrame(INERTIAL)` and is kept in sync with the
  Camera Frame submenu radio items — selecting Inertial via the submenu and
  pressing Stop Tracking are equivalent operations.
- Time — unchanged from step 9.
- Window — preset sizes: 1280×720, 1280×1024, 1920×1080, 2560×1440; resizes
  the JME window only. JavaFX window is not resized programmatically.

**Keyboard shortcuts** — wired through the JME input handler, not JavaFX. All
call `SimulationCommands`:
- G — `goTo` focused body
- F — toggles camera frame between SYNODIC and INERTIAL. If camera frame is
  currently INERTIAL and a targeted body is set, switches to SYNODIC. If
  camera frame is currently SYNODIC, switches to INERTIAL. No-op if frame is
  INERTIAL and no targeted body is set.
- T — target selected body
- Space — pause/resume
- `[` / `]` — decrease / increase time rate

Escape has no keyboard binding. JavaFX default Escape-closes-stage behavior
must be suppressed on the control window. Escape must not close either window
under any circumstance.

**Tracking is not a separate camera behavior.** F and Stop Tracking are
shortcuts for switching the camera frame to Synodic and Inertial respectively.
`trackedBodyId`, `trackingAnchor`, `trackBody()`, and `stopTracking()` do not
exist in the codebase. Any legacy call sites are replaced with
`setCameraFrame()` calls.

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

## Remaining Steps

---

### 19c. Camera Scripting API

This step adds camera navigation commands to `SimulationCommands` so that
all meaningful camera actions are scriptable. `CameraInputHandler` is
updated to delegate to these commands rather than manipulating camera state
directly. No new camera behavior is introduced — this is a refactor that
exposes existing behavior through the correct interface.

**New methods on `SimulationCommands`** — all non-blocking, all use
`TransitionController`. All must have complete Javadoc with usage examples.
If `durationSeconds` is zero or negative, the camera snaps instantly on the
next frame:
```java
/**
 * Change the camera distance from the focus body by a multiplicative factor.
 *
 * <p>Example: {@code zoom(2.0, 1.0)} doubles the distance over one second.
 * {@code zoom(0.5, 1.0)} halves it. Factor must be positive.
 * Clamped to [1.1 × focusBodyRadius, 1e15 km] per zoom rules in §10.
 *
 * @param factor      multiplicative distance factor; must be positive
 * @param durationSeconds transition duration in wall-clock seconds
 */
void zoom(double factor, double durationSeconds);

/**
 * Set the camera field of view.
 *
 * <p>Example: {@code setFov(45.0, 1.0)} transitions to a 45-degree FOV
 * over one second. Clamped to [FOV_MIN_DEG, FOV_MAX_DEG] in KepplrConstants.
 *
 * @param degrees         desired field of view in degrees
 * @param durationSeconds transition duration in wall-clock seconds
 */
void setFov(double degrees, double durationSeconds);

/**
 * Orbit the camera around the focus body by the given camera-relative angles.
 *
 * <p>Equivalent to a right-drag gesture. Positive {@code rightDegrees}
 * orbits clockwise when viewed from above. Positive {@code upDegrees}
 * orbits toward the camera's screen-up direction.
 *
 * <p>Example: {@code orbit(45.0, 0.0, 2.0)} orbits 45 degrees to the
 * right over two seconds.
 *
 * @param rightDegrees    degrees to orbit around the camera's screen-up axis
 * @param upDegrees       degrees to orbit around the camera's screen-right axis
 * @param durationSeconds transition duration in wall-clock seconds
 */
void orbit(double rightDegrees, double upDegrees, double durationSeconds);

/**
 * Tilt the camera in place around its screen-right axis.
 *
 * <p>Example: {@code tilt(10.0, 0.5)} tilts up 10 degrees over half a second.
 *
 * @param degrees         tilt angle in degrees; positive tilts up
 * @param durationSeconds transition duration in wall-clock seconds
 */
void tilt(double degrees, double durationSeconds);

/**
 * Roll the camera around its look axis.
 *
 * <p>Example: {@code roll(90.0, 1.0)} rotates the camera's up vector
 * 90 degrees clockwise over one second.
 *
 * @param degrees         roll angle in degrees; positive rolls clockwise
 * @param durationSeconds transition duration in wall-clock seconds
 */
void roll(double degrees, double durationSeconds);

/**
 * Set the camera position relative to the current focus body in the
 * current camera frame.
 *
 * <p>Example: {@code setCameraPosition(0, 0, 10000, 2.0)} moves the camera
 * to 10,000 km above the focus body's north pole (in body-fixed frame)
 * over two seconds.
 *
 * @param x               x component in km
 * @param y               y component in km
 * @param z               z component in km
 * @param durationSeconds transition duration in wall-clock seconds
 */
void setCameraPosition(double x, double y, double z,
                       double durationSeconds);

/**
 * Set the camera position relative to an explicit origin body in
 * the current camera frame.
 *
 * <p>Does not change the focused body. Useful for positioning the camera
 * relative to a body other than the current focus.
 *
 * <p>Example: {@code setCameraPosition(0, 0, 50000, 301, 3.0)} moves
 * the camera to 50,000 km above the Moon regardless of which body
 * is currently focused.
 *
 * @param x               x component in km
 * @param y               y component in km
 * @param z               z component in km
 * @param originNaifId    NAIF ID of the origin body
 * @param durationSeconds transition duration in wall-clock seconds
 */
void setCameraPosition(double x, double y, double z,
                       int originNaifId, double durationSeconds);

/**
 * Set the camera look direction and up vector in the current camera frame.
 *
 * <p>Vectors need not be normalized — they are normalized internally.
 * The up vector must not be parallel to the look vector.
 *
 * <p>Example — point along the ecliptic toward vernal equinox:
 * <pre>
 *   setCameraLookDirection(1, 0, 0,   // look toward +X (vernal equinox)
 *                          0, 0, 1,   // up toward +Z (ecliptic north)
 *                          2.0);
 * </pre>
 *
 * @param lookX           x component of look direction
 * @param lookY           y component of look direction
 * @param lookZ           z component of look direction
 * @param upX             x component of up vector
 * @param upY             y component of up vector
 * @param upZ             z component of up vector
 * @param durationSeconds transition duration in wall-clock seconds
 */
void setCameraLookDirection(double lookX, double lookY, double lookZ,
                            double upX,   double upY,   double upZ,
                            double durationSeconds);

/**
 * Switch to the synodic camera frame defined by explicit focus and target
 * bodies, without changing focused, targeted, or selected body state.
 *
 * <p>Use this when a script needs a specific synodic view without
 * disturbing interaction state. To switch to the synodic frame using
 * the current SimulationState focus and target, use
 * {@code setCameraFrame(CameraFrame.SYNODIC)} instead.
 *
 * <p>Example — Earth-Moon synodic view:
 * <pre>
 *   setSynodicFrame(399, 301); // focus=Earth, target=Moon
 * </pre>
 *
 * @param focusNaifId  NAIF ID of the focus body defining the frame origin
 * @param targetNaifId NAIF ID of the target body defining the +X axis
 */
void setSynodicFrame(int focusNaifId, int targetNaifId);
```

**`CameraInputHandler` refactor:**

All navigation actions in `CameraInputHandler` that correspond to a new
`SimulationCommands` method must delegate to it. Mouse drag and scroll
events are converted to equivalent `zoom`, `orbit`, `tilt`, and `roll`
calls with `durationSeconds = 0` (snap). Keyboard navigation shortcuts
follow the same pattern. The input handler retains responsibility for
detecting gestures and computing delta values — it does not retain
responsibility for applying them to the camera.

**Default durations:**

`DEFAULT_CAMERA_TRANSITION_DURATION_SECONDS` added to `KepplrConstants`.
Used by `CameraInputHandler` for keyboard navigation shortcuts (not mouse,
which snaps). The Groovy wrapper uses it for no-duration overloads.

**Hard constraints:**
- No camera math moves to the scripting layer — the Groovy wrapper calls
  `SimulationCommands` only.
- All new methods fully Javadoc'd with parameter descriptions and at least
  one usage example.
- `setSynodicFrame` must not write to `selectedBodyId`, `focusedBodyId`,
  or `targetedBodyId` in `SimulationState`.
- `mvn test` passes with no new failures.

---

### 20. Groovy Scripting Layer
Groovy-friendly wrapper delegating to `SimulationCommands`. Exposes
`waitSim()` and `waitWall()` timing primitives wired into the time model from
step 7, and `waitTransition()` from step 18. Every `SimulationCommands` call
must be loggable so real-time recordings can be transcribed as valid Groovy
scripts with `waitWall()` calls inserted for timing. No generic `wait()`
function per §11.2. All overlay commands added in step 19b are covered by
this layer automatically since they are on `SimulationCommands`.

---

## Known Limitations

**macOS: JME window requires a click to receive keyboard focus.**
Moving the mouse from the JavaFX control window into the JME render window
does not automatically transfer keyboard focus on macOS. The user must click
the JME window before keyboard shortcuts will respond. This is a macOS window
management constraint. Behavior on Linux (X11) is untested and may differ.

---

## Deferred / Out of Scope for v0.1

- Camera transition acceleration/deceleration (linear interpolation used initially)
- Camera navigation inertia and damping (§14.2)
- Full camera control bindings spec (§14.2)
- Object search and autocomplete UI (§14.3)
- Shape model rendering for irregular bodies (§14.6)
- Determinism and reproducible replay (§14.1)
- Performance acceptance criteria and LOD rules (§14.7)
- Gaia star catalog (requires user-downloaded files)
- Native Wayland support (Linux runs under XWayland)
- Instrument overlays (Frustum, Boresight) — UI scaffolded in prototype, implementation deferred