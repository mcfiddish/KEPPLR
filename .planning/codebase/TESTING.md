# Testing Patterns

**Analysis Date:** 2026-04-25

## Test Framework

**Runner:**
- JUnit Jupiter `6.0.3`
- Config: `pom.xml`
- Unit tests run through `org.apache.maven.plugins:maven-surefire-plugin:3.5.5`.
- Integration/render tests are configured through `org.apache.maven.plugins:maven-failsafe-plugin:3.5.5` with group `render`, `forkCount=1`, `reuseForks=false`, `GLFW_PLATFORM=x11`, and `LIBGL_ALWAYS_SOFTWARE=1`.

**Assertion Library:**
- JUnit Jupiter assertions via `org.junit.jupiter.api.Assertions`.
- Mockito `5.23.0` is available for tests through `mockito-core`, with the Mockito Java agent configured in Surefire `argLine` in `pom.xml`.

**Run Commands:**
```bash
mvn test                         # Run unit tests; excludes group "render" by default
mvn -DexcludedGroups= test        # Run unit tests without the default render exclusion
mvn -Dgroups=render verify        # Run render-tagged tests through the configured Maven lifecycle
mvn spotless:check test           # Check formatting and run unit tests
```

## Test File Organization

**Location:**
- Tests are co-located by mirrored package path under `src/test/java`: `src/test/java/kepplr/core/SimulationClockTest.java` tests `src/main/java/kepplr/core/SimulationClock.java`.
- Test resources live under `src/test/resources`, especially SPICE fixtures under `src/test/resources/spice/`.
- Shared test support lives under `src/test/java/kepplr/testsupport/TestHarness.java`.

**Naming:**
- Use `{Subject}Test.java` for test classes: `DefaultSimulationCommandsTest.java`, `StateSnapshotCodecTest.java`, `TrailSamplerTest.java`.
- Test method names are lower camel case and describe the expected behavior: `threadIsolation()`, `urlSafeNoPadding()`, `testFallback30Days()`.
- Most test classes are package-private; public test classes also exist and are accepted by the current suite: `src/test/java/kepplr/render/trail/TrailSamplerTest.java`.

**Structure:**
```text
src/test/java/kepplr/
├── apps/                 # CLI/tool tests such as PngToMovie
├── camera/               # Camera frame, transition, and pointing tests
├── commands/             # Simulation command state-transition tests
├── config/               # Configuration and Jackfruit block tests
├── core/                 # Clock and capture service tests
├── ephemeris/            # SPICE/Picante-backed ephemeris tests
├── render/               # Render-manager and geometry tests
├── scripting/            # Groovy script API and recorder tests
├── state/                # JavaFX state and snapshot tests
└── testsupport/          # Shared test reset/fixture helpers
```

## Test Structure

**Suite Organization:**
```java
@DisplayName("SimulationClock")
class SimulationClockTest {
    private FakeClock fakeClock;
    private DefaultSimulationState state;
    private SimulationClock clock;

    @BeforeEach
    void setUp() {
        fakeClock = new FakeClock();
        state = new DefaultSimulationState();
        clock = new SimulationClock(state, START_ET, fakeClock::get);
    }

    @Nested
    @DisplayName("Pause / resume")
    class PauseResume {
        @Test
        @DisplayName("ET freezes when paused")
        void etFreezesDuringPause() {
            // Arrange / act / assert in one readable flow.
        }
    }
}
```

**Patterns:**
- Use `@DisplayName` on classes, nested suites, and tests for readable reports: `src/test/java/kepplr/core/SimulationClockTest.java`, `src/test/java/kepplr/state/StateSnapshotCodecTest.java`.
- Use `@Nested` classes to group related behaviors when a class has many scenarios: `src/test/java/kepplr/commands/DefaultSimulationCommandsTest.java`, `src/test/java/kepplr/state/DefaultSimulationStateTest.java`.
- Use `@BeforeEach` to reset mutable singletons and construct fresh state: `src/test/java/kepplr/config/KEPPLRConfigurationTest.java`, `src/test/java/kepplr/scripting/KepplrScriptTest.java`.
- Use `@TempDir` for filesystem tests and write small generated fixtures directly in the test: `src/test/java/kepplr/apps/PngToMovieTest.java`, `src/test/java/kepplr/scripting/ScriptRunnerTest.java`.
- Use tolerance constants for floating-point comparisons: `TOLERANCE` in `src/test/java/kepplr/core/SimulationClockTest.java`, `PHOBOS_PERIOD_SEC` tolerance in `src/test/java/kepplr/render/trail/TrailSamplerTest.java`.

## Mocking

**Framework:** Mockito is available, but hand-written fakes/spies and real lightweight state objects are more common.

**Patterns:**
```java
import static org.mockito.Mockito.*;

KEPPLREphemeris ephemeris = mock(KEPPLREphemeris.class);
when(ephemeris.getTimeConversion()).thenReturn(timeConversion);
verify(ephemeris).someCall();
```

```java
private static StarCatalog<Star> catalogOf(Iterable<Star> stars) {
    return new StarCatalog<>() {
        @Override
        public Iterator<Star> iterator() {
            return stars.iterator();
        }
        // Implement only the methods the test exercises.
    };
}
```

**What to Mock:**
- Mock or fake external-heavy boundaries such as ephemeris providers, command interfaces, callbacks, and catalogs when the behavior under test is delegation or state transition: `src/test/java/kepplr/scripting/KepplrScriptTest.java`, `src/test/java/kepplr/render/StarFieldManagerTest.java`.
- Use simple hand-written spies when the test needs to record command calls without framework setup: `SpyCommands` in `src/test/java/kepplr/scripting/KepplrScriptTest.java`.
- Use fake clocks instead of real sleeps for time-sensitive behavior: `FakeClock` in `src/test/java/kepplr/core/SimulationClockTest.java`.

**What NOT to Mock:**
- Do not mock `DefaultSimulationState` for command/state tests; use the real JavaFX property-backed state and assert property values: `src/test/java/kepplr/commands/DefaultSimulationCommandsTest.java`, `src/test/java/kepplr/state/DefaultSimulationStateTest.java`.
- Do not mock SPICE/Picante for integration-style ephemeris assertions; load `KEPPLRConfiguration.getTestTemplate()` and use test kernels from `src/test/resources/spice/`: `src/test/java/kepplr/render/trail/TrailSamplerTest.java`, `src/test/java/kepplr/ephemeris/spice/SpiceBundleTest.java`.
- Avoid touching the JME scene graph in unit tests unless the test is explicitly render-tagged; use `null` JME nodes or empty catalogs for non-rendering code paths as in `src/test/java/kepplr/render/StarFieldManagerTest.java`.

## Fixtures and Factories

**Test Data:**
```java
@BeforeEach
void setup() {
    TestHarness.resetSingleton();
    KEPPLRConfiguration.getTestTemplate();
}

double et = TestHarness.getTestEpoch(); // 2015 Jul 14 07:59:00 UTC
```

```java
private static StateSnapshot sample() {
    return new StateSnapshot(
            4.895e8,
            1.0,
            false,
            new double[] {1.234e8, -5.678e7, 9.012e6},
            new float[] {0.0f, 0.0f, 0.0f, 1.0f},
            CameraFrame.INERTIAL,
            399,
            301,
            10,
            45.0,
            false);
}
```

**Location:**
- Shared singleton reset and epoch helpers live in `src/test/java/kepplr/testsupport/TestHarness.java`.
- SPICE kernels and metakernel fixtures live in `src/test/resources/spice/`, including `src/test/resources/spice/kepplr_test.tm`.
- Per-test factories should stay inside the test class when they are only used once: `sample()` in `src/test/java/kepplr/state/StateSnapshotCodecTest.java`, `write1x1Png(...)` in `src/test/java/kepplr/apps/PngToMovieTest.java`, `stubStar(...)` in `src/test/java/kepplr/render/StarFieldManagerTest.java`.

## Coverage

**Requirements:** None enforced. No JaCoCo or other coverage plugin is configured in `pom.xml`.

**View Coverage:**
```bash
# Not detected. Add a coverage plugin before relying on a coverage command.
```

## Test Types

**Unit Tests:**
- Pure state and logic tests dominate the suite. They construct real domain objects and assert state, return values, exceptions, and collection contents: `src/test/java/kepplr/core/SimulationClockTest.java`, `src/test/java/kepplr/state/StateSnapshotCodecTest.java`, `src/test/java/kepplr/render/body/CullingRuleTest.java`.
- Use package-private production constructors/helpers for deterministic tests when needed: `SimulationClock(DefaultSimulationState, double, DoubleSupplier)` in `src/main/java/kepplr/core/SimulationClock.java`.

**Integration Tests:**
- SPICE/Picante-backed tests use `KEPPLRConfiguration.getTestTemplate()` and test kernels. These validate real name resolution, time conversion, ephemeris coverage, and trail sampling: `src/test/java/kepplr/config/KEPPLRConfigurationTest.java`, `src/test/java/kepplr/ephemeris/spice/SpiceBundleTest.java`, `src/test/java/kepplr/render/trail/TrailSamplerTest.java`.
- CLI/filesystem tests use `@TempDir` and small generated files instead of committed temporary outputs: `src/test/java/kepplr/apps/PngToMovieTest.java`.

**E2E Tests:**
- Not used. No browser/UI automation or full application launch test framework is detected.
- Render test infrastructure is configured in `pom.xml`, but no `@Tag("render")` annotation was detected under `src/test/java` during this mapping. Add `@Tag("render")` to tests that require real JME/OpenGL behavior so Surefire/Failsafe grouping works as configured.

## Common Patterns

**Async Testing:**
```java
Thread otherThread = new Thread(() -> {
    KEPPLREphemeris eph2 = config.getEphemeris();
    assertSame(eph2, config.getEphemeris());
});
otherThread.start();
otherThread.join();
```
- Use direct thread joins for simple ThreadLocal/lifecycle assertions: `src/test/java/kepplr/config/KEPPLRConfigurationTest.java`.
- Use command callbacks/fences rather than sleeps for render command flows: `waitRenderFrames` tests in `src/test/java/kepplr/commands/DefaultSimulationCommandsTest.java`.
- For script lifecycle tests, use short scripts and observable `isRunning()` or state effects rather than timing-only assertions: `src/test/java/kepplr/scripting/ScriptRunnerTest.java`.

**Error Testing:**
```java
IllegalArgumentException ex =
        assertThrows(IllegalArgumentException.class, () -> StateSnapshotCodec.decode(encoded));
assertTrue(ex.getMessage().contains("version"));
```
- Use `assertThrows` for invalid input and lifecycle failures: `src/test/java/kepplr/state/StateSnapshotCodecTest.java`, `src/test/java/kepplr/ephemeris/BodyLookupServiceTest.java`, `src/test/java/kepplr/config/KEPPLRConfigurationTest.java`.
- Use `assertDoesNotThrow` for no-op/fallback render-manager behavior where the important contract is resilience: `src/test/java/kepplr/render/StarFieldManagerTest.java`.
- Use `assertAll` when testing multiple invalid variants of one validation rule: `src/test/java/kepplr/state/DefaultSimulationStateTest.java`.

---

*Testing analysis: 2026-04-25*
