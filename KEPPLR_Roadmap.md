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

**`SimulationStateFxBridge` is the sole location of `Platform.runLater()` calls.** No other class may call `Platform.runLater()`. The JavaFX controller binds only to properties exposed by the bridge.

**`SimulationCommands` is also the Groovy scripting API.** The scripting layer (step 19) is a thin Groovy-friendly wrapper that delegates to `SimulationCommands`. Every method on `SimulationCommands` must be loggable so real-time recordings can be transcribed as executable Groovy scripts with `waitWall()` timing calls inserted between commands.

**The synodic frame "other body" is the currently targeted body.** No separate command or property is needed for this. If no targeted body exists, fall back to the inertial frame and log a warning.

**`BODY_FIXED` camera frame is stubbed as `UnsupportedOperationException`.** It has been in the `CameraFrame` enum since step 6 but is not yet implemented. Do not attempt to implement it outside of step 15.

**Spacecraft are always rendered as point sprites for now.** Shape model rendering for spacecraft is explicitly deferred. Do not implement it before the deferred shape model step.

**Ellipsoids only for body geometry.** `KEPPLREphemeris.getShape()` always returns an ellipsoid. Shape model rendering for irregular bodies is deferred. Do not use shape models before the deferred step.

**The Sun halo is deferred.** The Sun renders as a plain emissive sphere until step 17. The 2-pixel culling rule applies to the Sun like any other body but may need revisiting in step 17.

**Orbit drag is camera-relative.** Right drag orbits around the camera's current screen-right and screen-up vectors, not fixed world axes. This must not be changed to world-axis orbit without explicit discussion.

**Zoom is exponential.** Each scroll or keyboard zoom step changes camera distance by a fixed percentage. Minimum distance is 1.1× the focus body radius; maximum is 1e15 km matching the far frustum boundary.

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

### 11. Multi-Body Rendering and Multi-Frustum *(nearly complete)*
All bodies from `getBodies()` and `getSpacecraft()` rendered or culled each frame. Three-layer frustum assignment (Near/Mid/Far) with 10% overlap per §8. Sun as emissive light source. Small-body culling per §7.3 (2-pixel threshold). Spacecraft as point sprites. Bodies without body-fixed frame or valid ephemeris skipped. Bodies without orientation data rendered as untextured spheres per §12.3. Ellipsoids used throughout — shape models deferred.

---

## Remaining Steps

### 12. Orbital Trails
Trail rendering per §7.5. Trails sampled from SPICE at 180 samples per orbital period (approx. every 2 degrees), defaulting to 30 days if orbital period cannot be determined. Trails update dynamically with simulation time. Trail segments assignable to different frustum layers per §8.3. Trails drawable in heliocentric J2000 by default, optionally in other frames. Trail duration specifiable as an argument. A distinct rendering subsystem from body rendering with its own update cadence and memory management.

### 13. Vector Overlays
Instantaneous vector rendering: body-fixed axis directions, velocity vectors, Sun direction vectors. Rendered as line segments or arrows from body centers. Driven through `SimulationCommands` (user toggles on/off). Must respect multi-frustum assignment. General enough that adding a new vector type is a data change, not a code change.

### 14. Star Field
Star field renderer per §7.4 using the Yale Bright Star Catalog with proper motions. Star catalog abstracted behind an interface so Tycho-2 or GAIA can be swapped in future. Rendered in a separate frustum pass. The catalog interface design is load-bearing — get it right before implementing.

### 15. Body-Fixed Camera Frame
Implement `BODY_FIXED` in `CameraFrame`, stubbed as `UnsupportedOperationException` since step 6. Requires PCK-based body orientation from `KEPPLREphemeris`. Camera pose remains expressible in heliocentric J2000 at all times per §1.4.

### 16. Shadows and Eclipses
Analytic physical eclipse geometry per §9.3 (hybrid Option C). The Sun is an extended source for shadow calculations. All bodies except the Sun receive shadows. Night side renders dimmer than day side. Penumbra supported. Optional shape-model shadow refinement in the near frustum for irregular bodies. Quality presets (Low/Medium/High) per §9.4 adjusting shadow quality, trail density, star catalog cutoff, and Sun halo quality.

### 17. Sun Halo
Sun halo per §7.6. Bodies passing near the Sun must occlude the halo correctly per §8.4. The 2-pixel culling rule for the Sun established in step 11 may need special handling here.

### 18. UI — Body Selection and Camera Controls
Body selection controls (select, focus, target by NAIF ID or name). Camera frame selector in the Camera menu (Inertial / Body-Fixed / Synodic). Stop Tracking menu item. Object search deferred to §14.3.

### 19. Groovy Scripting Layer
`waitSim()` and `waitWall()` timing primitives wired into the time model from step 7. Groovy-friendly wrapper delegating to `SimulationCommands`. Every `SimulationCommands` call must be loggable so real-time recordings can be transcribed as valid Groovy scripts with `waitWall()` calls inserted for timing. No generic `wait()` function per §11.2.

---

## Deferred / Out of Scope for v0.1

- Camera navigation inertia and damping (§14.2)
- Full camera control bindings spec (§14.2)
- Object search and autocomplete UI (§14.3)
- Visibility layer toggles (§14.4)
- Labels and HUD decluttering policy (§14.5)
- Shape model rendering for irregular bodies (§14.6)
- Determinism and reproducible replay (§14.1)
- Performance acceptance criteria and LOD rules (§14.7)
