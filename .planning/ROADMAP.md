# Roadmap: KEPPLR Stabilization and v0.3 Foundations

**Created:** 2026-04-26
**Granularity:** Coarse
**Source Context:** `.planning/codebase/CONCERNS.md`, `.planning/codebase/ARCHITECTURE.md`, `.planning/codebase/TESTING.md`, `.claude/KEPPLR_Roadmap.md`

## Overview

This roadmap prioritizes the concerns surfaced by codebase mapping, then lays the first foundations for the v0.3 work described in `.claude/KEPPLR_Roadmap.md`. The order is intentionally conservative: first establish a reliable baseline, then reduce reload/script/render/data risks, then add reproducibility and scene-persistence foundations before larger user-facing features.

| # | Phase | Goal | Requirements | Success Criteria |
|---|-------|------|--------------|------------------|
| 1 | Baseline and Risk Inventory | Establish a clean test/status baseline and convert concerns into actionable work packets. | BASE-01, BASE-02, BASE-03 | 4 |
| 2 | Configuration and Script Lifecycle Hardening | Reduce reload, temp-resource, singleton, ephemeris, and trusted-script risks. | CONF-01, CONF-02, CONF-03, SCR-01, SCR-02, SCR-03 | 5 |
| 3 | Render Reliability and Visual Regression Foundations | Strengthen render-path coverage and measurable quality policy for shadows, GLB rendering, and star lookup. | REND-01, REND-02, REND-03, REND-04 | 5 |
| 4 | Data Tooling and Catalog Robustness | Harden Gaia and model-conversion edge cases with validation, tests, and memory-aware behavior. | DATA-01, DATA-02, DATA-03 | 4 |
| 5 | Replay, Manifest, and Performance Foundations | Add reproducibility metadata, deterministic expectations, and lightweight telemetry before heavy v0.3 features. | REPRO-01, REPRO-02, REPRO-03 | 5 |
| 6 | Scene Preset Persistence Contract | Specify and implement the first atomic `.kepplrscene` load/apply foundation. | SCENE-01, SCENE-02, SCENE-03 | 5 |

## Phase Details

### Phase 1: Baseline and Risk Inventory

**Goal:** Establish the current health of the project and create a traceable stabilization backlog from mapped concerns.

**Requirements:** BASE-01, BASE-02, BASE-03

**Status:** Complete (2026-04-26)

**Success criteria:**
1. `mvn test` result is captured with any pre-existing failures distinguished from new failures.
2. `mvn spotless:check` result is captured or formatting gaps are documented.
3. Each v1 requirement has a short implementation note that points back to one or more concerns in `.planning/codebase/CONCERNS.md`.
4. Phase planning identifies likely file touchpoints before implementation begins.

**UI hint:** no

### Phase 2: Configuration and Script Lifecycle Hardening

**Goal:** Make configuration reload, resource extraction, and scripting lifecycle behavior safer and easier to test.

**Requirements:** CONF-01, CONF-02, CONF-03, SCR-01, SCR-02, SCR-03

**Status:** Complete (2026-04-26)

**Verified:** 6/6 requirements verified via 02-VERIFICATION.md

### Phase 3: Render Reliability and Visual Regression Foundations

**Goal:** Reduce visual-regression risk before expanding GLB, shadow, star, and mesh rendering behavior.

**Requirements:** REND-01, REND-02, REND-03, REND-04

**Status:** Ready to execute

**Success criteria:**
1. Render-tagged Maven flow has at least one meaningful render smoke/focused test, or a documented technical blocker with a narrower replacement test.
2. Shadow/occluder quality policy is explicit and measurable for current `RenderQuality` tiers.
3. GLB material, lighting, and fallback behavior has automated coverage where feasible and a documented manual verification path where rendering must be inspected.
4. Wide-cone star tile lookup has tests at normal, near-boundary, and over-boundary cone sizes.
5. `mvn test` passes with no new failures after changes.

**Likely files:** `pom.xml`, `src/main/java/kepplr/render/body/EclipseShadowManager.java`, `src/main/resources/kepplr/shaders/Bodies/EclipseLighting.frag`, `src/main/java/kepplr/render/body/BodyNodeFactory.java`, `src/main/java/kepplr/render/util/GLTFUtils.java`, `src/main/java/kepplr/stars/catalogs/tiled/uniform/UniformTileLocator.java`, `src/test/java/kepplr/render`, `src/test/java/kepplr/stars`

**Plans:**
- [x] 03-01-PLAN.md — Render test infrastructure and shadow quality policy
- [x] 03-02-PLAN.md — GLB rendering coverage and star tile boundary tests

**UI hint:** no

### Phase 4: Data Tooling and Catalog Robustness

**Goal:** Make external star/catalog/model data failures easier to diagnose and safer to evolve.

**Requirements:** DATA-01, DATA-02, DATA-03

**Success criteria:**
1. Gaia lookup paths report missing optional source indexes clearly and tests cover the behavior.
2. Gaia tile cache cost is measured or bounded with memory-aware criteria, with evidence captured in docs or tests.
3. Pure model-converter parsing and metadata helpers are isolated enough for fixture tests that do not require Blender.
4. Existing CLI/tool behavior remains compatible with current documentation.

**Likely files:** `src/main/java/kepplr/stars/catalogs/gaia/GaiaCatalog.java`, `src/main/java/kepplr/stars/catalogs/gaia/tools`, `src/main/python/apps/convert_to_normalized_glb.py`, `src/main/python/README.md`, `doc/python_tools.rst`, `src/test/java/kepplr/stars`

**UI hint:** no

### Phase 5: Replay, Manifest, and Performance Foundations

**Goal:** Establish traceability, deterministic expectations, and telemetry before shot/keyframe, mesh, and reference-layer features add more moving parts.

**Requirements:** REPRO-01, REPRO-02, REPRO-03

**Success criteria:**
1. Capture output can include a render manifest with app version, platform, config/kernel identity, script or scene identity, render quality, resolution, frame count, and ET per frame.
2. Deterministic replay expectations and numeric tolerances are documented for camera pose, ET progression, capture timing, and platform limits.
3. Lightweight telemetry records frame time and key scene counts without adding heavy runtime overhead.
4. Manifest behavior is covered by deterministic non-render tests where possible.
5. Existing screenshot and capture-sequence APIs remain backward compatible.

**Likely files:** `src/main/java/kepplr/core/CaptureService.java`, `src/main/java/kepplr/render/KepplrApp.java`, `src/main/java/kepplr/util/AppVersion.java`, `src/main/java/kepplr/render/RenderQuality.java`, `src/test/java/kepplr/core`, `doc/scripting_examples.rst`

**UI hint:** no

### Phase 6: Scene Preset Persistence Contract

**Goal:** Add the first durable scene-file foundation for authored visual setups and future shot/keyframe work.

**Requirements:** SCENE-01, SCENE-02, SCENE-03

**Success criteria:**
1. `.kepplrscene` JSON format is versioned, readable, and documented.
2. Scene validation reports field-specific errors and preserves existing app state when validation fails.
3. Scene load/apply restores time, camera, selected/focused/targeted bodies, render quality, window size, body visibility, labels, trails, trail durations, trail references, vectors, frustums, HUD visibility, and current supported overlay state.
4. Unknown future fields are ignored or preserved according to a documented policy.
5. Compact state strings remain supported for quick bookmarks.

**Likely files:** `src/main/java/kepplr/state/StateSnapshot.java`, `src/main/java/kepplr/state/StateSnapshotCodec.java`, `src/main/java/kepplr/commands/SimulationCommands.java`, `src/main/java/kepplr/commands/DefaultSimulationCommands.java`, `src/main/java/kepplr/scripting/KepplrScript.java`, `src/main/java/kepplr/ui/KepplrStatusWindow.java`, `src/test/java/kepplr/state`, `doc/usersguide.rst`

**UI hint:** yes

## Requirement Coverage

All v1 requirements in `.planning/REQUIREMENTS.md` are mapped to exactly one phase.

| Phase | Requirement Count |
|-------|-------------------|
| Phase 1 | 3 |
| Phase 2 | 6 |
| Phase 3 | 4 |
| Phase 4 | 3 |
| Phase 5 | 3 |
| Phase 6 | 3 |

## Deferred Roadmap

After this stabilization/foundation milestone, continue with the remaining v0.3 sequence from `.claude/KEPPLR_Roadmap.md`: object search/bookmarks, shot/keyframe core, camera navigation inertia, timeline, geometry readouts, instrument targeting, mesh intersections, reference geometry layers, and full LOD/update-cadence enforcement.

---
*Roadmap created: 2026-04-26*
