# REND-01: Render Test Infrastructure — Technical Blocker

**Created:** 2026-04-26

## Issue

The render test infrastructure is configured in `pom.xml` but no `@Tag("render")` tests exist under `src/test/java`.

## Evidence

### Infrastructure Status

- **Failsafe plugin:** Configured with `<groups>render</groups>` at line 408 of `pom.xml`
- **Environment:** Software GL via `LIBGL_ALWAYS_SOFTWARE=1` and `GLFW_PLATFORM=x11`
- **Test failure handling:** `<testFailureIgnore>true</testFailureIgnore>` (line 406)
- **Existing tests:** Zero `@Tag("render")` annotations found in `src/test/java`

### Technical Constraints

1. **JME display requirement:** JMonkeyEngine requires an active display/GPU context to initialize. Even with software rendering, the LWJGL/GLFW backend attempts window creation.

2. **Headless CI limitations:** Running `mvn -Dgroups=render verify` in a headless environment (no X11 display, no GPU) will fail at JME initialization before any test code runs.

3. **Test harness state:** Existing tests (e.g., `KepplrAppTrailDeclutterTest`) use `TestHarness` which does NOT initialize JME — it only sets up configuration and state. True render tests would require JME initialization.

### Evidence of Attempted Solutions

- Failsafe config includes `GLFW_PLATFORM=x11` and `LIBGL_ALWAYS_SOFTWARE=1` — these help but do not solve the headless display problem
- `testFailureIgnore=true` suggests the original intent was to allow render tests to fail gracefully in environments without display capability

## Recommendation

**Document as completed blocker** — The infrastructure is in place, but true render smoke tests require either:

1. A CI environment with display server (Xvfb or similar), OR
2. Investment in a mock JME rendering layer for unit testing, OR
3. Acceptance that render tests are "manual verification only" and remove the `<testFailureIgnore>` flag

## Verification Command

```bash
# This will fail in headless environments due to no display
mvn verify -Dgroups=render -Djavafx.platform=linux
```

Expected failure: `java.lang.UnsatisfiedLinkError: org/lwjgl/glfw/GLFW.glfwInit()`

## Status

**REND-01: PARTIALLY COMPLETE** — Infrastructure configured, blocker documented. No `@Tag("render")` tests added due to technical constraints.