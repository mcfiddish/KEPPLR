# Phase 2 Discussion: Configuration and Script Lifecycle Hardening

**Created:** 2026-04-26

## Phase Context

Phase 2 builds on the baseline established in Phase 1. The goal is to make configuration reload, resource extraction, and scripting lifecycle behavior safer and easier to test. This addresses CONF-01, CONF-02, CONF-03, SCR-01, SCR-02, SCR-03.

## Current Implementation State

### CONF-01: Configuration-load error handling
**Current behavior:** In `KEPPLRConfiguration.load(Path)` (lines 333-354):
```java
if (!Files.exists(filename)) {
    System.err.println("Cannot load configuration file " + filename);
    Thread.dumpStack();
    System.exit(1);
}
```
- Uses `System.exit(1)` which terminates the JVM with no structured error
- No way for tests or tools to catch/handle gracefully
- Logs to stderr instead of throwing a proper exception

**Requirement:** Return structured errors instead of process termination

### CONF-02: Resource extraction path collision
**Current behavior:** In `KEPPLRConfiguration.getTemplate()` (lines 120-126):
```java
File tmpDir = new File(System.getProperty("java.io.tmpdir"));
List<Path> resources = ResourceUtils.getResourcePaths("/resources");
for (Path p : resources) {
    ResourceUtils.writeResourceToFile(
            p.toString(), new File(tmpDir, StringUtils.stripStart(p.toString(), "/")), true);
}
```
- Uses shared `java.io.tmpdir` for resource extraction
- No instance-specific path isolation
- Can cause collisions between parallel test runs or multiple instances

**Requirement:** Instance-specific path that avoids temp collisions

### CONF-03: Reload tests
**Current tests:** `KEPPLRConfigurationTest.java` covers:
- Basic singleton lifecycle (`getInstance()` before init throws)
- ThreadLocal ephemeris isolation (different threads get independent instances)

**Missing coverage:**
- Singleton replacement during reload
- Thread-local ephemeris access across reload boundaries
- Render/script interaction boundaries during reload
- Resource cleanup on reload

### SCR-01: Scripting security documentation
**Current state:** No explicit security documentation in `doc/scripting.rst`
- Scripts have filesystem, network, process, configuration, and state access
- No sandboxing - scripts are trusted local automation

**Need:** Documentation that clearly states trusted-code semantics

### SCR-02: Script stop/replacement tests
**Current behavior:** `ScriptRunner.stop()` (lines 133-154):
```java
public void stop() {
    synchronized (lock) {
        stopInternal();
    }
}

private void stopInternal() {
    Thread t = scriptThread;
    if (t != null && t.isAlive()) {
        t.interrupt();
        commands.cancelTransition();
        try {
            t.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (t.isAlive()) {
            logger.warn("Script thread did not terminate within 500ms after interrupt");
        }
    }
    scriptThread = null;
}
```

**Missing coverage:**
- Cooperative interruption (does `interrupt()` work properly?)
- Wait primitives response to interruption (waitRenderFrames, waitWall, waitSim, waitUntilSim, waitTransition)
- Blocking behavior with KEPPLR wait primitives

### SCR-03: Command recording
**Current behavior:** `CommandRecorder.java` records method calls on `SimulationCommands`
- Problem: If new methods are added to `SimulationCommands` but not to the recorder, scripts that use those methods won't be recordable/replayable
- Need: Tests that ensure all `SimulationCommands` methods are covered

## Gray Areas Requiring Research

### Area 1: Structured configuration-load errors
**Research question:** What is the proper way to handle configuration-load errors instead of `System.exit(1)`?
- Should throw a checked or unchecked exception?
- What type of exception?
- How should the UI/command path surface the error?

### Area 2: Instance-specific resource extraction
**Research question:** How should resource extraction be made instance-specific?
- Use UUID-based subdirectory in temp?
- Use user-specific temp (Files.createTempFile)?
- Alternative: Keep resources in JAR and memory-map?

### Area 3: Reload test coverage
**Research question:** What reload tests are truly needed?
- Singleton replacement behavior
- Thread-local ephemeris across reload
- Render/script interaction boundaries
- Integration with SceneManager reload

### Area 4: Scripting security documentation
**Research question:** What exactly needs to be documented?
- Trusted local code semantics
- No sandboxing
- Filesystem, network, process, configuration access warnings

### Area 5: Script stop/replacement tests
**Research question:** What tests are needed for script stop/replacement?
- Does `interrupt()` actually work?
- Do wait primitives respond to interruption?
- What about blocking waits?

### Area 6: Command recording coverage
**Research question:** How to ensure complete coverage?
- What happens when new SimulationCommands methods are added?
- Can we automate or verify recording coverage?

## File Touchpoints

**Likely files to modify:**
1. `src/main/java/kepplr/config/KEPPLRConfiguration.java` - error handling, resource path
2. `src/main/java/kepplr/util/ResourceUtils.java` - extraction path
3. `src/main/java/kepplr/scripting/ScriptRunner.java` - interrupt handling
4. `src/main/java/kepplr/scripting/KepplrScript.java` - wait primitives
5. `src/main/java/kepplr/scripting/CommandRecorder.java` - recording
6. `doc/scripting.rst` - security documentation
7. `src/test/java/kepplr/config/KEPPLRConfigurationTest.java` - reload tests
8. `src/test/java/kepplr/scripting/ScriptRunnerTest.java` - stop tests

## Questions for Discussion

1. **CONF-01:** Should configuration-load errors throw a custom exception type or reuse an existing one?
2. **CONF-02:** Is UUID-based temp subdirectory the right approach, or is there a better pattern?
3. **CONF-03:** Are the missing reload tests feasible unit tests, or do they need integration tests?
4. **SCR-01:** Where exactly should security warnings go in documentation?
5. **SCR-02:** How do we test cooperative interruption without race conditions?
6. **SCR-03:** Is there a way to automate command recording coverage verification?