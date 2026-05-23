# Phase 3 Discussion: Render Reliability and Visual Regression Foundations

**Created:** 2026-04-26

## Phase Context

Phase 3 builds on the configuration/script hardening from Phase 2. The goal is to reduce visual-regression risk before expanding GLB, shadow, star, and mesh rendering behavior. This addresses REND-01, REND-02, REND-03, REND-04.

## Current Implementation State

### REND-01: Render-path test infrastructure

**Current behavior:** `pom.xml` has render-test infrastructure configured:
- Surefire excludes `render` group: `<excludedGroups>render</excludedGroups>`
- Failsafe runs tests tagged with `render`: configured in pom.xml
- However: No `@Tag("render")` tests exist under `src/test/java`

**Evidence:** Phase 1 baseline confirms: "Render flow: `mvn -Dgroups=render verify` is configured through Failsafe, but no `@Tag("render")` tests were found under `src/test/java`."

**Need:** At least one meaningful render smoke/focused test, or documented technical blocker if live render harness cannot support it.

### REND-02: Eclipse/shadow quality policy

**Current behavior:** `ShadowGeometry.java` has math for shadow calculations (`canCastShadow`, `computeLitFraction`, `computeCombinedLitFraction`). Tests exist in `ShadowGeometryTest.java` — pure unit tests for shadow geometry.

**Missing:**
- Quality policy for occluder limits tied to `RenderQuality` tiers
- Measurable behavior specification for shadow resolution/quality per tier

**Likely file:** `src/main/java/kepplr/render/body/EclipseShadowManager.java` or `src/main/java/kepplr/render/RenderQuality.java`

### REND-03: GLB rendering regression coverage

**Current behavior:** `GLTFUtils.java` parses GLB files for `modelToBodyFixedQuat` from JSON extras. Tests exist in `GLTFUtilsTest.java`:
- Returns identity for missing/bad/corrupt files
- Reads quaternion from minimal extras
- Handles scientific notation, whitespace, malformed numbers

**Missing:**
- Material/lighting regression coverage
- Fallback behavior testing (what happens when GLB fails to load)
- Manual verification path documentation

### REND-04: Wide-cone star tile lookup

**Current behavior:** `UniformTileLocator.java` has `query(vector, angle, receiver, ...)` method. Tests exist in `UniformTileLocatorTest.java`:
- Tests at normal tile size (initializer.getConeAngleAddition())
- Tests at 1/10th size
- Tests at 5x size
- Tests near poles, corners, map edges

**Current state:** Tests already cover "near-boundary and over-boundary cone sizes" per the requirement. However, need to verify:
- Convexity boundary is documented
- What happens when cone exceeds hemisphere (angle > 90°)

## Existing Tests Summary

| Requirement | Test File | Coverage | Gap |
|------------|-----------|----------|-----|
| REND-01 | None | None | No `@Tag("render")` tests |
| REND-02 | ShadowGeometryTest.java | Math only | Quality policy not tied to RenderQuality |
| REND-03 | GLTFUtilsTest.java | Quaternion parsing | No material/lighting/fallback tests |
| REND-04 | UniformTileLocatorTest.java | Near/over boundary | Convexity boundary docs |

## Gray Areas Requiring Research

### Area 1: Render smoke test feasibility
**Research question:** Can we add a simple `@Tag("render")` test, or is there a technical blocker?
- JME requires display/window context
- Headless CI may not support OpenGL
- Need to check if existing tests (like KepplrAppTrailDeclutterTest) actually run JME

### Area 2: Shadow quality policy
**Research question:** What should the quality policy look like?
- Number of occluders per receiver?
- Shadow geometry resolution per RenderQuality tier?
- Distance cutoff for shadow calculations?

### Area 3: GLB fallback behavior
**Research question:** What happens when GLB fails to load in practice?
- Is there fallback to ellipsoid/primitive geometry?
- What gets logged?
- Can we test fallback without a real GPU?

### Area 4: Star tile convexity boundary
**Research question:** What is the documented convexity boundary for the tiling?
- At what angle does the spherical cone geometry break down?
- Is there documentation in Initializer or UniformBandedTiling?

## File Touchpoints

**Likely files to modify:**
1. `pom.xml` — possibly adjust render-test config or document blocker
2. `src/main/java/kepplr/render/body/EclipseShadowManager.java` — quality policy
3. `src/main/java/kepplr/render/RenderQuality.java` — add shadow quality spec
4. `src/main/java/kepplr/render/body/BodyNodeFactory.java` — GLB fallback
5. `src/main/java/kepplr/render/util/GLTFUtils.java` — add fallback behavior
6. `src/main/java/kepplr/stars/catalogs/tiled/uniform/Initializer.java` — document convexity
7. `src/test/java/kepplr/render/` — add @Tag("render") tests or document blocker

## Questions for Discussion

1. **REND-01:** Is there an existing render test we can tag, or should we add a simple smoke test, or document a technical blocker?
2. **REND-02:** What specific measurable behavior should the shadow quality policy have?
3. **REND-03:** What fallback behavior should be tested — logging, primitive fallback, graceful degradation?
4. **REND-04:** Where should the convexity boundary be documented?