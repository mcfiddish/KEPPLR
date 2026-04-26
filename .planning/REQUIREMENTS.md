# Requirements: KEPPLR Stabilization and v0.3 Foundations

**Defined:** 2026-04-26
**Core Value:** KEPPLR must remain a scientifically credible, scriptable, reproducible SPICE visualization tool while new rendering, scene, and mission-geometry features are added.

## v1 Requirements

### Baseline

- [ ] **BASE-01**: Developer can run the current test baseline and see pre-existing failures documented before phase work begins.
- [ ] **BASE-02**: Developer can run `mvn test` and `mvn spotless:check` as explicit gates for changes made during this milestone.
- [ ] **BASE-03**: Developer can identify which mapped concern each stabilization change addresses.

### Configuration

- [ ] **CONF-01**: User receives structured configuration-load errors instead of process termination for missing or invalid config paths.
- [ ] **CONF-02**: Multiple KEPPLR instances or tests do not collide through a shared `java.io.tmpdir/resources` extraction path.
- [ ] **CONF-03**: Configuration reload behavior is covered by tests for singleton replacement, thread-local ephemeris access, and render/script interaction boundaries.

### Scripting

- [ ] **SCR-01**: User documentation clearly states that Groovy scripts are trusted local code with filesystem, network, process, configuration, and state access.
- [ ] **SCR-02**: Script stop/replacement behavior is tested for cooperative interruption and blocking KEPPLR wait primitives.
- [ ] **SCR-03**: Command recording remains executable for newly touched `SimulationCommands` methods.

### Rendering

- [ ] **REND-01**: Render-path test infrastructure contains at least one real `@Tag("render")` smoke or focused test, or a documented blocker if the live render harness cannot support it yet.
- [ ] **REND-02**: Eclipse/shadow quality policy has measurable behavior for occluder limits and render quality tiers before GLB shape-shadow expansion.
- [ ] **REND-03**: GLB-backed body and spacecraft rendering has regression coverage or documented manual verification paths for material, lighting, and fallback behavior.
- [ ] **REND-04**: Wide-cone star tile lookup behavior is explicitly tested near and beyond the documented convexity boundary.

### Data

- [ ] **DATA-01**: Gaia catalog lookup failure modes produce clear validation or error messages when optional source indexes are missing.
- [ ] **DATA-02**: Gaia tile cache behavior is bounded or measured by memory-oriented criteria rather than only tile count, or a follow-up is documented with evidence.
- [ ] **DATA-03**: Model conversion tooling has focused fixture tests for pure parsing/metadata behavior that can run without Blender.

### Reproducibility

- [ ] **REPRO-01**: Capture outputs can include a render manifest with app version, platform, config identity, kernel identity, script or scene identity, render quality, resolution, frame count, and ET per frame.
- [ ] **REPRO-02**: Deterministic replay expectations and numeric tolerances are documented for camera state, ET progression, and capture timing.
- [ ] **REPRO-03**: Lightweight performance telemetry reports frame time and key scene counts needed to set quality budgets.

### Scene

- [ ] **SCENE-01**: `.kepplrscene` format is specified with versioning, readable JSON structure, validation errors, and unknown-field handling.
- [ ] **SCENE-02**: Scene load/apply is atomic: invalid scenes do not leave the app in a partially applied state.
- [ ] **SCENE-03**: Scene files can preserve the current authored visual setup, including state string fields plus overlay visibility and trail/frustum/body visibility state.

## v2 Requirements

### v0.3 Feature Roadmap

- **OBJ-01**: User can search bodies, spacecraft, and instruments with autocomplete, recents, favorites, bookmarks, filters, and explicit actions.
- **SHOT-01**: User can define, preview, play, and deterministically capture named camera shots and shot sequences.
- **TIME-01**: User can scrub simulation time through a timeline with event markers and script-defined marker groups.
- **GEOM-01**: User can show SPICE-derived measurement labels and geometry readouts for camera range, light time, phase angle, local solar time, altitude, and boresight intercepts.
- **INST-01**: User can inspect instrument boresight lines, intercept markers, angular separation, targeting actions, and per-instrument color controls.
- **MESH-01**: GLB-backed bodies can receive clipped frustums, footprints, retained swaths, and altitude calculations from mesh intersections with ellipsoid fallback.
- **REF-01**: User can toggle and record reference geometry layers such as grids, planes, meridians, terminators, and directional lines.
- **LOD-01**: Render quality presets enforce LOD and update-cadence budgets for heavy visual features.

## Out of Scope

| Feature | Reason |
|---------|--------|
| Full v0.3 feature completion in this milestone | Current priority is reducing mapped risk and building foundations. |
| Untrusted Groovy script sandbox | Requires a separate security design; current scripts are trusted local automation. |
| Complete class-size refactor | Large files are refactored only when a phase needs a specific risk reduction. |
| Server, web, or cloud mode | KEPPLR is a local desktop simulator and tooling app. |
| Replacing Picante/SPICE architecture | Existing scientific ephemeris path is core validated behavior. |

## Traceability

| Requirement | Phase | Status |
|-------------|-------|--------|
| BASE-01 | Phase 1 | Pending |
| BASE-02 | Phase 1 | Pending |
| BASE-03 | Phase 1 | Pending |
| CONF-01 | Phase 2 | Pending |
| CONF-02 | Phase 2 | Pending |
| CONF-03 | Phase 2 | Pending |
| SCR-01 | Phase 2 | Pending |
| SCR-02 | Phase 2 | Pending |
| SCR-03 | Phase 2 | Pending |
| REND-01 | Phase 3 | Pending |
| REND-02 | Phase 3 | Pending |
| REND-03 | Phase 3 | Pending |
| REND-04 | Phase 3 | Pending |
| DATA-01 | Phase 4 | Pending |
| DATA-02 | Phase 4 | Pending |
| DATA-03 | Phase 4 | Pending |
| REPRO-01 | Phase 5 | Pending |
| REPRO-02 | Phase 5 | Pending |
| REPRO-03 | Phase 5 | Pending |
| SCENE-01 | Phase 6 | Pending |
| SCENE-02 | Phase 6 | Pending |
| SCENE-03 | Phase 6 | Pending |

**Coverage:**
- v1 requirements: 22 total
- Mapped to phases: 22
- Unmapped: 0

---
*Requirements defined: 2026-04-26*
*Last updated: 2026-04-26 after initialization*
