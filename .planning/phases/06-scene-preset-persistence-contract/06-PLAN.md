---
phase: 6
plan: 01
type: std
autonomous: true
depends_on: []
---

# Plan: Scene Preset Persistence Contract

## Objective

Implement the first atomic `.kepplrscene` file format with versioning, JSON structure, validation, and full visual setup preservation per requirements SCENE-01, SCENE-02, SCENE-03.

## Context

Current codebase has `StateSnapshot` (core simulation state) and `StateSnapshotCodec` (compact binary Base64url format). Need to add:
- Human-readable JSON format with versioning
- Field-specific validation with error reporting
- Atomic load/apply behavior
- Full overlay state (labels, trails, trail durations, trail references, vectors, frustums, HUD, body visibility)
- Render quality and window size
- Backward compatibility with existing `getStateString()` / `setStateString()`

## Tasks

### Task 1: ScenePreset Record and Capture (auto)

**Implementation:** Create `ScenePreset.java` record that holds all scene state:

```java
public record ScenePreset(
    int version,
    String et,
    double timeRate,
    boolean paused,
    double[] camPosJ2000,
    float[] camOrientJ2000,
    CameraFrame cameraFrame,
    int focusedBodyId,
    int targetedBodyId,
    int selectedBodyId,
    double fovDeg,
    RenderQuality renderQuality,
    int windowWidth,
    int windowHeight,
    Map<Integer, Boolean> bodyVisibility,        // naifId -> visible
    Map<Integer, Boolean> labelVisibility,      // naifId -> visible
    Map<Integer, Boolean> trailVisibility,     // naifId -> visible
    Map<Integer, Double> trailDurations,       // naifId -> duration seconds, -1 = default
    Map<Integer, Integer> trailReferenceBodies,// naifId -> reference naifId, -1 = auto
    Map<String, Boolean> vectorVisibility,    // "naifId:type" -> visible
    Map<String, Boolean> frustumVisibility,    // instrument name -> visible
    boolean hudTimeVisible,
    boolean hudInfoVisible)
```

Add static `capture(SimulationState)` method that reads all state from `SimulationState` interface and produces a `ScenePreset`.

### Task 2: ScenePresetCodec JSON Encoding (auto)

**Implementation:** Create `ScenePresetCodec.java` with:

- `encode(ScenePreset)` → JSON string with version field
- `decode(String)` → ScenePreset, handles unknown fields gracefully
- `saveToFile(ScenePreset, Path)` — writes pretty-printed JSON
- `loadFromFile(Path)` — reads JSON, validates version
- Version 1 format documented in JavaDoc

### Task 3: ScenePresetValidator (auto)

**Implementation:** Create `ScenePresetValidator.java` with:

- `validate(ScenePreset)` → list of `ValidationError` (field, message, severity)
- `ValidationError` record: `field`, `message`, `severity` (ERROR/WARNING)
- Validations:
  - ET must be valid TDB seconds (non-negative, reasonable range)
  - timeRate must be non-negative
  - fovDeg must be in [1, 180]
  - windowWidth/Height must be positive
  - cameraFrame must be valid enum
  - bodyVisibility keys must be valid NAIF IDs
  - renderQuality must be valid enum

### Task 4: SimulationCommands Interface Extensions (auto)

**Implementation:** Add to `SimulationCommands.java`:

```java
/** Save current scene to a .kepplrscene file */
void saveScenePreset(String path) throws IOException;

/** Load and apply a .kepplrscene file atomically */
void loadScenePreset(String path) throws IOException, IllegalArgumentException;

/** Get current scene preset as ScenePreset */
ScenePreset getScenePreset();
```

### Task 5: DefaultSimulationCommands Implementation (auto)

**Implementation:** In `DefaultSimulationCommands.java`:

- Implement `saveScenePreset(path)` — captures current state, validates, writes JSON to file
- Implement `loadScenePreset(path)` — reads JSON, validates all fields, if ANY validation error: throw exception WITHOUT applying any state; if valid: apply all state atomically (use latches to ensure all JME thread updates complete before returning)
- Implement `getScenePreset()` — delegate to `ScenePreset.capture(state)`

Key: Load must be atomic — collect all validation errors first, then only apply if NONE. Do NOT partially apply state on validation failure.

### Task 6: UI Integration - File Menu (auto)

**Implementation:** In `KepplrStatusWindow.java`:

- Add "Save Scene..." menu item under File menu
- Add "Load Scene..." menu item under File menu
- Use JavaFX FileChooser with `.kepplrscene` extension filter
- On save: capture current scene, write to selected file
- On load: read file, validate, apply if valid; show error dialog on validation failure

### Task 7: Scripting API Exposure (auto)

**Implementation:** In `KepplrScript.java`:

- Expose `kepplr.saveScene(path)` that calls `commands.saveScenePreset(path)`
- Expose `kepplr.loadScene(path)` that calls `commands.loadScenePreset(path)`
- Document in script comments

### Task 8: Unit Tests (auto)

**Implementation:** Create tests:

- `ScenePresetCodecTest.java`: round-trip encode/decode, file save/load, unknown field handling
- `ScenePresetValidatorTest.java`: error detection, severity levels, edge cases
- `ScenePresetCaptureTest.java`: capture from SimulationState produces valid preset
- Expand `DefaultSimulationCommandsTest.java` with scene save/load tests

### Task 9: Documentation (auto)

**Implementation:** Add section to `doc/usersguide.rst`:

- `.kepplrscene` file format description (JSON, version, fields)
- How to save/load scene from UI (File menu)
- How to save/load scene from scripts (`kepplr.saveScene()`, `kepplr.loadScene()`)
- Validation error interpretation
- Backward compatibility note (compact state strings still work)

### Task 10: Spotless and Test Verification (auto)

**Run:** `mvn spotless:check` and `mvn test`

Verify all code formatted and tests pass.

## Verification

### SCENE-01 Verification
- [ ] JSON format is versioned (version field in JSON)
- [ ] JSON is readable (pretty-printed, human-readable keys)
- [ ] Format is documented in JavaDoc and usersguide.rst
- [ ] Unknown fields are ignored (test with extra fields in JSON)
- [ ] Validation errors are field-specific

### SCENE-02 Verification
- [ ] Invalid scene file throws exception without partial state application
- [ ] Existing app state is preserved when load fails validation
- [ ] All-or-nothing atomic apply on successful load

### SCENE-03 Verification
- [ ] Scene file preserves: time (ET), timeRate, paused
- [ ] Scene file preserves: camera position, orientation, frame, FOV
- [ ] Scene file preserves: selected/focused/targeted bodies
- [ ] Scene file preserves: render quality
- [ ] Scene file preserves: window size
- [ ] Scene file preserves: body visibility (per-body)
- [ ] Scene file preserves: label visibility (per-body)
- [ ] Scene file preserves: trail visibility, durations, references (per-body)
- [ ] Scene file preserves: vector visibility (per-body + type)
- [ ] Scene file preserves: frustum visibility (per-instrument)
- [ ] Scene file preserves: HUD time/info visibility

### Success Criteria
1. `.kepplrscene` JSON format is versioned, readable, and documented
2. Scene validation reports field-specific errors and preserves existing app state when validation fails
3. Scene load/apply restores time, camera, selected/focused/targeted bodies, render quality, window size, body visibility, labels, trails, trail durations, trail references, vectors, frustums, HUD visibility, and current supported overlay state
4. Unknown future fields are ignored or preserved according to a documented policy
5. Compact state strings remain supported for quick bookmarks (`getStateString()` / `setStateString()`)
6. `mvn test` passes with no new failures
7. `mvn spotless:check` passes