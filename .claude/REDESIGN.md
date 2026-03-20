# KEPPLR Requirements v0.1

## 0. Purpose

KEPPLR is a deterministic, interactive 3D solar system simulator written in pure Java. It uses SPICE-compatible ephemeris and frame calculations (via Picante) and renders with JMonkeyEngine. A JavaFX control window provides status and interactive controls. KEPPLR exposes a Groovy scripting API for automation.

This document defines **behavioral requirements** (what the system must do), not implementation details.

---

## 1. Core Physics and Reference Systems

### 1.1 SPICE / Ephemeris Authority

* KEPPLR **must use SPICE-compatible calculations for all ephemeris and frame transforms**.
* The simulator uses a provided `KEPPLREphemeris` interface as the sole authority for:

  * body state vectors (position/velocity)
  * frame transforms
  * time conversions (ET/TDB ↔ UTC)
  * light-time and aberration corrected states

### 1.2 Time System

* The **core time system is TDB**, represented as **ephemeris seconds past J2000** (“ET”).

* Simulation time advancement must follow:

  `et += dt_real * timeRate`

  where:

  * `dt_real` = measured wall-clock seconds elapsed between frames
  * `timeRate` = simulation seconds per wall-clock second (see §2.3)

* When simulation time is **paused**, **ET must not change**.

### 1.3 Base Frame and Global Position Convention

* The base inertial frame is **J2000**.
* **ICRF is considered equivalent to J2000**.
* For any body (anything with a NAIF ID) that is defined with a valid time range in an SPK:

  * its position **must be definable relative to the Sun in J2000** (heliocentric J2000).

### 1.4 Camera Global Pose Requirement

* The camera pose (position + orientation) **must always be expressible in heliocentric J2000 coordinates**.
* The camera may be internally represented in other frames (e.g., body-fixed), but it must always be convertible to/from heliocentric J2000 **without loss of meaning**, enabling robust camera frame switching.

### 1.5 Camera Frames [D-005]

The simulator must support the following camera frames:

* **Inertial** (J2000)
* **Body-fixed** (focus-body fixed frame, when a focus exists)
* **Synodic** (defined in §5)

> **Implementation note:** When PCK orientation data is unavailable for the focus body,
> the camera silently falls back to the inertial frame. `SimulationState` exposes both
> the *requested* frame and the *actual* frame as distinct properties so the UI always
> reflects ground truth. See also §12.3.

---

## 2. Units, Angles, and Time Rate

### 2.1 Canonical Units

* Distance: **kilometers (km)**
* Velocity: **km/s**
* Time: **seconds**
* Camera and body state vectors must use km and km/s.

### 2.2 Angles

* Internally, angles must be represented in **radians**.
* Public input/output (UI and scripting) must use **degrees** unless explicitly stated otherwise.

### 2.3 Time Rate Semantics

* `timeRate` is expressed in **simulation seconds per wall-clock second**.

  * Example: “3x” means `timeRate = 3.0` sim-seconds/sec, not “multiply existing rate by 3”.

---

## 3. Configuration and Ephemeris Access [D-001]

### 3.1 Configuration Source of Truth

* SPICE kernels are defined in a configuration file.
* A singleton `KEPPLRConfiguration` provides access to configuration and ephemeris services.

### 3.2 Singleton and Threading Rule

* `KEPPLRConfiguration` is instantiated at startup and accessed via:

  `KEPPLRConfiguration.getInstance()`

* `KEPPLRConfiguration` provides ephemeris via:

  `KEPPLRConfiguration.getInstance().getEphemeris()`

* `KEPPLRConfiguration` holds a `ThreadLocal<KEPPLREphemeris>`.

### 3.3 No Passing/Storing Rule

* The application **must not store or pass** references to `KEPPLRConfiguration` or `KEPPLREphemeris`.

  * Code must acquire them at the point of use via `getInstance()` / `getEphemeris()`.

---

## 4. Object States and Interaction Semantics [D-002]

### 4.1 Renderable Body Definition

* A “body” is any object with a NAIF ID that can be resolved through ephemeris and configuration.
* If a body has **no valid ephemeris at the current simulation time**, it **must not be displayed**.

### 4.2 Interaction Modes (Global Singletons)

At any time, the application maintains at most:

* one **selected** body
* one **targeted** body
* one **focused** body

### 4.3 Selected

* Selected affects only the **HUD information display**.
* Selection **must not** change camera pose or camera dynamics.

### 4.4 Targeted ("Point At")

* Targeted means:

  * it **is selected**, and
  * camera orientation slews to point at the **target body center** (SPICE-computed position)
    via `pointAt(naifId, duration)` over a configurable duration. [D-010]
* When a body is targeted:

  * **camera position remains fixed**
  * **camera orientation slews** to point to target center over the default slew duration.
* In the GUI this corresponds to **Point At**.

### 4.5 Focused (Orbit Camera)

* Focused means:

  * the camera is in an **orbit camera** mode with the **focus body center** as the pivot.
  * the camera coordinate system origin is centered on the focus body.
* When a body is chosen as focus:

  * it also becomes the **selected** body
  * and becomes the **targeted** body
  * the camera slews to point at the body via `pointAt`, then translates along its
    line of sight to a default apparent radius via `goTo`. [D-010, D-013]
    Both transitions are non-blocking; `waitTransition()` is available in the
    scripting layer to sequence subsequent commands. [D-015]

* After focusing, the user may choose another body as the **target** (distinct from focus).

#### Focused camera pose persistence

* When focused, the camera pose (frame, position, orientation quaternion) must persist as a stable state.
* User interactions may modify the pose; after interaction ends, the updated pose must be maintained.

### 4.6 Tracking

"Tracking" is not a distinct interaction mode — it is a shortcut for
switching the camera frame to Synodic with the targeted body as the
"other body."

* Pressing **F** while a targeted body is set switches the camera frame to
  Synodic. If the camera frame is already Synodic, F switches back to
  Inertial. If no targeted body is set and the frame is Inertial, F is a
  no-op.
* **Stop Tracking** in the View menu switches the camera frame to Inertial.
  It is equivalent to selecting Inertial in the Camera Frame submenu.
* The Camera Frame submenu and F / Stop Tracking are always kept in sync.
* There is no separate "tracked body" property. The synodic "other body"
  is always the currently targeted body (see §5).

---

## 5. Synodic Camera Frame Definition

### 5.1 Axes

* Synodic frame uses:

  * **+X** = normalized vector from focus body center → “other body” center
  * **+Z** = secondary axis:

    * defaults to **J2000 +Z**, OR
    * may be user specified as (**frame**, **vector**) and converted into J2000
  * **+Y** = **Z × X** (right-handed system)
* Orthonormalization must be performed to produce a valid rotation basis.

### 5.2 Degenerate Case Handling

* If +X is too close to J2000 +Z (cross product magnitude too small), the system must use:

  * **Ecliptic J2000 +Z** as the secondary axis instead of J2000 +Z.

---

## 6. Light-Time and Aberration Corrections

### 6.1 Supported Correction Modes

* Only two aberration correction modes are allowed:

  * `NONE`
  * `LT+S`
* This is represented as an enum compatible with Picante and passed through `KEPPLREphemeris`.

### 6.2 Default Correction Policy

* Default behavior applies correction to **both**:

  * rendered body positions
  * camera pointing computations (e.g., “Point At”)
* Correction reference point:

  * use the **coordinate frame origin (the focus body)** as the reference point for correction.
* If **no focus body exists**, use **geometric positions only** (no correction).

### 6.3 Limitations

* If the camera is very far from the coordinate frame origin, light-time correction may be inaccurate.
* This limitation must be documented; no special fallback behavior is required beyond §6.2.

---

## 7. Rendering Requirements

### 7.1 Technology

* Rendering must use **JMonkeyEngine** (pure Java).

### 7.2 Body Rendering Types [D-007]

* Bodies must be renderable as:

  * **spheres with textures**
  * **Saturn’s rings** *(implemented in Step 16a; see also §9 for shadow coupling)*
  * **point sprites** for very small bodies (see §7.3)
* Textures must be oriented using the **body-fixed frame**:

  * the body-fixed → J2000 transform is provided by `KEPPLREphemeris`.

### 7.3 Small Body Culling Rule

* Bodies with apparent radius < **2 pixels** must be drawn as point sprites.
* Satellites are **not** exempt from sprite rendering — they render as sprites, not culled.
* **Screen-space cluster suppression:** when two or more sprite-class bodies are within **2 pixels** of each other on screen, the body with the smaller physical radius is suppressed (not rendered). Bodies in an active interaction state (selected, focused, or targeted) are exempt from cluster suppression.
* Satellite NAIF ID definition must follow NAIF ID rules documented by NAIF (see sections "Barycenters" and "Planets and Satellites" in NAIF's NAIF ID reference):

  * [https://naif.jpl.nasa.gov/pub/naif/toolkit_docs/FORTRAN/req/naif_ids.html](https://naif.jpl.nasa.gov/pub/naif/toolkit_docs/FORTRAN/req/naif_ids.html)

### 7.4 Star Field [D-004]

* A built-in star field must exist using the **Yale Bright Star Catalog** including proper motions.
* The star catalog must be abstracted behind an interface supplied by the user so catalogs like Tycho-2 or GAIA can be swapped in the future.

### 7.5 Orbits and Trajectory Trails

* The renderer must support orbit lines and trajectory trails.
* Trails must be sampled from SPICE over time:

  * default sample density: **180 samples per orbital period** (≈ every 2 degrees).
* Orbital period determination:

  * if an orbital period cannot be calculated from orbital elements, default trail duration is **30 days**.
* The trail API must allow the **duration** to be supplied as an argument, per body.
* Trails must update with simulation time (dynamic).
* Trails must be drawable in:

  * default: heliocentric J2000
  * optionally: other frames (for resonance visualization)
* Trails must be renderable in **segments** such that different segments can be drawn in different frustums if needed.
* Trail visibility must be togglable **per body** via `SimulationCommands.setTrailVisible(int naifId, boolean visible)`.
* Trail duration must be settable **per body** via `SimulationCommands.setTrailDuration(int naifId, double seconds)`.
* **Decluttering policy:** trails for satellite bodies are suppressed when the satellite's screen position is within
  `TRAIL_DECLUTTER_MIN_SEPARATION_PX` of its primary body's screen position. As the camera zooms in and the satellite
  separates from its primary on screen, its trail becomes visible. This threshold is defined in `KepplrConstants`.
* **Barycenter trail policy:** the GUI global trail toggle skips barycenter
  bodies except the Pluto Barycenter (NAIF ID 9), whose trail is meaningful
  due to the Pluto-Charon mass ratio. The per-body API imposes no such
  restriction.

### 7.6 Vector Overlays [D-003]

* The renderer must support configurable direction-vector overlays drawn from a body center.
* Vector types must be defined via a **strategy interface** (`VectorType`), not an enum,
  to support parameterized types such as `VectorTypes.towardBody(int naifId)`.
* The set of active overlays must be managed independently of body selection state.
* Vector visibility must be togglable **per body per vector type** via
  `SimulationCommands.setVectorVisible(int naifId, VectorType type, boolean visible)`.

### 7.7 Illumination and Sun Halo

* The **Sun** is the only illumination source.
* The Sun must have a visible **halo**.
* Bodies passing near the Sun must be able to **occlude the Sun halo** correctly (see §8).

### 7.8 Labels

* Each body must support a name label rendered as screen-space text attached to its scene node.
* Label text is the body name resolved via `SpiceBundle.getObjectName()`.
* Label visibility must be togglable **per body** via `SimulationCommands.setLabelVisible(int naifId, boolean visible)`.
* **Decluttering policy:** a label is drawn only if no other label belonging to a
  larger-radius body is within `LABEL_DECLUTTER_MIN_SEPARATION_PX` of its screen
  position. This produces zoom-dependent behavior: at large distances, major planets
  are labeled and their satellites are suppressed because satellite screen positions
  cluster near the primary; as the camera moves closer and satellites separate on
  screen, their labels appear. `LABEL_DECLUTTER_MIN_SEPARATION_PX` is defined in
  `KepplrConstants`.

### 7.9 HUD

Two independent HUD elements rendered by JME on the render thread:

* **Time display** — current simulation UTC, upper-right corner.
  Toggled via `SimulationCommands.setHudTimeVisible(boolean)`.
* **Info display** — focused body name and distance from camera, upper-left corner.
  Toggled via `SimulationCommands.setHudInfoVisible(boolean)`.

Both are on by default.

---

## 8. Multi-Frustum Rendering and Occlusion

### 8.1 Frustum Layers

The renderer must use multiple frustums to manage scale:

* Near: **1 m to 1000 km**
* Mid: **1000 km to 1e9 km**
* Far: **1e9 km to 1e15 km**
* Stars: separate frustum pass

These thresholds are not user-configurable but must be defined in an easily discoverable location in code (e.g., enum/constants in a camera controller).

### 8.2 Overlap Policy

* Adjacent frustum ranges must overlap by **10%**, hard-coded.

### 8.3 Object-to-Frustum Assignment

* Bodies must be assigned to the **nearest frustum that fully contains the body** (based on a bounding volume appropriate for the body).
* Orbit trails must be segmented to allow segments to be assigned to different frustums as necessary.

### 8.4 Occlusion Requirement

* **Occlusion correctness:** opaque objects must occlude across frustums; transparent effects (rings/halo) must be depth-aware.

---

## 9. Shadows and Eclipses [D-007]

### 9.1 Required Phenomena

* Shadows can be cast by **any body with a radius**.
* All objects other than the Sun must **receive shadows**.
* The night side must render dimmer than the day side.

### 9.2 Penumbra Requirement

* The simulator must support **penumbra** (extended-source Sun) effects.

### 9.3 Eclipse Implementation Model (Hybrid, Option C)

* Default eclipse/shadowing must use **analytic physical eclipse geometry** (spherical/ellipsoidal approximation) suitable for solar-system scale and penumbra.
* If an irregular body has an available shape model:

  * the system may optionally refine shadowing using the shape model **only in the near regime** (e.g., near frustum / close range), while still using analytic methods for general penumbra behavior.
* The system must support a quality/performance option to treat the Sun as a **point source** for shadow calculations.

### 9.4 Quality Presets (Performance Requirements)

The simulator must provide quality presets (at minimum Low/Medium/High) that can adjust:

* shadow quality / penumbra fidelity (including point-source approximation as an option)
* trail update density / cadence
* star catalog magnitude cutoff / density
* Sun halo quality

The goal is to maintain interactive performance by allowing reduced-cost settings.

---

## 10. UI Requirements (JavaFX Control Window)

### 10.1 JavaFX Control Window

* The JavaFX control panel is a **separate OS window** (two-window layout).
* It opens alongside the JME render window at launch, positioned to its right.
* Closing either window must close both — the shutdown path is symmetric.
* The JavaFX window is a normal, non-transparent, non-always-on-top stage.
  No overlay positioning, no GLFW callbacks for window management.

### 10.2 Status Fields

* The control window must include a status panel displaying at least:

  * current selected / targeted / focused body (three rows, each showing body name or "—")
  * camera position and active camera frame
  * UTC time and time rate

### 10.3 Overlays Menu

The JavaFX menu bar must include an **Overlays** menu with:

* **Labels** — global toggle (calls `setLabelVisible` on all known bodies)
* **HUD/Info** — toggles the info display (focused body name and distance)
* **Show Time** — toggles the time HUD display
* **Show Trajectories** — global trail toggle (calls `setTrailVisible` on all known bodies)
* **Current Target** submenu — vector overlays for the focused body:

  * Sun Direction
  * Earth Direction
  * Velocity Direction
  * Trajectory (trail for focused body)
  * Axes

All menu items call `SimulationCommands` only — no rendering logic in `ui/`.

---

## 11. Scripting Requirements (Groovy)

### 11.1 Scripting Support

* The system must support a Groovy scripting API.
* Scripts are loaded from `.groovy` files via `File → Run Script` in the
  JavaFX control window.
* Each script runs on a dedicated daemon thread, separate from the JME render
  thread and the JavaFX application thread. The simulation loop continues
  normally while a script is executing.
* If a script is already running when `Run Script` is invoked, a confirmation
  dialog is shown before interrupting the current script.

### 11.2 Timing Functions

* The scripting API must include:

  * `waitWall(double seconds)` — block the script thread until that many
    wall-clock seconds have elapsed
  * `waitSim(double seconds)` — block until simulation time has advanced by
    the given number of seconds from the moment of the call. Polls at
    `SCRIPT_WAIT_POLL_INTERVAL_MS` intervals. Handles negative time rates
    (reverse time). **Blocks indefinitely if the simulation is paused or the
    time rate works against the target** — scripts relying on this primitive
    must ensure the simulation is running in the correct direction.
  * `waitUntilSim(double etSeconds)` — block until simulation ET reaches the
    given absolute value. Subject to the same indefinite-block caveat as
    `waitSim`. If the target ET has already been passed in the current time
    direction, returns immediately.
  * `waitUntilSim(String utc)` — block until simulation time reaches the given
    UTC string (same format accepted by `setUTC()`). Converts UTC to ET via
    ephemeris at the point of call, then delegates to `waitUntilSim(double)`.
  * `waitTransition()` — block until `transitionActiveProperty()` is false
    [D-015]

* The scripting API must **not** include a generic `wait(...)` function.

### 11.3 Name Resolution

* Every `SimulationCommands` method that accepts a NAIF ID has a corresponding
  String-name overload on `KepplrScript`. For methods with more than one NAIF
  ID parameter, one all-String overload is provided; mixed int/String
  combinations are not.
* Name resolution is performed by `BodyLookupService` in `kepplr.ephemeris`.
  Resolution is case-insensitive. An unresolvable name logs the error and
  throws `IllegalArgumentException`, stopping the script. Silent no-ops are
  not permitted.
* No name resolution logic may exist in `ui/` or in the scripting layer itself.
  `KepplrScript` calls `BodyLookupService` exclusively.

### 11.4 Session Recording

* The system must support recording an interactive session as a runnable Groovy
  script via `File → Start/Stop Recording` in the JavaFX control window.
* `CommandRecorder` is a decorator on `SimulationCommands`. All interactive
  paths (status window, camera input handler) are wired through the recorder
  so every user action is capturable. Script execution is wired to raw
  `SimulationCommands` directly — scripts are never self-recording.
* When recording is stopped, the recorder serializes the captured log as a
  Groovy script and opens a file-save dialog.
* Instant camera commands (`durationSeconds == 0`) are coalesced within a
  250ms window rather than recorded verbatim — see D-024.
* Commands with `durationSeconds > 0` are always recorded verbatim.
* `VectorType` arguments are serialized via `VectorType.toScript()` — see
  D-026.

---

## 12. Texture Mapping Requirements

### 12.1 Texture Format

* Textures are **equirectangular maps**.

### 12.2 Longitude Convention

* The texture center longitude must be specified in the configuration file and accessible from `KEPPLRConfiguration`.

### 12.3 Missing Orientation Data [D-005]

* If a body lacks PCK/orientation data, it must render as an **untextured sphere** (assuming it otherwise has valid ephemeris coverage).

---

## 13. Instrument Frustum Overlays

### 13.1 Frustum Rendering

* The simulator must render translucent frustum pyramid overlays for instruments
  defined in loaded IK kernels.
* Each frustum is a closed pyramid whose apex is placed at the camera-relative
  J2000 position of the instrument's center body, and whose base vertices are:

  `base[i] = apex + normalize(boundVector_in_J2000) × INSTRUMENT_FRUSTUM_DEFAULT_EXTENT_KM`

* Visibility must be controllable **per instrument** by NAIF code via
  `SimulationCommands.setFrustumVisible(int naifCode, boolean)` and by name
  via `setFrustumVisible(String name, boolean)`.
* Instrument entries must be rebuilt when a new configuration is loaded.

### 13.2 FOV Shape Handling

* SPICE defines four FOV shapes: CIRCLE, ELLIPSE, RECTANGLE, POLYGON.
* RECTANGLE and POLYGON FOVs use the SPICE bound vectors directly.
* CIRCLE (1 bound vector) and ELLIPSE (2 bound vectors) must be approximated
  as regular polygons with `INSTRUMENT_FRUSTUM_CIRCLE_APPROX_SIDES` sides
  so the pyramid mesh can be constructed. See D-033.

### 13.3 Frustum Layer Assignment

* The frustum pyramid must be assigned to the layer containing its apex
  (spacecraft position), using the apex distance — not apex + extent.
  This matches the `VectorRenderer` pattern (§8.3).

### 13.4 Out of Scope for v0.1

* Boresight line rendering (separate from the pyramid faces)
* Frustum shortening when the boresight intersects a body surface
* Per-instrument color configuration (hardcoded cyan)
* Distance culling of frustums

---

## 15. Out of Scope / Not Yet Specified

The following are acknowledged areas not fully specified in v0.1 and may be refined:

* exact UI menu structure and full set of controls (beyond required status and tracking stop)
* detailed camera control mappings (mouse/keyboard bindings)
* determinism guarantees and golden-test harness requirements
* shape model formats and loading pipeline (for irregular bodies)
* exact numeric tolerances for comparisons and rendering thresholds beyond those specified

---

## 16. Optional Requirements (Future)

This section lists commonly needed capabilities (e.g., in Cosmographia/Celestia-class tools) that are not required for v0.1 but are candidates for future requirement revisions.

### 16.1 Determinism and Reproducibility

* Define deterministic replay requirements for:

  * body/camera state evolution given identical kernels, initial state, and script inputs
  * capture pipelines (e.g., PNG sequence generation)
* Define numeric tolerances for “same result” across platforms.

### 16.2 Camera Controls and Navigation Semantics

* Specify input bindings and navigation modes (mouse/keyboard/gamepad), including:

  * orbit camera controls (yaw/pitch/roll) and damping/inertia
  * zoom/range behavior and constraints (min/max distance, pitch limits)
  * optional collision/ground avoidance for near-surface viewing
* Specify frame-switching UX invariants (e.g., preserve world pose vs preserve local orbit parameters).

### 16.3 Object Search and Discovery

* Provide a searchable object catalog UI with:

  * autocomplete by name/NAIF ID
  * recent objects
  * user-defined favorites/bookmarks

### 16.4 Visibility Layers and Rendering Toggles

Labels, HUD elements, orbit trails, and vector overlays are implemented in
step 19b with per-body API and global GUI toggles. Remaining deferred items:

* reference axes, grids, ring plane indicators
* star magnitude cutoffs / density controls

### 16.5 Labels and HUD Policy

Name labels with zoom-dependent decluttering and HUD time/info displays are
implemented in step 19b. Remaining deferred label types:

* distance, light-time, phase angle, and other data labels
* user-configurable label priority rules beyond the proximity-based policy

### 16.6 GLB Shape Model Rendering

Shape models for bodies and spacecraft are loaded from GLB files and replace
the default ellipsoid / point sprite geometry in the scene graph.

#### 16.6.1 GLTFUtils

`kepplr.visualization.jme.util.GLTFUtils` must be ported from the prototype.
Its sole initial responsibility is `readModelToBodyFixedQuatFromGlb(Path)`,
which reads the quaternion stored in the GLB JSON extras at
`asset.extras.kepplr.modelToBodyFixedQuat.value = [x,y,z,w]` without a
third-party JSON library. No other code may read this field directly.

#### 16.6.2 Asset Manager Registration

`KEPPLRConfiguration.getInstance().resourcesFolder()` must be registered as a
JME `FileLocator` once, in the JME `simpleInitApp` path. Shape model paths
returned by `BodyBlock.shapeModel()` and `SpacecraftBlock.shapeModel()` are
relative to that folder and are passed directly to `assetManager.loadModel()`.

#### 16.6.3 BodyBlock Shape Models

Natural bodies (from `getKnownBodies()`) are full scene objects with lighting,
material, and texture configuration. When `BodyBlock.shapeModel()` returns a
non-null path:

* Load the GLB via JME's `GlbLoader`. Apply `SamplerPreset.QUALITY_DEFAULT`
  (Trilinear min, Bilinear mag, anisotropy 8) to all textures immediately after
  load, using the same logic as the prototype viewer.
* Read `modelToBodyFixedQuat` from the GLB extras via `GLTFUtils`.
* Wrap the loaded Spatial in a Node named "glbModelRoot". Set
  `modelToBodyFixedQuat` as its local rotation. Attach this node as a child of
  `bodyFixedNode`, which already carries the time-varying SPICE body-fixed
  frame rotation — the two rotations compose correctly by construction.
* GLB files are authored in **kilometers**. No scale transform is applied.
* The glbModelRoot replaces `fullGeom` (the ellipsoid) in the scene graph.
  `spriteGeom` is unaffected.
* If the path is null, the file is missing, or loading fails: log a warning at
  WARN level and fall back to the existing ellipsoid. The simulation must
  continue normally.

#### 16.6.4 SpacecraftBlock Shape Models

Spacecraft (from `getSpacecraft()`) use their GLB-embedded PBR materials
as-is — colors, textures, and metallic/roughness values are defined in the
GLB and must not be overridden. Spacecraft are lit by the scene sun light
exactly as natural bodies are. They do not go through KEPPLR's body material
pipeline (no equirectangular texture mapping, no `textureAlignNode`, no
center-longitude adjustment). The standard sampler preset is applied to their
textures. When `SpacecraftBlock.shapeModel()` returns a non-null path:

* Load and apply the sampler preset identically to §16.6.3.
* Read and apply `modelToBodyFixedQuat` identically to §16.6.3.
* GLB files are authored in **meters**. Apply a uniform scale of
  `0.001 × SpacecraftBlock.scale()` to convert to kilometers.
  `SpacecraftBlock.scale()` defaults to 1.0 if not configured.
* The glbModelRoot replaces the point sprite geometry in the scene graph.
* If the path is null, the file is missing, or loading fails: log a warning at
  WARN level and fall back to the existing point sprite. The simulation must
  continue normally.

#### 16.6.5 Frame Semantics

* `bodyFixedNode` carries the time-varying SPICE body-fixed → J2000 rotation,
  updated each frame by `BodySceneManager`, exactly as it does today for
  ellipsoids and sprites. This update must not change.
* `modelToBodyFixedQuat` (the constant quaternion from the GLB extras) is
  applied once at load time as the local rotation of `glbModelRoot`. It maps
  glTF model-space into the body-fixed frame expected by SPICE. It is never
  reapplied or updated per-frame.

#### 16.6.6 Iteration Scope

`getKnownBodies()` provides the body set for §16.6.3. `getSpacecraft()`
provides the spacecraft set for §16.6.4. Both sets are processed once at scene
graph construction time. Dynamic loading of shape models at runtime is out of
scope for this step.

### 16.7 Performance and Quality Acceptance Criteria

* Expand quality presets into measurable acceptance criteria (e.g., target FPS at 1080p).
* Define LOD and update-cadence rules for:

  * textures/meshes
  * star field density
  * trail resampling
  * shadow resolution and penumbra fidelity