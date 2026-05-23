---
phase: 05-replay-manifest-and-performance
plan: 05-01
subsystem: capture-telemetry
tags: [repro, manifest, telemetry]
dependency_graph:
  requires: []
  provides:
    - capture_info.json extended fields
    - frame time telemetry API
    - deterministic tolerance documentation
  affects:
    - CaptureService
    - SimulationState
    - KepplrApp
tech_stack:
  - Java 21
  - JMonkeyEngine
  - JavaFX
key_files:
  created: []
  modified:
    - src/main/java/kepplr/core/CaptureService.java
    - src/main/java/kepplr/util/AppVersion.java
    - src/main/bash/createVersionFile.bash
    - src/main/java/kepplr/state/SimulationState.java
    - src/main/java/kepplr/state/DefaultSimulationState.java
    - src/main/java/kepplr/render/KepplrApp.java
    - doc/scripting_examples.rst
decisions:
  - Extended capture_info.json with appVersion, platform, configIdentity, kernelIdentity, renderQuality
  - Frame time telemetry via System.nanoTime() at start/end of simpleUpdate()
  - Platform description using System.getProperty() for os.name, os.arch, java.version
metrics:
  duration_minutes: 8
  tasks_completed: 3
  files_modified: 11
---

# Phase 05 Plan 05-01: Replay, Manifest, and Performance Summary

## Objective

Implemented REPRO-01 (render manifest), REPRO-02 (deterministic documentation), and REPRO-03 (telemetry).

## One-Liner

Extended capture manifest with version/platform/config/kernel identity, documented deterministic tolerances, and added lightweight frame time telemetry.

## Completed Tasks

| Task | Name | Files Modified | Commit |
|------|------|-------------|--------|
| 1 | Extend manifest fields (REPRO-01) | CaptureService.java, AppVersion.java, CaptureServiceTest.java | 74-gsd-roadmap/25df616 |
| 2 | Document deterministic tolerances (REPRO-02) | doc/scripting_examples.rst | 74-gsd-roadmap/8ebd01a |
| 3 | Add frame time telemetry (REPRO-03) | SimulationState.java, DefaultSimulationState.java, KepplrApp.java | 74-gsd-roadmap/121a663 |

## Changes Made

### Task 1: Manifest Fields (REPRO-01)

- Added `appVersion` from `AppVersion.getVersionString()` (e.g., "KEPPLR version 2026.04.26-134bc49M")
- Added `platform` from new `AppVersion.getPlatform()` (e.g., "Linux/aarch64 (Java 21)")
- Added `configIdentity` using resources folder path from configuration
- Added `kernelIdentity` from metakernel path (`spiceBlock().metakernel().get(0)`)
- Added `renderQuality` from `state.renderQualityProperty().get().name()`
- Updated `createVersionFile.bash` to include getPlatform() method in generated source

### Task 2: Tolerance Documentation (REPRO-02)

Added deterministic tolerances section to `doc/scripting_examples.rst`:
- **Camera Position**: ±1 km for positions under 10,000 km; ±0.01% for greater distances
- **Camera Rotation**: ±0.1 degrees
- **ET Progression**: ±0.1 seconds (ET is TDB)
- **Frame Timing**: ±50ms desktop, ±200ms headless/CI
- **Platform Limits**: Full fidelity on all supported platforms (Linux x86_64, macOS Intel, macOS Apple Silicon, Windows, Linux aarch64)

### Task 3: Frame Time Telemetry (REPRO-03)

- Added `frameTimeMsProperty()` to SimulationState interface with documentation
- Added `frameTimeMs` SimpleDoubleProperty (initially 0.0) in DefaultSimulationState
- Added `setFrameTimeMs(double ms)` setter
- Modified `KepplrApp.simpleUpdate()` to record frame time:
  ```java
  long frameStartNanos = System.nanoTime();
  // ... update logic ...
  long frameEndNanos = System.nanoTime();
  double frameTimeMs = (frameEndNanos - frameStartNanos) / 1_000_000.0;
  simulationState.setFrameTimeMs(frameTimeMs);
  ```

## Deviations from Plan

None - plan executed exactly as written.

## Auth Gates

None.

## Known Stubs

None.

## Threat Flags

None.

## Test Results

- CaptureServiceTest: 10 tests passed
- Compilation successful
- Build: SUCCESS

## Self-Check

- [x] Extended fields added to capture_info.json
- [x] Platform getter added to AppVersion
- [x] Frame time telemetry in SimulationState
- [x] Tolerance documentation in scripting_examples.rst
- [x] Tests pass

## Commits

- 25df616: feat(05-01): add extended manifest fields to capture_info.json (REPRO-01)
- 8ebd01a: docs(05-01): document deterministic tolerances for replay (REPRO-02)
- 121a663: feat(05-01): add frame time telemetry to SimulationState (REPRO-03)
- f4a80e1: fix: update GaiaCatalogTest to use Java 21 Files API

---
*Plan completed: 2026-04-26*