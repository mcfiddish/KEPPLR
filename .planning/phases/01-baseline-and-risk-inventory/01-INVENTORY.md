# Phase 1 Baseline and Risk Inventory

## Scope

This inventory converts the highest-priority concerns from `.planning/codebase/CONCERNS.md` into traceable work packets for the current stabilization milestone. Phase 1 records baseline health, concern traceability, representative touchpoints, and boundaries. It does not fix production code, run live render tests, refactor large classes, or start v0.3 feature implementation.

Priority is risk first, with v0.3 dependency used as a tie-breaker. The approved disposition labels are `Must fix`, `Should fix`, `Can defer`, and `Out of scope`.

## Concern Trace Matrix

| Concern | Evidence Path | Requirement ID | Subsystem | Severity | v0.3 Dependency | Recommended Disposition |
|---------|---------------|----------------|-----------|----------|-----------------|-------------------------|
| configuration load can terminate the process for missing config paths | `.planning/codebase/CONCERNS.md`; `src/main/java/kepplr/config/KEPPLRConfiguration.java` | CONF-01 | Configuration/reload | High | Blocks safer launch, reload, and later scene/apply workflows | Must fix |
| shared temp resource extraction under `java.io.tmpdir/resources` can collide across instances/tests | `.planning/codebase/CONCERNS.md`; `src/main/java/kepplr/config/KEPPLRConfiguration.java`; `src/main/java/kepplr/util/ResourceUtils.java` | CONF-02 | Configuration/reload | High | Blocks reproducible packaging, reload testing, and multi-instance confidence | Must fix |
| configuration reload and singleton/thread-local ephemeris interactions are under-tested | `.planning/codebase/CONCERNS.md`; `src/main/java/kepplr/config/KEPPLRConfiguration.java`; `src/main/java/kepplr/ephemeris/KEPPLREphemeris.java` | CONF-03 | Configuration/reload | High | Blocks safe reload, script/render interaction, and Phase 2 hardening | Must fix |
| Groovy scripts are unrestricted trusted local code but user documentation does not fully surface the trust boundary | `.planning/codebase/CONCERNS.md`; `src/main/java/kepplr/scripting/ScriptRunner.java`; `src/main/java/kepplr/scripting/KepplrScript.java`; `doc/scripting.rst` | SCR-01 | Scripting | High | Blocks safe script sharing expectations before richer automation | Must fix |
| script stop/replacement is cooperative and interruption behavior needs focused tests around KEPPLR wait primitives | `.planning/codebase/CONCERNS.md`; `src/main/java/kepplr/scripting/ScriptRunner.java`; `src/test/java/kepplr/scripting/ScriptRunnerTest.java` | SCR-02 | Scripting | Medium | Blocks predictable automation and replay/capture scripting flows | Should fix |
| command recording can silently miss newly touched `SimulationCommands` methods if not covered per change | `.planning/REQUIREMENTS.md`; `src/main/java/kepplr/commands/SimulationCommands.java`; `src/main/java/kepplr/scripting/CommandRecorder.java` | SCR-03 | Scripting/commands | Medium | Blocks replay and script-generated workflow reproducibility | Should fix |
| live render-path and retained layering behavior are not exercised by current CI | `.planning/codebase/CONCERNS.md`; `.planning/codebase/TESTING.md`; `pom.xml`; `src/test/java/kepplr/render/` | REND-01 | Rendering/test infrastructure | High | Blocks GLB, shadow, mesh, frustum, retained-swath, and visual regression work | Must fix |
| eclipse/shadow quality policy has fixed occluder limits without measurable tier behavior | `.planning/codebase/CONCERNS.md`; `src/main/java/kepplr/render/body/EclipseShadowManager.java`; `src/main/resources/kepplr/shaders/Bodies/EclipseLighting.frag`; `src/main/java/kepplr/render/RenderQuality.java` | REND-02 | Rendering | Medium | Blocks scientifically credible shadow expansion and GLB shape-shadow work | Should fix |
| GLB material, lighting, and fallback behavior lack automated or documented manual verification coverage | `.planning/codebase/CONCERNS.md`; `src/main/java/kepplr/render/body/BodyNodeFactory.java`; `src/main/java/kepplr/render/util/GLTFUtils.java`; `src/main/java/kepplr/apps/GlbModelViewer.java` | REND-03 | Rendering/model assets | Medium | Blocks v0.3 mesh, spacecraft, and visual fidelity work | Should fix |
| wide-cone star tile lookup is not tested near or beyond the documented convexity boundary | `.planning/codebase/CONCERNS.md`; `src/main/java/kepplr/stars/catalogs/tiled/uniform/UniformTileLocator.java`; `src/test/java/kepplr/stars/catalogs/tiled/uniform/UniformTileLocatorTest.java` | REND-04 | Stars/catalogs | Medium | Blocks large-FOV star density and reference-layer confidence | Should fix |
| Gaia source-id lookup fails when optional source index is missing and needs clearer validation/error behavior | `.planning/codebase/CONCERNS.md`; `src/main/java/kepplr/stars/catalogs/gaia/GaiaCatalog.java` | DATA-01 | Data/catalogs | Medium | Blocks robust external Gaia pack usage | Should fix |
| Gaia tile cache is bounded by tile count rather than measured memory cost | `.planning/codebase/CONCERNS.md`; `src/main/java/kepplr/stars/catalogs/gaia/GaiaCatalog.java` | DATA-02 | Data/catalogs | Medium | Blocks scaling confidence for dense catalogs | Should fix |
| model conversion tooling lacks fixture tests for pure parsing and metadata behavior that do not require Blender | `.planning/codebase/CONCERNS.md`; `src/main/python/apps/convert_to_normalized_glb.py`; `src/main/python/README.md` | DATA-03 | Python/model tooling | Medium | Blocks reliable asset conversion for future GLB/mesh work | Should fix |
| render manifest fields are not yet available for reproducible capture outputs | `.planning/REQUIREMENTS.md`; `.planning/codebase/CONCERNS.md`; `src/main/java/kepplr/core/CaptureService.java`; `src/main/java/kepplr/util/AppVersion.java` | REPRO-01 | Reproducibility/capture | Medium | Blocks deterministic capture, visual regression, and shot export foundations | Should fix |
| deterministic replay expectations and numeric tolerances are not formally documented | `.planning/codebase/CONCERNS.md`; `.planning/REQUIREMENTS.md`; `src/main/java/kepplr/state/StateSnapshotCodec.java`; `src/main/java/kepplr/core/SimulationClock.java` | REPRO-02 | Reproducibility/state | Medium | Blocks cross-platform replay confidence for shots and scenes | Should fix |
| lightweight performance telemetry is missing for frame time and key scene counts | `.planning/REQUIREMENTS.md`; `.planning/codebase/CONCERNS.md`; `src/main/java/kepplr/render/KepplrApp.java`; `src/main/java/kepplr/render/RenderQuality.java` | REPRO-03 | Reproducibility/performance | Medium | Blocks quality budgets for LOD, visual layers, and heavy v0.3 rendering | Should fix |
| large classes combine many responsibilities and should not be refactored without phase-specific seams | `.planning/codebase/CONCERNS.md`; `src/main/java/kepplr/ui/KepplrStatusWindow.java`; `src/main/java/kepplr/render/InstrumentFrustumManager.java`; `src/main/java/kepplr/apps/GlbModelViewer.java`; `src/main/java/kepplr/scripting/KepplrScript.java` | BASE-03 | Architecture/maintainability | Medium | Affects review cost for all later phases, but broad rewrites are not required now | Can defer |
| v0.3 feature work such as object search, shots, timeline, geometry readouts, boresight tools, mesh intersections, and reference layers is outside Phase 1 | `.planning/ROADMAP.md`; `.claude/KEPPLR_Roadmap.md` | v2 roadmap | Feature roadmap | Low | Planned future value, not a Phase 1 stabilization deliverable | Out of scope |

## Prioritized Task List

### Must fix

- configuration load can terminate the process for missing config paths; subsystem: configuration/reload; requirement: CONF-01; priority reason: process exit prevents structured error handling and safe launcher behavior; likely future phase: Phase 2.
- shared temp resource extraction under `java.io.tmpdir/resources`; subsystem: configuration/reload; requirement: CONF-02; priority reason: shared paths can make tests and concurrent desktop instances interfere with each other; likely future phase: Phase 2.
- configuration reload and singleton/thread-local ephemeris interactions; subsystem: configuration/reload; requirement: CONF-03; priority reason: global state plus thread-local ephemeris is a high-risk boundary for reload, scripts, and render managers; likely future phase: Phase 2.
- unrestricted Groovy scripting trust boundary documentation; subsystem: scripting; requirement: SCR-01; priority reason: users need explicit trusted-code semantics before more automation is built; likely future phase: Phase 2.
- live render-path and layering test gap; subsystem: rendering/test infrastructure; requirement: REND-01; priority reason: visual regressions can pass unit tests while GLB, shadow, frustum, and retained-layer behavior changes; likely future phase: Phase 3.

### Should fix

- script stop/replacement interruption tests; subsystem: scripting; requirement: SCR-02; priority reason: cooperative cancellation must be predictable for script-driven captures and waits; likely future phase: Phase 2.
- command recording coverage for newly touched commands; subsystem: scripting/commands; requirement: SCR-03; priority reason: replay and recorded workflows depend on the command surface staying executable; likely future phase: Phase 2 and any later command-changing phase.
- eclipse/shadow quality policy; subsystem: rendering; requirement: REND-02; priority reason: shader occluder limits need measurable behavior before shadow expansion; likely future phase: Phase 3.
- GLB material, lighting, and fallback verification; subsystem: rendering/model assets; requirement: REND-03; priority reason: visual fidelity changes need a regression or manual verification path; likely future phase: Phase 3.
- wide-cone star tile lookup tests; subsystem: stars/catalogs; requirement: REND-04; priority reason: large-FOV catalog queries can be wrong near the documented boundary; likely future phase: Phase 3.
- Gaia missing source-index behavior; subsystem: data/catalogs; requirement: DATA-01; priority reason: external pack failures should be clear and test-covered; likely future phase: Phase 4.
- Gaia memory-aware cache criteria; subsystem: data/catalogs; requirement: DATA-02; priority reason: tile count is not enough to bound dense catalog memory use; likely future phase: Phase 4.
- model conversion fixture tests; subsystem: Python/model tooling; requirement: DATA-03; priority reason: pure parsing and metadata behavior can regress without Blender-visible tests; likely future phase: Phase 4.
- render manifest foundation; subsystem: reproducibility/capture; requirement: REPRO-01; priority reason: capture artifacts need enough identity metadata for later replay and visual comparisons; likely future phase: Phase 5.
- deterministic replay tolerances; subsystem: reproducibility/state; requirement: REPRO-02; priority reason: capture, shots, and scenes need explicit numeric expectations; likely future phase: Phase 5.
- lightweight performance telemetry; subsystem: reproducibility/performance; requirement: REPRO-03; priority reason: quality budgets need frame-time and scene-count evidence; likely future phase: Phase 5.

### Can defer

- large-class extraction; subsystem: architecture/maintainability; requirement: BASE-03 traceability only; priority reason: broad refactors raise regression risk unless tied to a specific phase requirement; likely future phase: opportunistic within Phases 2-6 when a focused seam is needed.
- star tile API escape hatch deprecation; subsystem: stars/catalogs; requirement: none in v1; priority reason: it affects future implementation cleanliness but does not directly block the milestone; likely future phase: after REND-04 or DATA work touches catalog internals.
- full overlay state snapshot expansion; subsystem: state/reproducibility; requirement: SCENE-03 later; priority reason: needed for scene files, but Phase 6 owns the scene contract; likely future phase: Phase 6.

### Out of scope

- untrusted Groovy sandbox; subsystem: scripting/security; requirement: out of scope; priority reason: current project policy is trusted local scripts, and sandboxing requires a separate security design; likely future phase: none in current milestone.
- full v0.3 feature delivery; subsystem: feature roadmap; requirement: v2 roadmap; priority reason: Phase 1 only prepares stabilization traceability; likely future phase: after this stabilization/foundation milestone.
- replacing Picante/SPICE architecture; subsystem: ephemeris; requirement: out of scope; priority reason: SPICE/Picante correctness is core product value; likely future phase: none.

## Representative Touchpoints

| Subsystem | Package Area | Representative Files | Confidence | Notes |
|-----------|--------------|----------------------|------------|-------|
| Configuration/reload | `src/main/java/kepplr/config`, `src/main/java/kepplr/util` | `src/main/java/kepplr/config/KEPPLRConfiguration.java`, `src/main/java/kepplr/util/ResourceUtils.java` | High | Directly cited by process-exit, temp extraction, singleton, and thread-local ephemeris concerns. |
| Scripting | `src/main/java/kepplr/scripting`, `doc/scripting.rst` | `src/main/java/kepplr/scripting/ScriptRunner.java`, `src/main/java/kepplr/scripting/KepplrScript.java`, `src/main/java/kepplr/scripting/CommandRecorder.java`, `doc/scripting.rst` | High | Directly cited by trusted-code documentation, cooperative stop, wait primitive, and command recording concerns. |
| Rendering | `src/main/java/kepplr/render`, `src/main/java/kepplr/render/body`, `src/main/resources/kepplr/shaders` | `src/main/java/kepplr/render/KepplrApp.java`, `src/main/java/kepplr/render/body/EclipseShadowManager.java`, `src/main/java/kepplr/render/body/BodyNodeFactory.java`, `src/main/resources/kepplr/shaders/Bodies/EclipseLighting.frag` | High | Directly cited by render-path, shadow policy, GLB, and visual fidelity concerns. |
| Stars/catalogs | `src/main/java/kepplr/stars`, `src/main/java/kepplr/stars/catalogs` | `src/main/java/kepplr/stars/catalogs/tiled/uniform/UniformTileLocator.java`, `src/main/java/kepplr/stars/catalogs/gaia/GaiaCatalog.java`, `src/main/java/kepplr/stars/TileSet.java` | High | Directly cited by wide-cone lookup, Gaia source-index, Gaia cache, and raw tile API concerns. |
| Python/model tooling | `src/main/python/apps`, `src/main/python` | `src/main/python/apps/convert_to_normalized_glb.py`, `src/main/python/README.md` | High | Directly cited by model conversion fixture-test and external tool behavior concerns. |
| Tests | `src/test/java/kepplr`, `src/test/resources` | `src/test/java/kepplr/render`, `src/test/java/kepplr/config`, `src/test/java/kepplr/scripting`, `src/test/java/kepplr/stars`, `src/test/resources/spice` | Medium | Existing tests are broad, but render-tagged and cross-thread/reload cases need targeted expansion. |
| UI/control surface | `src/main/java/kepplr/ui` | `src/main/java/kepplr/ui/KepplrStatusWindow.java`, `src/main/java/kepplr/ui/SimulationStateFxBridge.java`, `src/main/java/kepplr/ui/FxDispatch.java` | Medium | Relevant to command routing and reload/script interactions, but Phase 1 does not prescribe UI implementation changes. |
| Capture/reproducibility | `src/main/java/kepplr/core`, `src/main/java/kepplr/state`, `src/main/java/kepplr/util` | `src/main/java/kepplr/core/CaptureService.java`, `src/main/java/kepplr/state/StateSnapshotCodec.java`, `src/main/java/kepplr/util/AppVersion.java` | Medium | Relevant to manifest and replay foundations; exact edits should be planned in Phase 5. |

## Do Not Touch Yet

- Do not run live render execution or change render harness behavior in Phase 1 unless needed only to document the gap.
- Do not make production refactors in large classes such as `KepplrStatusWindow`, `InstrumentFrustumManager`, `SaturnRingManager`, `GlbModelViewer`, or `KepplrScript` during Phase 1.
- Do not implement v0.3 features in Phase 1. Out-of-scope v0.3 features include object search, shot/keyframes, scene files, timeline, geometry readouts, boresight tools, mesh intersections, and reference layers.
- Do not change SPICE/Picante ephemeris architecture while building this inventory.
- Do not fix baseline command failures inside Phase 1; record them as baseline debt.

## Candidate Seams for Later Refactor

- `KepplrStatusWindow`: split menu/control sections only when a UI phase needs a smaller edit surface.
- `InstrumentFrustumManager`: separate persistent coverage overlay, footprint rendering, and update orchestration if Phase 3 render work touches those paths.
- `SaturnRingManager`: isolate ring geometry and shadow/material concerns only if shadow quality changes require it.
- `GlbModelViewer`: separate viewer UI, material inspection, and conversion/debug helpers if GLB verification needs focused tool support.
- `KepplrScript`: group command categories or delegate documentation helpers when scripting phases touch the public script API.
- `convert_to_normalized_glb.py`: extract pure parsing and metadata helpers before adding Blender-free fixture tests.
- `GaiaCatalog`: isolate source-index validation and cache accounting before expanding Gaia user-facing behavior.

## Deferred and Out-of-Scope Items

- Major rewrites, class-size cleanup, and architecture replacement are deferred unless a later phase links them to a specific requirement.
- Full untrusted script sandboxing is out of scope for the current milestone; current policy remains trusted local scripts.
- Scene files, search, shots, timeline, mesh intersections, boresight tooling, and reference layers remain v0.3 or later feature work.
- Full render-golden visual regression is deferred until Phase 3 defines the render smoke/manual verification path.

## Source Notes

- Primary concern source: `.planning/codebase/CONCERNS.md`.
- Test command and render-flow source: `.planning/codebase/TESTING.md` and `pom.xml`.
- Requirement IDs: `.planning/REQUIREMENTS.md`.
- Phase ordering and deferral: `.planning/ROADMAP.md` and `.claude/KEPPLR_Roadmap.md`.
- Phase decisions: `.planning/phases/01-baseline-and-risk-inventory/01-CONTEXT.md`.
