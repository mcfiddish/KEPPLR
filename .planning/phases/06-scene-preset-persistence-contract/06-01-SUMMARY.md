---
phase: 06-scene-preset-persistence-contract
plan: 06-01
subsystem: persistence
tags: [scene, json, validation, scripting, ui, tests]
dependency_graph:
  requires:
    - phase: 05-replay-manifest-and-performance
      provides: capture manifest and runtime telemetry patterns
  provides:
    - versioned .kepplrscene persistence
    - atomic scene load/apply
    - vector visibility validation
    - save/load UI and scripting hooks
  affects:
    - kepplr.commands.DefaultSimulationCommands
    - kepplr.state.ScenePreset
    - kepplr.state.ScenePresetValidator
    - kepplr.ui.KepplrStatusWindow
    - kepplr.scripting.KepplrScript
    - doc/usersguide.rst
tech_stack:
  - Java 21
  - JavaFX
  - Groovy
  - Jackson
  - JUnit 6
key_files:
  created:
    - .planning/phases/06-scene-preset-persistence-contract/06-01-SUMMARY.md
  modified:
    - src/main/java/kepplr/state/ScenePreset.java
    - src/main/java/kepplr/state/ScenePresetValidator.java
    - src/main/java/kepplr/commands/DefaultSimulationCommands.java
    - src/main/java/kepplr/util/AppVersion.java
    - src/test/java/kepplr/commands/DefaultSimulationCommandsTest.java
    - src/test/java/kepplr/state/ScenePresetCodecTest.java
    - src/test/java/kepplr/state/ScenePresetValidatorTest.java
    - .planning/phases/06-scene-preset-persistence-contract/06-VERIFICATION.md
decisions:
  - Use the first colon in vector visibility keys so towardBody:<naifId> survives round-trips.
  - Restore ET immediately during scene load and queue camera pose restoration separately.
  - Validate vector visibility keys explicitly instead of silently dropping malformed entries.
metrics:
  duration_minutes: 45
  tasks_completed: 10
  files_modified: 8
---

# Phase 06 Plan 06-01 Summary

**Versioned `.kepplrscene` persistence with validation, UI/script entry points, and round-trip coverage.**

## Performance

- **Duration:** 45 min
- **Started:** 2026-05-01T13:15:00Z
- **Completed:** 2026-05-01T14:00:53Z
- **Tasks:** 10
- **Files modified:** 8

## Accomplishments
- Preserved scene preset vector visibility keys, including `towardBody:<naifId>` entries, across save/load round-trips.
- Tightened scene load semantics so ET is published immediately and camera restoration remains queued on the JME thread.
- Added validation for vector visibility key syntax and supported types.
- Expanded command tests to cover save/load round-trips and queued camera restoration.

## Files Created/Modified
- `src/main/java/kepplr/state/ScenePreset.java` - corrected vector type serialization
- `src/main/java/kepplr/state/ScenePresetValidator.java` - validates vector visibility keys
- `src/main/java/kepplr/commands/DefaultSimulationCommands.java` - fixes vector parsing and scene restore ordering
- `src/main/java/kepplr/util/AppVersion.java` - regenerated version metadata from the build
- `src/test/java/kepplr/commands/DefaultSimulationCommandsTest.java` - scene preset save/load tests
- `src/test/java/kepplr/state/ScenePresetCodecTest.java` - round-trip coverage for `towardBody`
- `src/test/java/kepplr/state/ScenePresetValidatorTest.java` - validation coverage for vector keys
- `.planning/phases/06-scene-preset-persistence-contract/06-VERIFICATION.md` - updated verification report

## Decisions Made
- Keep `.kepplrscene` validation strict for malformed vector keys instead of silently dropping them.
- Preserve the existing script/UI entry points and use them as the integration surface for the preset format.

## Deviations from Plan
None. The implementation and verification now cover the intended scene preset contract.

## Issues Encountered
- Maven regenerated `src/main/java/kepplr/util/AppVersion.java` during the build, which needed formatting cleanup.

## Test Results
- `mvn -Dtest=ScenePresetCodecTest,ScenePresetValidatorTest,DefaultSimulationCommandsTest,KepplrScriptTest,CommandRecorderTest test` - passed
- `mvn test` - passed
- `mvn spotless:check` - passed after formatting fixes

## Self-Check
- [x] `.kepplrscene` save/load path round-trips the supported scene state
- [x] Vector visibility keys preserve `towardBody:<naifId>` entries
- [x] Atomic scene load behavior keeps camera restoration separate from state restore
- [x] Validation rejects malformed vector keys with field-specific errors
- [x] Unit tests and formatting gates pass

## Next Phase Readiness
Phase 6 is complete and ready for milestone completion / archival steps.

---
*Phase: 06-scene-preset-persistence-contract*
*Completed: 2026-05-01*
