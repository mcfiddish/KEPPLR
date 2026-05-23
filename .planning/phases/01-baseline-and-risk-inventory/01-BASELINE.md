# Phase 1 Baseline Command Status

**Captured:** 2026-04-26
**Scope:** BASE-01, BASE-02

## Checklist

- [x] Run `mvn test`.
- [x] Run `mvn spotless:check`.
- [x] Record exit codes and concise failure summaries.
- [x] Inspect render-test Maven configuration without running live render flow.
- [x] Preserve failures as baseline debt instead of fixing production code in Phase 1.

## Command: mvn test

Exit code: 0

Status: Pass

Summary:
- Maven Surefire completed successfully.
- Results: 739 tests run, 0 failures, 0 errors, 0 skipped.
- Build result: `BUILD SUCCESS`.
- Total time reported by Maven: 01:12 min.

Notes:
- The run emitted expected runtime warnings from SPICE/configuration tests, including missing default `resources/spice/kepplr.tm` in some test paths and a deliberately invalid Groovy script fixture. These warnings did not fail the build.

## Command: mvn spotless:check

Exit code: 1

Status: Fail

Baseline debt:
- Spotless reported one formatting violation in `src/main/java/kepplr/util/AppVersion.java`.
- First relevant check: `src/main/java/kepplr/util/AppVersion.java`.
- Violation summary: Spotless expected removal of a leading blank line before `package kepplr.util;`.
- Maven guidance: `Run 'mvn spotless:apply' to fix these violations.`

Notes:
- `mvn test` generated local version metadata in `src/main/java/kepplr/util/AppVersion.java` and `doc/conf.py`; those build side effects were not retained as Phase 1 source changes.
- The formatting failure is recorded here as the current baseline style debt. Phase 1 does not fix it.

## Render Flow Inspection

Configured render command: `mvn -Dgroups=render verify`

Findings:
- `pom.xml` contains `maven-failsafe-plugin` configuration for render tests.
- `pom.xml` contains `excludedGroups`; default unit test execution excludes `render`.
- `pom.xml` configures Failsafe with `<groups>render</groups>` and `<excludedGroups>__none__</excludedGroups>`.
- `src/test/java` currently contains no `@Tag("render")` occurrence.
- The render flow exists in Maven configuration, but the render-tagged test set is empty.

Decision:
- Phase 1 does not require live render execution; Phase 3 owns render-path execution planning.

## Baseline Gaps

- `mvn test` is green and can serve as the current correctness baseline.
- `mvn spotless:check` is not green because generated `AppVersion.java` formatting currently violates Spotless.
- Render-test infrastructure is configured, but no `@Tag("render")` tests were found under `src/test/java`.
