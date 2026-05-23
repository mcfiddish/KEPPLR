# Codebase Concerns

**Analysis Date:** 2026-04-25

## Tech Debt

**Large application and manager classes:**
- Issue: Several files combine UI wiring, renderer orchestration, domain behavior, and tool-specific workflows in very large classes. `src/main/java/kepplr/apps/GlbModelViewer.java` is 3,396 lines, `src/main/java/kepplr/render/body/SaturnRingManager.java` is 2,676 lines, `src/main/java/kepplr/ui/KepplrStatusWindow.java` is 1,642 lines, `src/main/java/kepplr/render/InstrumentFrustumManager.java` is 1,562 lines, `src/main/java/kepplr/scripting/KepplrScript.java` is 1,492 lines, and `src/main/python/apps/convert_to_normalized_glb.py` is 1,254 lines.
- Files: `src/main/java/kepplr/apps/GlbModelViewer.java`, `src/main/java/kepplr/render/body/SaturnRingManager.java`, `src/main/java/kepplr/ui/KepplrStatusWindow.java`, `src/main/java/kepplr/render/InstrumentFrustumManager.java`, `src/main/java/kepplr/scripting/KepplrScript.java`, `src/main/python/apps/convert_to_normalized_glb.py`
- Impact: Changes in these areas have high review cost and elevated regression risk because unrelated responsibilities live in the same edit surface.
- Fix approach: Extract focused collaborators only along existing ownership boundaries: UI menu sections out of `src/main/java/kepplr/ui/KepplrStatusWindow.java`, rendering sub-passes out of `src/main/java/kepplr/render/InstrumentFrustumManager.java`, and conversion format handlers out of `src/main/python/apps/convert_to_normalized_glb.py`.

**Deferred shape-model eclipse support:**
- Issue: GLB body shape models render through `glbModelRoot`, but eclipse/shadow application to GLB body geometry is explicitly deferred and the shader still uses a sphere approximation.
- Files: `src/main/java/kepplr/render/body/BodyNodeFactory.java`, `src/main/java/kepplr/render/body/BodySceneNode.java`, `src/main/resources/kepplr/shaders/Bodies/EclipseLighting.frag`
- Impact: Bodies with irregular GLB models can render with physically inaccurate self-shadowing or body-shadow behavior in near-frustum views.
- Fix approach: Route `EclipseShadowManager` material updates to loaded GLB geometry nodes and add a shape-aware shadow refinement path before relying on GLB models for close-up scientific visualization.

**Configuration singleton and thread-local ephemeris coupling:**
- Issue: `KEPPLRConfiguration` owns a process-global singleton and lazily creates a `ThreadLocal<KEPPLREphemeris>` per thread.
- Files: `src/main/java/kepplr/config/KEPPLRConfiguration.java`, `src/main/java/kepplr/ephemeris/KEPPLREphemeris.java`
- Impact: Tests and reload paths must manage global state carefully; new background threads can silently create independent ephemeris instances with different lifecycle behavior.
- Fix approach: Keep point-of-use access for current architecture, but isolate singleton mutation to startup/reload paths and add tests around reload plus script/render thread access.

**Temporary resource extraction to shared system temp:**
- Issue: Default configuration extracts bundled resources under `java.io.tmpdir/resources` on every template load.
- Files: `src/main/java/kepplr/config/KEPPLRConfiguration.java`, `src/main/java/kepplr/util/ResourceUtils.java`
- Impact: Multiple concurrent KEPPLR instances or test runs can collide on the same temp resource tree, and stale temp files can affect behavior.
- Fix approach: Extract to an instance-specific temp directory, record that path in the configuration, and clean it deterministically during shutdown/tests instead of relying only on `deleteOnExit`.

**Star tile API escape hatch:**
- Issue: `TileSet.getTiles()` exposes raw unfiltered tiles and is already marked as questionable in code comments.
- Files: `src/main/java/kepplr/stars/TileSet.java`
- Impact: Callers can depend on raw tile internals instead of the filtered iterable contract, making future catalog implementations harder to change.
- Fix approach: Prefer iteration over `TileSet` for new code and deprecate `getTiles()` after replacing any production callers that need raw access.

## Known Bugs

**Groovy script stop is cooperative only:**
- Symptoms: `ScriptRunner.stopInternal()` interrupts the script thread and waits 500 ms, then logs a warning if the thread remains alive. It does not forcibly terminate the Groovy execution engine.
- Files: `src/main/java/kepplr/scripting/ScriptRunner.java`
- Trigger: Run a Groovy script that ignores interruption or blocks in arbitrary Java/Groovy code outside KEPPLR wait primitives, then stop or replace the script.
- Workaround: Prefer scripts that use KEPPLR wait primitives and check interruption during long loops.

**Configuration load can terminate the process:**
- Symptoms: Missing configuration files call `System.err.println`, `Thread.dumpStack()`, and `System.exit(1)` instead of returning a structured error.
- Files: `src/main/java/kepplr/config/KEPPLRConfiguration.java`
- Trigger: Start `src/main/java/kepplr/apps/KEPPLR.java` or another tool with a non-existent config path.
- Workaround: Validate config path existence before calling `KEPPLRConfiguration.load(Path)` in new CLI entry points.

**Gaia lookup requires optional source index:**
- Symptoms: `GaiaCatalog.getStar(String)` throws `UnsupportedOperationException` when the tile pack lacks `gaia.sourceidx`.
- Files: `src/main/java/kepplr/stars/catalogs/gaia/GaiaCatalog.java`
- Trigger: Load a Gaia tile pack built without a source-id index, then call `getStar("GAIA:<sourceId>")`.
- Workaround: Rebuild the pack with `src/main/java/kepplr/stars/catalogs/gaia/tools/GaiaBuildSourceIndex.java` or avoid direct source-id lookup.

**Transient Gaia StarTile implements only part of the interface:**
- Symptoms: Gaia lookup returns proxy `StarTile` objects that support iteration, `toString`, `hashCode`, and `equals`; other `StarTile` methods throw `UnsupportedOperationException`.
- Files: `src/main/java/kepplr/stars/catalogs/gaia/GaiaCatalog.java`, `src/main/java/kepplr/stars/StarTile.java`
- Trigger: New code calls non-iterator `StarTile` methods on tiles returned from `GaiaCatalog.lookup(...)`.
- Workaround: Treat Gaia lookup results as iterable star collections only until a concrete Gaia tile type is added.

## Security Considerations

**Unrestricted Groovy scripting:**
- Risk: Scripts execute through the standard Groovy JSR-223 engine without sandboxing. Scripts can access Java classes, files, network APIs, process APIs, and the live `SimulationState` and `KEPPLRConfiguration`.
- Files: `src/main/java/kepplr/scripting/ScriptRunner.java`, `src/main/java/kepplr/scripting/KepplrScript.java`, `doc/scripting.rst`
- Current mitigation: Scripts are user-selected through the UI or CLI and are intended as trusted automation.
- Recommendations: Document scripts as fully trusted code, avoid running scripts from untrusted sources, and add a restricted scripting mode before supporting shared/downloaded script execution.

**User-controlled external executables:**
- Risk: `PngToMovie` runs a user-provided `ffmpeg` executable path and the Python model converter runs user-provided helper tools such as `assimp`, `cmodconvert`, `dskexp`, and ImageMagick.
- Files: `src/main/java/kepplr/apps/PngToMovie.java`, `src/main/python/apps/convert_to_normalized_glb.py`, `src/main/python/README.md`
- Current mitigation: Commands are built as argument arrays rather than shell strings, reducing shell-injection risk.
- Recommendations: Treat executable paths as trusted local input, avoid exposing these tools to remote callers, and keep command logging free of sensitive paths in shared logs.

**Arbitrary output file writes from UI and scripting paths:**
- Risk: Screenshots, capture sequences, logs, recorded scripts, and videos write to user-chosen paths or script-provided paths.
- Files: `src/main/java/kepplr/core/CaptureService.java`, `src/main/java/kepplr/ui/KepplrStatusWindow.java`, `src/main/java/kepplr/ui/LogWindow.java`, `src/main/java/kepplr/apps/PngToMovie.java`
- Current mitigation: Desktop file choosers are used for UI flows; CLI and scripts assume trusted local users.
- Recommendations: Keep these APIs local-only. If automation is exposed beyond local trusted use, constrain output roots and reject path traversal outside configured work directories.

**Secret files:**
- Risk: No application `.env` or credential file was detected in the repo scan. `.codex/get-shit-done/bin/lib/secrets.cjs` exists as workflow support code and was not read as secret material.
- Files: `.codex/get-shit-done/bin/lib/secrets.cjs`
- Current mitigation: Runtime secrets are not part of the KEPPLR application path.
- Recommendations: Continue excluding `.env`, credential, key, and certificate files from commits.

## Performance Bottlenecks

**All-body/all-caster shadow evaluation pressure:**
- Problem: Eclipse rendering is designed around all bodies as casters and receivers, while the shader has fixed occluder loops capped at eight uniforms.
- Files: `src/main/java/kepplr/render/body/EclipseShadowManager.java`, `src/main/resources/kepplr/shaders/Bodies/EclipseLighting.frag`, `src/main/java/kepplr/render/RenderQuality.java`
- Cause: Physically general shadow behavior competes with per-frame renderer cost and shader uniform limits.
- Improvement path: Keep quality-tier limits explicit, add performance benchmarks for body counts, and define measurable LOD acceptance criteria before expanding shape-model shadows.

**Large resource extraction and bundled assets:**
- Problem: Default template creation copies all bundled resources, including multi-megabyte GLB, map, and SPICE files, into temp storage.
- Files: `src/main/java/kepplr/config/KEPPLRConfiguration.java`, `src/main/java/kepplr/util/ResourceUtils.java`, `src/main/resources/resources/shapes/new_horizons_dds.glb`, `src/main/resources/resources/maps/`
- Cause: `getTemplate()` writes the whole `/resources` tree to a temp folder rather than resolving resources lazily.
- Improvement path: Extract only files required by the active config, cache per application instance, and benchmark startup with packaged JAR resources.

**Gaia tile decompression is per-tile and heap-backed:**
- Problem: Gaia tiles are read into a heap `ByteBuffer`, copied to a compressed byte array, decompressed into `GaiaStar[]`, and cached by tile count rather than byte budget.
- Files: `src/main/java/kepplr/stars/catalogs/gaia/GaiaCatalog.java`
- Cause: The cache cap is a tile count (`cacheTiles`), while actual memory cost depends on star count per tile.
- Improvement path: Track approximate decoded bytes, bound cache by memory budget, and add stress tests with dense Gaia tiles.

**Model conversion script is monolithic and serial:**
- Problem: `convert_to_normalized_glb.py` handles directory scans, external conversion, texture normalization, Blender import/export, GLB metadata patching, and error reporting in one serial workflow.
- Files: `src/main/python/apps/convert_to_normalized_glb.py`
- Cause: Format-specific conversion is embedded in one script rather than isolated handlers with independent test seams.
- Improvement path: Split pure parsing/metadata utilities from Blender-bound execution and add small fixture tests for MTL parsing, texture token extraction, and quaternion metadata writing.

## Fragile Areas

**JME/JavaFX/Groovy threading boundary:**
- Files: `src/main/java/kepplr/render/KepplrApp.java`, `src/main/java/kepplr/ui/KepplrStatusWindow.java`, `src/main/java/kepplr/ui/FxDispatch.java`, `src/main/java/kepplr/scripting/ScriptRunner.java`, `src/main/java/kepplr/commands/DefaultSimulationCommands.java`
- Why fragile: The application spans the JME render thread, JavaFX application thread, and Groovy script thread. Blocking calls, scene graph mutations, and FX updates must stay on the correct thread.
- Safe modification: Route simulation changes through `SimulationCommands`, marshal UI changes through `FxDispatch`, and use render-frame fences such as `waitRenderFrames` for capture/scripting visibility.
- Test coverage: Unit coverage exists for command/state behavior, but full end-to-end thread interaction is difficult to exercise without a live renderer.

**Render-path behavior not fully exercised by CI:**
- Files: `pom.xml`, `src/test/java/kepplr/render/`, `.claude/KEPPLR_Roadmap.md`
- Why fragile: Maven config defines render-tagged Failsafe tests with `testFailureIgnore=true`, but no `@Tag("render")` tests were detected under `src/test/java`. The roadmap also records deferred full render-path/layering tests for retained swaths.
- Safe modification: Add render tests with explicit `@Tag("render")`, remove or narrow `testFailureIgnore`, and keep deterministic non-render unit tests for math/state behavior.
- Test coverage: Broad unit tests exist, but live material-backed JME render paths and retained-swath layering remain under-covered.

**GLB material and lighting fidelity:**
- Files: `src/main/java/kepplr/render/body/BodyNodeFactory.java`, `src/main/java/kepplr/render/util/GLTFUtils.java`, `src/main/python/apps/convert_to_normalized_glb.py`, `src/main/python/README.md`
- Why fragile: Spacecraft rendering intentionally prioritizes KEPPLR lighting and body-shadow behavior over preserving full GLB/PBR/CMOD material semantics. The converter docs describe texture and UV issues that require Blender repair.
- Safe modification: Preserve per-geometry base color/texture where possible and verify converted models visually in `src/main/java/kepplr/apps/GlbModelViewer.java` before changing renderer material policy.
- Test coverage: `src/test/java/kepplr/render/util/GLTFUtilsTest.java` covers metadata parsing, but material fidelity and visual appearance require manual or render-level validation.

**Star catalog tiling edge cases:**
- Files: `src/main/java/kepplr/stars/catalogs/tiled/uniform/UniformTileLocator.java`, `src/test/java/kepplr/stars/catalogs/tiled/uniform/UniformTileLocatorTest.java`, `src/main/java/kepplr/stars/catalogs/gaia/GaiaCatalog.java`
- Why fragile: `UniformTileLocator` still notes missing handling when `halfAngle + coneAngleAddition` exceeds `Math.PI / 2`, where convexity assumptions break down.
- Safe modification: Add explicit wide-cone behavior before changing tile selection logic, and test cone half-angles near and above the documented boundary.
- Test coverage: Existing tests cover normal tiling behavior; wide-cone failure boundaries need targeted tests.

## Scaling Limits

**Shader occluder capacity:**
- Current capacity: Eclipse shader loops over up to eight occluders per receiver.
- Limit: Scenes with more than eight relevant occluders require quality policy decisions about which casters are included.
- Scaling path: Promote occluder selection to an explicit ranked policy in `src/main/java/kepplr/render/body/EclipseShadowManager.java` and test quality-tier behavior through `src/main/java/kepplr/render/RenderQuality.java`.

**State snapshot field set:**
- Current capacity: Snapshot strings include ET, time rate, paused flag, camera pose/frame, focused/targeted/selected IDs, and FOV.
- Limit: Overlay visibility for labels, trails, vectors, and frustums is explicitly deferred.
- Scaling path: Version a "full snapshot" extension in `src/main/java/kepplr/state/StateSnapshotCodec.java` and add backward-compatibility tests in `src/test/java/kepplr/state/StateSnapshotCodecTest.java`.

**Gaia data distribution:**
- Current capacity: Bundled star data uses Yale BSC (`src/main/resources/kepplr/stars/catalogs/yaleBSC/ybsc5.gz`); Gaia support expects external tile packs.
- Limit: Gaia is too large to embed in the application JAR and direct source lookup requires an optional source index.
- Scaling path: Keep Gaia packs user-managed, add validation/error UI around missing tiles and missing `gaia.sourceidx`, and document pack-building commands for `src/main/java/kepplr/stars/catalogs/gaia/tools/`.

**Desktop rendering environment:**
- Current capacity: Maven profiles cover JavaFX native classifiers for macOS, Linux, and Windows; render tests set X11 and software OpenGL environment variables.
- Limit: JME/LWJGL/JavaFX behavior can vary by OS, GPU driver, display server, and first-thread requirements.
- Scaling path: Add platform smoke tests for launch/configuration paths and keep render-specific tests isolated from pure unit tests.

## Dependencies at Risk

**Groovy 3 JSR-223 scripting:**
- Risk: The scripting system depends on `groovy-jsr223` and unrestricted dynamic execution.
- Impact: Script compatibility and security posture are tied to Groovy engine behavior.
- Migration plan: Keep the public `KepplrScript` API stable, then evaluate a restricted command DSL or a sandboxed script host if untrusted scripts become a requirement.

**JME/LWJGL/JavaFX native stack:**
- Risk: The app depends on native rendering and UI classifiers configured in `pom.xml`.
- Impact: Launch and render behavior can fail for unsupported platform/architecture combinations or headless environments.
- Migration plan: Maintain Maven platform profiles, document required JavaFX/JME runtime flags, and add smoke tests for each supported platform before packaging releases.

**External model/video tools:**
- Risk: Blender, assimp, cmodconvert, dskexp, ImageMagick, and ffmpeg are outside Maven dependency control.
- Impact: Conversion and video export behavior can vary by installed tool version and PATH.
- Migration plan: Pin supported versions in `doc/python_tools.rst`, validate tool versions at runtime where possible, and keep generated model assets checked or reproducible.

**Picante and SPICE kernels:**
- Risk: Ephemeris correctness depends on Picante behavior and runtime SPICE kernel availability.
- Impact: Missing or incompatible kernels break body positions, frames, instruments, and time conversion.
- Migration plan: Keep small deterministic test kernels in `src/test/resources/spice/`, validate metakernels at config load, and avoid changing `src/main/java/kepplr/ephemeris/spice/SpiceBundle.java` without ephemeris regression tests.

## Missing Critical Features

**Formal determinism and golden tolerances:**
- Problem: Formal deterministic replay requirements and numeric tolerances are explicitly not implemented.
- Blocks: Reliable cross-platform visual regression testing and reproducible capture guarantees.

**Full object discovery UX:**
- Problem: Autocomplete, recents, favorites, and bookmarks are deferred.
- Blocks: Efficient navigation in large kernel/object catalogs.

**Additional visibility layers and star density controls:**
- Problem: Reference axes, grids, ring-plane indicators, and user-facing star magnitude/density controls are deferred.
- Blocks: Advanced spatial orientation workflows and user tuning of star-field density.

**Richer labels and label priority policy:**
- Problem: Distance, light-time, phase angle, and configurable priority labels are deferred.
- Blocks: Scientific readout workflows that need more than object names and HUD-level information.

**Navigation safety semantics:**
- Problem: Collision/ground avoidance and broader navigation mode constraints are deferred.
- Blocks: Safe near-surface camera operation and predictable close-approach UX.

**Full overlay state snapshots:**
- Problem: State snapshots do not include overlay visibility.
- Blocks: Complete restoration of visual sessions through copy/paste state, CLI state startup, and recorded workflows.

## Test Coverage Gaps

**Live render-path and layering tests:**
- What's not tested: Material-backed JME render behavior, retained-swath layering, and some visual ordering/occlusion guarantees.
- Files: `pom.xml`, `src/test/java/kepplr/render/`, `.claude/KEPPLR_Roadmap.md`
- Risk: Visual regressions can pass `mvn test` because most coverage is math/state/unit-level and no render-tagged tests were detected.
- Priority: High

**Script sandbox and interruption behavior:**
- What's not tested: Hostile or non-cooperative Groovy scripts, filesystem/process access boundaries, and scripts that ignore thread interruption.
- Files: `src/main/java/kepplr/scripting/ScriptRunner.java`, `src/test/java/kepplr/scripting/ScriptRunnerTest.java`
- Risk: Stop/replacement behavior and trust assumptions can regress without obvious unit failures.
- Priority: Medium

**Configuration reload and global singleton interactions:**
- What's not tested: Multi-threaded reload while scripts/render managers are active, stale thread-local ephemeris after reload, and temp resource collisions across concurrent instances.
- Files: `src/main/java/kepplr/config/KEPPLRConfiguration.java`, `src/test/java/kepplr/config/KEPPLRConfigurationTest.java`
- Risk: Runtime reload and tests can become order-dependent or thread-dependent.
- Priority: Medium

**Model conversion fixture tests:**
- What's not tested: MTL parsing, DDS token extraction, missing-texture policies, external-tool failure modes, and GLB metadata patching in `src/main/python/apps/convert_to_normalized_glb.py`.
- Files: `src/main/python/apps/convert_to_normalized_glb.py`, `src/main/python/README.md`
- Risk: Converter regressions may only be caught by manual Blender runs.
- Priority: Medium

**Wide-cone star tile lookup:**
- What's not tested: `UniformTileLocator` behavior when `halfAngle + coneAngleAddition` exceeds the convexity assumption.
- Files: `src/main/java/kepplr/stars/catalogs/tiled/uniform/UniformTileLocator.java`, `src/test/java/kepplr/stars/catalogs/tiled/uniform/UniformTileLocatorTest.java`
- Risk: Large field-of-view star queries can miss or over-include tiles unexpectedly.
- Priority: Medium

---

*Concerns audit: 2026-04-25*
