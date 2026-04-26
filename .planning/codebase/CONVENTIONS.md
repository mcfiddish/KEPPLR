# Coding Conventions

**Analysis Date:** 2026-04-25

## Naming Patterns

**Files:**
- Use one public top-level Java type per file, with `PascalCase.java` matching the type name: `src/main/java/kepplr/core/SimulationClock.java`, `src/main/java/kepplr/state/StateSnapshotCodec.java`.
- Keep test files beside the mirrored package path under `src/test/java` and name them `{Subject}Test.java`: `src/test/java/kepplr/core/SimulationClockTest.java`, `src/test/java/kepplr/render/trail/TrailSamplerTest.java`.
- Use `package-info.java` for package-level JavaDoc in packages that expose public APIs: `src/main/java/kepplr/config/package-info.java`, `src/main/java/kepplr/stars/package-info.java`.
- Python utility scripts use lowercase snake_case file names under `src/main/python/apps`: `src/main/python/apps/convert_to_normalized_glb.py`.

**Functions:**
- Use `lowerCamelCase` for Java methods: `advance()`, `setPaused(boolean)`, `computeTrailDurationSec(int, double)` in `src/main/java/kepplr/core/SimulationClock.java` and `src/main/java/kepplr/render/trail/TrailSampler.java`.
- Name test methods after the behavior being asserted, not implementation details: `initialET()`, `etFreezesDuringPause()`, `invalidBase64()` in `src/test/java/kepplr/core/SimulationClockTest.java` and `src/test/java/kepplr/state/StateSnapshotCodecTest.java`.
- Use short factory/helper names inside tests when scoped to one class: `sample()` in `src/test/java/kepplr/state/StateSnapshotCodecTest.java`, `manager()` and `catalogOf(...)` in `src/test/java/kepplr/render/StarFieldManagerTest.java`.
- Use static utility classes with private constructors for stateless helpers: `TrailSampler` in `src/main/java/kepplr/render/trail/TrailSampler.java`, `StateSnapshotCodec` in `src/main/java/kepplr/state/StateSnapshotCodec.java`.

**Variables:**
- Use `lowerCamelCase` for fields and locals: `pausedWallTime`, `lastET`, `baryAnchor`, `frameOrdinal` in `src/main/java/kepplr/core/SimulationClock.java`, `src/main/java/kepplr/render/trail/TrailSampler.java`, and `src/main/java/kepplr/state/StateSnapshotCodec.java`.
- Use short domain abbreviations only when they are established in this codebase: `et`, `naifId`, `eph`, `gm`, `svf` in `src/main/java/kepplr/render/trail/TrailSampler.java`.
- Use `private static final` uppercase constants for shared test and production constants: `START_ET`, `TOLERANCE`, `PHOBOS_PERIOD_SEC` in `src/test/java/kepplr/core/SimulationClockTest.java` and `src/test/java/kepplr/render/trail/TrailSamplerTest.java`.

**Types:**
- Use `PascalCase` for classes, interfaces, records, and enums: `SimulationClock`, `SimulationCommands`, `StateSnapshot`, `CameraFrame` in `src/main/java/kepplr/core/SimulationClock.java`, `src/main/java/kepplr/commands/SimulationCommands.java`, `src/main/java/kepplr/state/StateSnapshot.java`, and `src/main/java/kepplr/camera/CameraFrame.java`.
- Use Java records for compact immutable value carriers: `TimeAnchor` in `src/main/java/kepplr/core/TimeAnchor.java`, `FrustumColor` in `src/main/java/kepplr/state/FrustumColor.java`, `BodyInView` in `src/main/java/kepplr/state/BodyInView.java`.
- Use nested private records/enums for command queues and local typed state when they are not part of the public API: `PendingRequest` records in `src/main/java/kepplr/camera/TransitionController.java`, `RecordedCommand` in `src/main/java/kepplr/scripting/CommandRecorder.java`.
- Use Jackfruit annotated interfaces for configuration blocks: `BodyBlock`, `SpacecraftBlock`, and `KEPPLRConfigBlock` in `src/main/java/kepplr/config/`.

## Code Style

**Formatting:**
- Use Maven Spotless with Palantir Java Format. Configuration lives in `pom.xml` under `com.diffplug.spotless:spotless-maven-plugin` with `<palantirJavaFormat><formatJavadoc>true</formatJavadoc></palantirJavaFormat>`.
- Run formatting through Maven:
```bash
mvn spotless:apply
mvn spotless:check
```
- Keep indentation at 4 spaces for Java and let Palantir wrap long argument lists, chained calls, and JavaDoc paragraphs: see `src/main/java/kepplr/state/StateSnapshotCodec.java` and `src/main/java/kepplr/render/trail/TrailSampler.java`.
- Use Java text blocks for multi-line literals where readability matters: `manifest` and `capture_info.json` fixture strings in `src/test/java/kepplr/apps/PngToMovieTest.java`.
- The codebase uses UTF-8 source encoding via `<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>` in `pom.xml`; existing Java comments include Unicode symbols in explanatory text.

**Linting:**
- No Checkstyle, PMD, SpotBugs, ESLint, Prettier, or Biome configuration is detected.
- Treat `mvn spotless:check` and `mvn test` as the current style and correctness gates.
- Maven Enforcer requires Maven `3.6.3` or newer in `pom.xml`.

## Import Organization

**Order:**
1. Static imports first, usually `import static org.junit.jupiter.api.Assertions.*;` and, where needed, `import static org.mockito.Mockito.*;` in test files such as `src/test/java/kepplr/config/KEPPLRConfigurationTest.java`.
2. Java and JDK imports next: `java.*`, `javax.*`, and related standard APIs in files such as `src/main/java/kepplr/state/StateSnapshotCodec.java`.
3. Third-party and project imports after standard imports. Palantir formatting groups imports lexicographically, so project imports such as `kepplr.*` can appear before `org.*` and `picante.*`: see `src/main/java/kepplr/render/trail/TrailSampler.java`.

**Path Aliases:**
- Not applicable. Java packages use normal Maven source roots: `src/main/java` and `src/test/java`.
- Resource paths are conventional Maven resources under `src/main/resources` and `src/test/resources`, including SPICE fixtures such as `src/test/resources/spice/kepplr_test.tm`.

## Error Handling

**Patterns:**
- Throw `IllegalArgumentException` for invalid caller input and malformed user-facing data: `FrustumColor.hex(...)` in `src/main/java/kepplr/state/FrustumColor.java`, `PngToMovie.run(...)` validation in `src/main/java/kepplr/apps/PngToMovie.java`, and `StateSnapshotCodec.decode(...)` in `src/main/java/kepplr/state/StateSnapshotCodec.java`.
- Throw `IllegalStateException` for invalid lifecycle or impossible internal states: `KEPPLRConfiguration.getInstance()` tested by `src/test/java/kepplr/config/KEPPLRConfigurationTest.java`, encode failure wrapping in `src/main/java/kepplr/state/StateSnapshotCodec.java`.
- Catch recoverable ephemeris/rendering failures at domain boundaries, log context, and fall back when the UI can continue: `TrailSampler.computeTrailDurationSec(...)` returns `KepplrConstants.TRAIL_DEFAULT_DURATION_SEC` after logged SPICE failures in `src/main/java/kepplr/render/trail/TrailSampler.java`.
- Preserve interrupt status when catching `InterruptedException`: follow patterns in `src/main/java/kepplr/scripting/ScriptRunner.java` and `src/main/java/kepplr/commands/DefaultSimulationCommands.java`.
- Keep checked exceptions local to IO/configuration boundaries and translate them into domain-appropriate runtime exceptions or return codes: `StateSnapshotCodec` wraps byte-stream IO, `PngToMovie.run(...)` returns process-style exit codes in `src/main/java/kepplr/apps/PngToMovie.java`.

## Logging

**Framework:** Log4j 2

**Patterns:**
- Declare loggers as `private static final Logger logger = LogManager.getLogger();`: `src/main/java/kepplr/render/trail/TrailSampler.java`, `src/main/java/kepplr/scripting/ScriptRunner.java`, `src/main/java/kepplr/commands/DefaultSimulationCommands.java`.
- Log warnings for recoverable operations that fall back: `logger.warn("Could not compute ...")` in `src/main/java/kepplr/render/trail/TrailSampler.java`.
- Log errors when background or UI-triggered work fails outside the caller's direct control: `src/main/java/kepplr/scripting/ScriptRunner.java`, `src/main/java/kepplr/ui/KepplrStatusWindow.java`, `src/main/java/kepplr/core/CaptureService.java`.
- Tests usually assert observable state or return values rather than log output: `src/test/java/kepplr/scripting/ScriptRunnerTest.java`, `src/test/java/kepplr/apps/PngToMovieTest.java`.

## Comments

**When to Comment:**
- Use JavaDoc on public APIs, records, configuration interfaces, and non-obvious threading or domain logic: `src/main/java/kepplr/core/SimulationClock.java`, `src/main/java/kepplr/commands/SimulationCommands.java`, `src/main/java/kepplr/config/BodyBlock.java`.
- Add short inline comments for domain constants, fixture meaning, or race/threading rationale: `src/main/java/kepplr/core/SimulationClock.java`, `src/test/java/kepplr/render/trail/TrailSamplerTest.java`.
- Keep comments tied to domain behavior and invariants. Avoid generic comments that restate code.
- Existing section divider comments are common in large test classes and stateful modules. Preserve the style when extending those files: `src/test/java/kepplr/core/SimulationClockTest.java`, `src/test/java/kepplr/commands/DefaultSimulationCommandsTest.java`.

**JSDoc/TSDoc:**
- Not applicable. This repo is Java/Python, not TypeScript.
- Use JavaDoc with `@param`, `@return`, `@throws`, and HTML tags such as `<p>`, `<h3>`, `<ul>`, and `<pre>` for Java API documentation: `src/main/java/kepplr/core/SimulationClock.java`, `src/main/java/kepplr/state/StateSnapshotCodec.java`.

## Function Design

**Size:** Prefer small, behavior-focused methods for pure logic and validation. Larger orchestration methods exist in UI and rendering classes; keep new domain logic extracted into package-private or private helpers when testability improves, as in `TrailSampler.doSample(...)` and `TrailSampler.sampleOnePosition(...)` in `src/main/java/kepplr/render/trail/TrailSampler.java`.

**Parameters:** Pass primitive domain IDs and timestamps explicitly (`int naifId`, `double et`, `double durationSec`) rather than storing hidden state when functions are pure or sampling-oriented: `src/main/java/kepplr/render/trail/TrailSampler.java`. Inject time suppliers or callbacks for test control: `SimulationClock(DefaultSimulationState, double, DoubleSupplier)` in `src/main/java/kepplr/core/SimulationClock.java`.

**Return Values:** Return immutable records for compact state snapshots and typed results (`StateSnapshot`, `FrustumColor`, `ApplyResult`). Return nullable values only where existing third-party/domain lookup semantics require it, such as SPICE object lookups and render sampling; document null handling at the method boundary.

## Module Design

**Exports:** Use package-private visibility for testable internals that should not be public API: `SimulationClock(DefaultSimulationState, double, DoubleSupplier)` in `src/main/java/kepplr/core/SimulationClock.java`, `TrailSampler.sampleWithExplicitRef(...)` and `sampleOnePosition(...)` in `src/main/java/kepplr/render/trail/TrailSampler.java`.

**Barrel Files:** Not applicable. Java packages do not use barrel exports.

**Local GSD Constraints:**
- Codebase maps are stored under `.planning/codebase/` and are used by GSD planning/execution workflows. The project-local GSD skills in `.codex/skills/` define workflow rules, not Java application coding APIs.
- Do not store secrets or environment values in planning documents. No `.env` content was read during this quality mapping.

---

*Convention analysis: 2026-04-25*
