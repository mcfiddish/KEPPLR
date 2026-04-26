# Phase 6 Context: Scene Preset Persistence Contract

## Current State

The codebase has `StateSnapshot` (a record capturing core simulation state: ET, time rate, paused, camera position/orientation, camera frame, focused/targeted/selected body IDs, FOV) and `StateSnapshotCodec` (encodes/decodes to compact Base64url binary format). The format is:
- Version 1: 75 bytes total (1 byte version + 74 bytes payload)
- Not human-readable
- No validation layer
- No overlay visibility (labels, trails, vectors, frustums, HUD)
- No body visibility state

`SimulationCommands` provides `getStateString()` and `setStateString()` that use this codec.

## What's Missing (Requirements)

- **SCENE-01**: `.kepplrscene` JSON format — versioned, readable, documented, with validation errors and unknown-field handling
- **SCENE-02**: Atomic load/apply — invalid scenes do not leave the app in a partially applied state
- **SCENE-03**: Full visual setup preservation — state string fields + overlay visibility + body visibility + trail durations + trail references + render quality + window size

## Scope

### Must Implement
1. New `ScenePreset` record to hold all scene state (time, camera, bodies, overlays, render quality, window)
2. New `ScenePresetCodec` for JSON encoding/decoding with versioning
3. New `ScenePresetValidator` for field-specific validation with error reporting
4. New methods in `SimulationCommands` interface: `saveScenePreset(Path)`, `loadScenePreset(Path)`, `getScenePreset()`
5. `DefaultSimulationCommands` implementation of scene save/load
6. UI integration in `KepplrStatusWindow` (File → Save Scene / Load Scene menus)
7. Documentation in `doc/usersguide.rst`
8. Unit tests for codec, validator, and commands

### Backward Compatibility
- `getStateString()` / `setStateString()` must continue to work as before (compact binary format)
- Scene preset files use JSON format (`.kepplrscene` extension)

### Not In Scope
- Scene preset export/import in scripting API beyond file save/load
- Automatic scene preset on app exit (future work)
- Scene preset comparison or merge tools (future work)

## Likely Files to Modify

- `src/main/java/kepplr/state/ScenePreset.java` (new)
- `src/main/java/kepplr/state/ScenePresetCodec.java` (new)
- `src/main/java/kepplr/state/ScenePresetValidator.java` (new)
- `src/main/java/kepplr/state/StateSnapshot.java` (expand for scene preset capture)
- `src/main/java/kepplr/commands/SimulationCommands.java` (add scene methods)
- `src/main/java/kepplr/commands/DefaultSimulationCommands.java` (implement scene methods)
- `src/main/java/kepplr/ui/KepplrStatusWindow.java` (add File → Save/Load Scene menus)
- `src/main/java/kepplr/scripting/KepplrScript.java` (expose scene methods)
- `src/test/java/kepplr/state/ScenePresetCodecTest.java` (new)
- `src/test/java/kepplr/state/ScenePresetValidatorTest.java` (new)
- `src/test/java/kepplr/commands/DefaultSimulationCommandsTest.java` (expand)
- `doc/usersguide.rst` (add scene preset documentation)

## UI Hint

This phase includes UI work: File menu items for Save Scene / Load Scene in the JavaFX status window.