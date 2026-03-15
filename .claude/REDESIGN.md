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
* one **tracked** body

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

### 4.6 Tracked

Tracking is a shortcut for switching the camera frame to Synodic with the
targeted body as the "other body." It is not a separate camera behavior —
it is frame selection with a convenient name for users who do not think in
terms of reference frames.

* **F key** — if a targeted body is set, switches the camera frame to
  Synodic. The targeted body becomes the synodic "other body" per the key
  decision in the roadmap. If no targeted body is set, F is a no-op.
* **Stop Tracking** (View menu) — switches the camera frame back to
  Inertial. Equivalent to selecting Inertial in the Camera Frame submenu.
* The Camera Frame submenu and the F key / Stop Tracking are kept in sync —
  switching frame via the submenu updates the "tracked" state and vice versa.
* The Tracked row in the body status panel is removed. The active camera
  frame is already shown in the View menu indicator; a separate Tracked
  row is redundant.
* `trackedBodyId`, `trackingAnchor`, and all related properties on
  `SimulationState` are removed. `SimulationStateFxBridge.trackedBodyActiveProperty()`
  is removed. The F key handler calls `SimulationCommands.setCameraFrame(SYNODIC)`
  instead of `trackBody()`. `SimulationCommands.stopTracking()` calls
  `SimulationCommands.setCameraFrame(INERTIAL)`.
* The `trackBody()` method on `SimulationCommands` is removed.

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
* **Screen-space cluster suppression:** when two or more sprite-class bodies are within **2 pixels** of each other on screen, the body with the smaller physical radius is suppressed (not rendered). Bodies in an active interaction state (selected, focused, targeted, or tracked) are exempt from cluster suppression.
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
* The trail API must allow the **duration** to be supplied as an argument.
* Trails must update with simulation time (dynamic).
* Trails must be drawable in:

  * default: heliocentric J2000
  * optionally: other frames (for resonance visualization)
* Trails must be renderable in **segments** such that different segments can be drawn in different frustums if needed.

### 7.6 Vector Overlays [D-003]

* The renderer must support configurable direction-vector overlays drawn from a body center.
* Vector types must be defined via a **strategy interface** (`VectorType`), not an enum,
  to support parameterized types such as `VectorTypes.towardBody(int naifId)`.
* The set of active overlays must be managed independently of body selection state.

### 7.7 Illumination and Sun Halo

* The **Sun** is the only illumination source.
* The Sun must have a visible **halo**.
* Bodies passing near the Sun must be able to **occlude the Sun halo** correctly (see §8).

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

* A JavaFX control panel must exist as a transparent overlay stage positioned over the
  JME render window. The JME window renders at full performance; the overlay is kept in
  sync via GLFW position and focus callbacks managed by `WindowManager`.
* On Linux, GLFW must run in X11 mode; native Wayland is not supported.
* On macOS, JME runs on the OS main thread; JavaFX is launched from a background thread.
* The Linux launcher script (`kepplr.sh`) is a production deliverable.

### 10.2 Status Field

* The control window must include a text field displaying status info, including at least:

  * current selected/targeted/focused body
  * camera position and camera frame
  * UTC time
  * time rate
  * whether a body is currently tracked

---

## 11. Scripting Requirements (Groovy)

### 11.1 Scripting Support

* The system must support a Groovy scripting API.

### 11.2 Timing Functions

* The scripting API must include:

  * `waitSim(...)` — block until simulation time has advanced by the given amount
  * `waitWall(...)` — block until wall time has advanced by the given amount
  * `waitTransition()` — block until the active camera transition completes [D-015]
* The scripting API must **not** include a generic `wait(...)` function.

---

## 12. Texture Mapping Requirements

### 12.1 Texture Format

* Textures are **equirectangular maps**.

### 12.2 Longitude Convention

* The texture center longitude must be specified in the configuration file and accessible from `KEPPLRConfiguration`.

### 12.3 Missing Orientation Data [D-005]

* If a body lacks PCK/orientation data, it must render as an **untextured sphere** (assuming it otherwise has valid ephemeris coverage).

---

## 13. Out of Scope / Not Yet Specified

The following are acknowledged areas not fully specified in v0.1 and may be refined:

* exact UI menu structure and full set of controls (beyond required status and tracking stop)
* detailed camera control mappings (mouse/keyboard bindings)
* determinism guarantees and golden-test harness requirements
* shape model formats and loading pipeline (for irregular bodies)
* exact numeric tolerances for comparisons and rendering thresholds beyond those specified

---

## 14. Optional Requirements (Future)

This section lists commonly needed capabilities (e.g., in Cosmographia/Celestia-class tools) that are not required for v0.1 but are candidates for future requirement revisions.

### 14.1 Determinism and Reproducibility

* Define deterministic replay requirements for:

  * body/camera state evolution given identical kernels, initial state, and script inputs
  * capture pipelines (e.g., PNG sequence generation)
* Define numeric tolerances for “same result” across platforms.

### 14.2 Camera Controls and Navigation Semantics

* Specify input bindings and navigation modes (mouse/keyboard/gamepad), including:

  * orbit camera controls (yaw/pitch/roll) and damping/inertia
  * zoom/range behavior and constraints (min/max distance, pitch limits)
  * optional collision/ground avoidance for near-surface viewing
* Specify frame-switching UX invariants (e.g., preserve world pose vs preserve local orbit parameters).

### 14.3 Object Search and Discovery

* Provide a searchable object catalog UI with:

  * autocomplete by name/NAIF ID
  * recent objects
  * user-defined favorites/bookmarks

### 14.4 Visibility Layers and Rendering Toggles

* Add UI toggles to show/hide rendering layers such as:

  * orbit lines, trajectory trails
  * labels and HUD overlays
  * reference axes, grids, ring plane indicators
  * star magnitude cutoffs / density controls

### 14.5 Labels and HUD Policy

* Define label content and decluttering rules, including:

  * label types (name, distance, light-time, phase angle, etc.)
  * zoom-dependent visibility thresholds
  * overlap avoidance and priority rules

### 14.6 Asset Pipeline Requirements

* Specify supported model formats (e.g., glTF/GLB/OBJ) and texture conventions.
* Define caching/loading behavior and graceful degradation when assets are missing.

### 14.7 Performance and Quality Acceptance Criteria

* Expand quality presets into measurable acceptance criteria (e.g., target FPS at 1080p).
* Define LOD and update-cadence rules for:

  * textures/meshes
  * star field density
  * trail resampling
  * shadow resolution and penumbra fidelity
