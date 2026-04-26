# Phase 5 Context: Replay, Manifest, and Performance Foundations

**Created:** 2026-04-26

## Phase Context

Phase 5 builds on Phase 4 (Data Tooling). The goal is to establish traceability, deterministic expectations, and telemetry before shot/keyframe, mesh, and reference-layer features add more moving parts.

## Requirements

| Requirement | Description |
|-------------|------------|
| REPRO-01 | Capture output can include a render manifest with app version, platform, config/kernel identity, script or scene identity, render quality, resolution, frame count, and ET per frame. |
| REPRO-02 | Deterministic replay expectations and numeric tolerances are documented for camera pose, ET progression, capture timing, and platform limits. |
| REPRO-03 | Lightweight telemetry records frame time and key scene counts without adding heavy runtime overhead. |

## Current Implementation State

### CaptureService (src/main/java/kepplr/core/CaptureService.java)

**Current capture_info.json fields:**
- startEt, etStep, frameCount, startFrameIndex
- width, height (from first PNG)
- captureTimestamp

**Missing for REPRO-01:**
- appVersion
- platform (OS, arch)
- config identity
- kernel identity
- script/scene identity
- render quality

### AppVersion (src/main/java/kepplr/util/AppVersion.java)

- Already exists with getVersion(), getBuildTime()
- Missing: platform info, git commit

### RenderQuality (src/main/java/kepplr/render/RenderQuality.java)

- Already exists with tier enum (LOW, MEDIUM, HIGH)
- Has maxOccluders(), isHighDetail(), getAntialiasingSamples()

## Gray Areas

### REPRO-01: Manifest scope
- What fields are essential vs nice-to-have?
- Should manifest be JSON or a companion .txt file?
- How to get config/kernel identity from KEPPLRConfiguration?

### REPRO-02: Deterministic tolerances
- What specific numeric tolerances are reasonable?
- How to document platform-specific limits?

### REPRO-03: Telemetry implementation
- Where to record frame time - in KepplrApp update loop?
- What scene counts are meaningful?
- How to avoid heavy runtime overhead?

## Likely Files to Modify

1. `CaptureService.java` - add manifest fields
2. `AppVersion.java` - add platform info
3. `AppVersion.java` - add getPlatform()
4. `CaptureServiceTest.java` - add manifest tests
5. New doc section in `doc/scripting_examples.rst`