# KEPPLR Implementation Audit

Date: 2026-04-04

Scope:
- `.claude/KEPPLR_Roadmap.md`
- `.claude/DECISIONS.md`
- `.claude/REDESIGN.md`
- Current code and tests in this repository

Method:
- Read the three design/planning documents.
- Verified implementation by tracing each feature area into production classes and unit tests.
- Ran `mvn test`.

Test result:
- `mvn test` passed: 664 tests, 0 failures, 0 errors, 0 skipped.

## Executive Summary

The codebase implements the roadmap through Step 27, plus some later follow-on work that is already present in code, including configurable trail reference bodies, active-frame trail rendering, and per-body visibility toggles.

The major architectural decisions are mostly represented faithfully in code. The strongest coverage is in:
- ephemeris/configuration access
- anchor-based simulation time
- camera frames and transitions
- rendering layers, trails, vectors, labels, HUD, sun halo, rings, and eclipse geometry
- scripting, recording, screenshots, capture sequences, state snapshots, and configuration reload
- GLB shape models and instrument frustums

The main audit findings were document/code drift rather than broad missing functionality. Those drift items have now
been corrected in code and local project documentation:
- non-destroy `Platform.runLater()` usage in `KepplrApp` was removed
- the JavaFX control window now matches the documented non-always-on-top policy
- the JavaFX window now positions to the right of the JME window
- stale synodic-frame comments now use the settled "selected body" semantics
- the roadmap keyboard-shortcut text now matches the current input handler

## Verified Implemented Features

### 1. Core architecture and time model

Implemented and verified:
- Project scaffold and testable Maven layout
- singleton configuration with thread-local ephemeris access
- anchor-based `SimulationClock`
- world-space units in kilometers
- heliocentric J2000 camera pose as canonical representation
- selected / targeted / focused interaction state split

Primary code:
- `src/main/java/kepplr/config/KEPPLRConfiguration.java`
- `src/main/java/kepplr/core/SimulationClock.java`
- `src/main/java/kepplr/state/SimulationState.java`
- `src/main/java/kepplr/state/DefaultSimulationState.java`
- `src/main/java/kepplr/commands/DefaultSimulationCommands.java`

Primary tests:
- `src/test/java/kepplr/config/KEPPLRConfigurationTest.java`
- `src/test/java/kepplr/core/SimulationClockTest.java`
- `src/test/java/kepplr/commands/DefaultSimulationCommandsTest.java`
- `src/test/java/kepplr/state/DefaultSimulationStateTest.java`

Assessment:
- Implemented.
- Decision D-001 is represented correctly in architecture and tests.
- Decision D-002 is mostly represented correctly; the core command/state split is in place.

### 2. Ephemeris and SPICE authority

Implemented and verified:
- heliocentric state queries
- frame transforms
- body-fixed transforms
- light-time support
- spacecraft and instrument lookup
- body name resolution through `BodyLookupService`

Primary code:
- `src/main/java/kepplr/ephemeris/KEPPLREphemeris.java`
- `src/main/java/kepplr/ephemeris/spice/SpiceBundle.java`
- `src/main/java/kepplr/ephemeris/BodyLookupService.java`

Primary tests:
- `src/test/java/kepplr/ephemeris/KEPPLREphemerisTest.java`
- `src/test/java/kepplr/ephemeris/BodyLookupServiceTest.java`
- `src/test/java/kepplr/ephemeris/spice/SpiceBundleTest.java`

Assessment:
- Implemented.
- REDESIGN sections 1, 3, and 6 are represented in code.

### 3. Camera frames, navigation, and transitions

Implemented and verified:
- inertial, body-fixed, and synodic camera frames
- active-frame vs requested-frame separation and fallback state
- screen-relative orbit behavior
- exponential zoom
- non-blocking `pointAt` and `goTo`
- transition cancellation on manual navigation
- camera scripting API from Step 19c
- cinematic commands from Step 24: `truck`, `crane`, `dolly`
- eased transitions

Primary code:
- `src/main/java/kepplr/camera/CameraNavigator.java`
- `src/main/java/kepplr/camera/CameraInputHandler.java`
- `src/main/java/kepplr/camera/BodyFixedFrame.java`
- `src/main/java/kepplr/camera/SynodicFrame.java`
- `src/main/java/kepplr/camera/SynodicFrameApplier.java`
- `src/main/java/kepplr/camera/TransitionController.java`

Primary tests:
- `src/test/java/kepplr/camera/CameraNavigatorTest.java`
- `src/test/java/kepplr/camera/BodyFixedCameraFrameTest.java`
- `src/test/java/kepplr/camera/SynodicFrameTest.java`
- `src/test/java/kepplr/camera/SynodicFrameApplierTest.java`
- `src/test/java/kepplr/camera/CameraTransitionTest.java`
- `src/test/java/kepplr/camera/CinematicTransitionTest.java`
- `src/test/java/kepplr/camera/TransitionControllerBodyFollowingTest.java`

Assessment:
- Implemented.
- Decisions D-005, D-010, D-011, D-012, D-013, D-014, D-023, D-045, D-059, D-063, D-064, D-066, and D-067 are represented in executable code.

### 4. Rendering pipeline

Implemented and verified:
- body rendering
- culling and sprite fallback
- star field with Yale BSC and abstraction layer
- multi-frustum rendering
- Saturn rings
- eclipse / shadow system with quality presets
- Sun halo
- labels, HUD, vector overlays, orbit trails
- trail decluttering and barycenter filtering
- body color use for trails/vectors
- instrument frustum overlays
- body visibility toggles

Primary code:
- `src/main/java/kepplr/render/KepplrApp.java`
- `src/main/java/kepplr/render/body/BodySceneManager.java`
- `src/main/java/kepplr/render/body/BodyNodeFactory.java`
- `src/main/java/kepplr/render/body/EclipseShadowManager.java`
- `src/main/java/kepplr/render/body/SaturnRingManager.java`
- `src/main/java/kepplr/render/SunHaloRenderer.java`
- `src/main/java/kepplr/render/StarFieldManager.java`
- `src/main/java/kepplr/render/trail/TrailManager.java`
- `src/main/java/kepplr/render/trail/TrailSampler.java`
- `src/main/java/kepplr/render/vector/VectorManager.java`
- `src/main/java/kepplr/render/vector/VectorTypes.java`
- `src/main/java/kepplr/render/InstrumentFrustumManager.java`
- `src/main/java/kepplr/render/label/LabelManager.java`

Primary tests:
- `src/test/java/kepplr/render/body/BodyCullerTest.java`
- `src/test/java/kepplr/render/body/CullingRuleTest.java`
- `src/test/java/kepplr/render/body/ShadowGeometryTest.java`
- `src/test/java/kepplr/render/body/SaturnRingGeometryTest.java`
- `src/test/java/kepplr/render/SunHaloRendererTest.java`
- `src/test/java/kepplr/render/StarFieldManagerTest.java`
- `src/test/java/kepplr/render/trail/TrailSamplerTest.java`
- `src/test/java/kepplr/render/trail/TrailManagerTest.java`
- `src/test/java/kepplr/render/vector/VectorManagerTest.java`
- `src/test/java/kepplr/render/vector/VectorTypesTest.java`
- `src/test/java/kepplr/render/frustum/FrustumLayerTest.java`
- `src/test/java/kepplr/render/label/LabelManagerTest.java`
- `src/test/java/kepplr/render/RenderQualityTest.java`

Assessment:
- Implemented.
- Decisions D-003, D-004, D-007, D-018, D-019, D-020, D-021, D-022, D-033, D-040, D-041, D-042, D-046, D-061, and D-062 are represented in code.

### 5. JavaFX UI and FX bridge

Implemented and verified:
- separate JavaFX status/control window
- body readout, status section, searchable tree, context menu
- View / Time / Overlays / Instruments / Window menus
- config reload menu path
- screenshot / capture / copy-state / paste-state / log window menu paths
- standard `MenuItem` actions for reliable menu activation; `CustomMenuItem` only where embedded controls are needed
- bidirectional menu sync with state
- script output area

Primary code:
- `src/main/java/kepplr/ui/KepplrStatusWindow.java`
- `src/main/java/kepplr/ui/SimulationStateFxBridge.java`
- `src/main/java/kepplr/ui/LogWindow.java`
- `src/main/java/kepplr/ui/LogAppender.java`

Primary tests:
- `src/test/java/kepplr/ui/SimulationStateFxBridgeTest.java`

Assessment:
- Implemented.
- Core UI feature set from roadmap Steps 19, 19b, 22, 25, 26, and 27 is present.

### 6. Groovy scripting, recording, screenshots, captures, state snapshots

Implemented and verified:
- `KepplrScript` API with int and string overloads
- `ScriptRunner` on dedicated daemon thread
- `waitRenderFrames`, `waitWall`, `waitSim`, `waitUntilSim`, `waitTransition`
- `CommandRecorder` decorator and script serialization
- `VectorType.toScript()`
- screenshot capture
- capture sequences and `capture_info.json`
- state snapshot strings with binary/base64url encoding
- startup `-script` and `-state`
- script-triggered configuration reload

Notes:
- `waitRenderFrames(int)` is the scripting/render-thread fence for queued scene
  changes that must be visible before capture.
- `waitTransition()` now waits by rendered frames rather than a fixed wall-clock
  sleep, avoiding races where the script thread could outrun a just-queued
  camera transition.

Primary code:
- `src/main/java/kepplr/scripting/KepplrScript.java`
- `src/main/java/kepplr/scripting/ScriptRunner.java`
- `src/main/java/kepplr/scripting/CommandRecorder.java`
- `src/main/java/kepplr/core/CaptureService.java`
- `src/main/java/kepplr/state/StateSnapshot.java`
- `src/main/java/kepplr/state/StateSnapshotCodec.java`
- `src/main/java/kepplr/apps/PngToMovie.java`

Primary tests:
- `src/test/java/kepplr/scripting/KepplrScriptTest.java`
- `src/test/java/kepplr/scripting/ScriptRunnerTest.java`
- `src/test/java/kepplr/scripting/CommandRecorderTest.java`
- `src/test/java/kepplr/core/CaptureServiceTest.java`
- `src/test/java/kepplr/state/StateSnapshotCodecTest.java`
- `src/test/java/kepplr/apps/PngToMovieTest.java`

Assessment:
- Implemented.
- Decisions D-015, D-024, D-025, D-026, D-043, D-044, D-049, D-050, D-051, D-052, D-053, D-056, D-057, and D-058 are represented in code and tests.

### 7. GLB shape models

Implemented and verified:
- GLB loading for bodies and spacecraft
- `modelToBodyFixedQuat` parsing without third-party JSON
- FileLocator registration
- graceful fallback paths
- spacecraft scale conversion
- shape-model hot reload path
- spacecraft FK/body-fixed unification

Primary code:
- `src/main/java/kepplr/render/util/GLTFUtils.java`
- `src/main/java/kepplr/render/body/BodyNodeFactory.java`
- `src/main/java/kepplr/render/body/BodySceneManager.java`

Primary tests:
- `src/test/java/kepplr/render/util/GLTFUtilsTest.java`

Assessment:
- Implemented.
- Decisions D-027, D-028, D-029, D-030, D-031, D-032, and D-055 are represented in code.

## Confirmed Not Yet Implemented

These items remain deferred or only partially addressed. They are either explicitly future work in `REDESIGN.md` or deferred from the roadmap.

Not yet implemented:
- formal determinism / reproducibility guarantees and golden-test tolerances (`REDESIGN.md` 15, 16.1)
- full object discovery UX such as autocomplete, recents, favorites/bookmarks (`REDESIGN.md` 16.3)
- additional overlay types like reference axes, grids, and ring-plane indicators (`REDESIGN.md` 16.4)
- user-facing star density / magnitude controls in the UI (`REDESIGN.md` 16.4)
- richer label types such as distance, light-time, phase angle, and configurable label-priority policies (`REDESIGN.md` 16.5)
- LOD policy and explicit measurable performance acceptance criteria (`REDESIGN.md` 16.7)
- collision / ground avoidance and broader navigation mode specification (`REDESIGN.md` 16.2)
- full-state snapshot extension including overlay visibility, which the roadmap explicitly deferred in Step 26

Assessment:
- These are genuinely not complete in the current codebase.
- None of them appears to be an accidental omission relative to the current executable roadmap through Step 27.

## Findings

No remaining high-signal document/code mismatches were identified in this pass after the follow-up fixes above. The
remaining gaps are the deferred future items listed under "Confirmed Not Yet Implemented", not contradictions between
the audited design documents and the current code.

## Overall Conclusion

Implementation status:
- Roadmap Steps 1 through 27: substantially implemented
- Several post-roadmap follow-ons are also already implemented in code
- Test coverage is broad and currently green

Faithfulness to design decisions:
- Faithful in the audited behavior and architecture
- The main previously identified UI/threading drift items have been corrected

Recommended next cleanup actions:
1. If desired, add a focused test around the new FX dispatch queue and window-position request path.
2. Continue future-work implementation from the deferred items in `REDESIGN.md` section 16.
