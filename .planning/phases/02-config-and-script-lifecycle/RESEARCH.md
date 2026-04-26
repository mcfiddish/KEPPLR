# CONF-02 Research: Instance-Specific Resource Extraction Path

**Gray Area:** Instance-specific resource extraction
**Phase Context:** Make resource extraction uses instance-specific path to avoid sharing temp path
**Project Context:** Java 21 desktop application with SPICE kernel extraction to temp

## Background

**Current behavior** in `KEPPLRConfiguration.getTemplate()` (lines 120-126):
```java
File tmpDir = new File(System.getProperty("java.io.tmpdir"));
List<Path> resources = ResourceUtils.getResourcePaths("/resources");
for (Path p : resources) {
    ResourceUtils.writeResourceToFile(
            p.toString(), new File(tmpDir, StringUtils.stripStart(p.toString(), "/")), true);
}
```

This uses the shared system temp directory (`java.io.tmpdir`) which can cause collisions:
- Parallel test runs
- Multiple KEPPLR instances
- Other processes using temp directory

## Options

| Option | Pros | Cons | Complexity |
|--------|------|------|------------|
| UUID-based subdirectory | Simple, unique, portable | Adds overhead, needs cleanup on exit | 2 files, new utility method |
| Files.createTempDirectory() | Native Java NIO2, secure defaults | Still in system temp, needs prefix | 1 file change |
| In-memory resources (JAR) | No temp extraction needed | Higher memory, may not work for all resources | Requires refactoring ResourceUtils |

## Recommendation

**Recommendation:** Use UUID-based subdirectory

The approach:
```java
// Create instance-specific temp directory
String instanceId = UUID.randomUUID().toString();
Path instanceDir = Files.createTempDirectory("kepplr-" + instanceId);
instanceDir.toFile().deleteOnExit();
```

This provides:
- Unique isolation per instance
- Easy to identify in system temp
- Consistent naming prefix for debugging
- deleteOnExit() for cleanup

Complex files that need changes are KEPPLRConfiguration.java (getTemplate method) and ResourceUtils.java. Risk is minimal collision in naming.

---

# CONF-01, CONF-03, SCR-01, SCR-02, SCR-03 Summary

## CONF-01: Structured Configuration-Load Errors

| Option | Pros | Cons | Complexity | Recommendation |
|--------|------|------|------------|----------------|
| Custom ConfigurationException | Clear error type, caught by callers | New exception class | 1 new class + 1 file | Rec if structured errors needed |
| RuntimeException wrapper | No new types needed | Less precise error handling | 1 file change | Rec if minimal change preferred |
| Return error result | Explicit return type | API breaking change | Multiple files | Not recommended |

**Rationale:** Currently KEPPLRConfiguration.load(Path) uses System.exit(1). Throwing a RuntimeException (e.g., `IllegalArgumentException` for missing files) allows callers to handle gracefully. Use `IllegalArgumentException` for missing/invalid paths, `IllegalStateException` for already-loaded state.

---

## CONF-03: Reload Test Coverage

| Option | Pros | Cons | Complexity | Recommendation |
|--------|------|------|------------|----------------|
| Unit tests for singleton replacement | Fast, focused | Doesn't test full integration | 2-3 new test methods | Rec if unit testing preferred |
| Integration tests with SceneManager | Tests real reload behavior | Requires render harness | New integration test class | Rec if integration testing preferred |
| ThreadLocal isolation across reload | Validates thread safety | Complex test setup | 2 new test methods | Rec if threadsafety critical |

**Rationale:** Existing tests cover basic ThreadLocal isolation. New tests should verify: (1) singleton replacement clears old state, (2) thread-local ephemeris is recreated after reload, (3) render/script boundaries don't leak state.

---

## SCR-01: Scripting Security Documentation

| Option | Pros | Cons | Complexity | Recommendation |
|--------|------|------|------------|----------------|
| Add security section to scripting.rst | Central documentation | None | 1 file + ~20 lines | Rec if documentation preferred |
| Add security warnings to code | Visible at usage point | May be overlooked | 1 file change | Rec if code warnings preferred |
| Both doc and code warnings | Maximum visibility | More maintenance | 2 files | Rec if security critical |

**Rationale:** Scripts have full filesystem, network, process, configuration, and state access. No sandboxing exists. Key warnings: (1) scripts are trusted local code, (2) never run untrusted scripts, (3) scripts can modify/delete files, (4) scripts can access network.

---

## SCR-02: Script Stop/Replacement Tests

| Option | Pros | Cons | Complexity | Recommendation |
|--------|------|------|------------|----------------|
| Existing test coverage | Already implemented | May have race conditions | Test verification only | Rec if tests pass |
| Explicit cooperative interrupt test | Clear behavior documented | May be flaky | 2 new test methods | Rec for robustness |
| Wait primitive interrupt tests | Validates wait cleanup | Complex threading | 3-4 new test methods | Rec if wait primitives critical |

**Rationale:** Existing ScriptRunnerTest tests stop() calls cancelTransition(). Need to verify: (1) interrupt() actually terminates blocking script, (2) wait primitives (waitWall, waitSim, etc.) respond to interruption, (3) no resource leaks after stop.

---

## SCR-03: Command Recording Coverage

| Option | Pros | Cons | Complexity | Recommendation |
|--------|------|------|------------|----------------|
| Manual method coverage verification | Simple | Error-prone | Code review | Rec if minimal change |
| Reflection-based verification | Automatic, catches new methods | May have false positives | 1 new test method | Rec if automation preferred |
| Interface + test extension | Explicit contract | More maintenance | 2 files + test changes | Not recommended |

**Rationale:** CommandRecorder records SimulationCommands method calls. Need to ensure any new SimulationCommands methods are recordable. Use reflection-based test that iterates interface methods and verifies each has a corresponding record method.