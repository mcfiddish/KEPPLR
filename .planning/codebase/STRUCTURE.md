# Codebase Structure

**Analysis Date:** 2026-04-25

## Directory Layout

```text
KEPPLR/
├── pom.xml                         # Maven build, dependencies, profiles, test plugin configuration
├── README.md                       # Project overview and usage notes
├── CHANGELOG.md                    # Release/change notes
├── LICENSE                         # Project license
├── mkPackage.bash                  # Packaging helper script
├── doc/                            # Sphinx/reStructuredText user and developer documentation
├── src/main/java/kepplr/            # Java application source
├── src/main/resources/             # JME assets, shaders, textures, shapes, SPICE kernels, star catalogs
├── src/main/python/                # Python asset tooling
├── src/main/bash/                  # Shell utilities for version/test kernel generation
├── src/test/java/kepplr/            # Java tests mirroring main package layout
└── src/test/resources/             # Test SPICE kernels and resource fixtures
```

## Directory Purposes

**`src/main/java/kepplr/apps`:**
- Purpose: User-facing Java entry points and standalone tools.
- Contains: GUI launcher, ephemeris printer, config dumper, GLB viewer, PNG-to-movie converter.
- Key files: `src/main/java/kepplr/apps/KEPPLR.java`, `src/main/java/kepplr/apps/PrintEphemeris.java`, `src/main/java/kepplr/apps/DumpConfig.java`, `src/main/java/kepplr/apps/GlbModelViewer.java`, `src/main/java/kepplr/apps/PngToMovie.java`

**`src/main/java/kepplr/render`:**
- Purpose: Main JME app and top-level render subsystems.
- Contains: `KepplrApp`, HUD, render quality enum, star field, sun halo, instrument frustum overlays.
- Key files: `src/main/java/kepplr/render/KepplrApp.java`, `src/main/java/kepplr/render/KepplrHud.java`, `src/main/java/kepplr/render/StarFieldManager.java`, `src/main/java/kepplr/render/SunHaloRenderer.java`, `src/main/java/kepplr/render/InstrumentFrustumManager.java`

**`src/main/java/kepplr/render/body`:**
- Purpose: Body and spacecraft scene graph management.
- Contains: Body culling, node factories, scene node wrappers, Saturn rings, eclipse shadows, body material/geometry handling.
- Key files: `src/main/java/kepplr/render/body/BodySceneManager.java`, `src/main/java/kepplr/render/body/BodyNodeFactory.java`, `src/main/java/kepplr/render/body/BodySceneNode.java`, `src/main/java/kepplr/render/body/BodyCuller.java`, `src/main/java/kepplr/render/body/SaturnRingManager.java`, `src/main/java/kepplr/render/body/EclipseShadowManager.java`

**`src/main/java/kepplr/render/frustum`:**
- Purpose: Multi-frustum depth layer policy.
- Contains: Frustum layer enum and assignment rules.
- Key files: `src/main/java/kepplr/render/frustum/FrustumLayer.java`

**`src/main/java/kepplr/render/label`:**
- Purpose: 2D label overlay management for bodies.
- Contains: Label manager and supporting render code.
- Key files: `src/main/java/kepplr/render/label/LabelManager.java`

**`src/main/java/kepplr/render/trail`:**
- Purpose: Orbital trail sampling and rendering.
- Contains: Trail manager, sampler, renderer/material helpers.
- Key files: `src/main/java/kepplr/render/trail/TrailManager.java`, `src/main/java/kepplr/render/trail/TrailSampler.java`

**`src/main/java/kepplr/render/vector`:**
- Purpose: Vector overlay definitions and rendering.
- Contains: Vector type strategy interface, built-in vector types, definitions, manager, renderer.
- Key files: `src/main/java/kepplr/render/vector/VectorType.java`, `src/main/java/kepplr/render/vector/VectorTypes.java`, `src/main/java/kepplr/render/vector/VectorManager.java`, `src/main/java/kepplr/render/vector/VectorRenderer.java`

**`src/main/java/kepplr/render/util`:**
- Purpose: Rendering helper utilities.
- Contains: GLTF/JME utility code.
- Key files: `src/main/java/kepplr/render/util/GLTFUtils.java`

**`src/main/java/kepplr/camera`:**
- Purpose: Camera input, transitions, coordinate frames, navigation, and pointing.
- Contains: Input handler, transition controller, transition value objects, body-fixed/synodic frame math.
- Key files: `src/main/java/kepplr/camera/CameraInputHandler.java`, `src/main/java/kepplr/camera/TransitionController.java`, `src/main/java/kepplr/camera/CameraTransition.java`, `src/main/java/kepplr/camera/CameraFrame.java`, `src/main/java/kepplr/camera/BodyFixedFrame.java`, `src/main/java/kepplr/camera/SynodicFrameApplier.java`

**`src/main/java/kepplr/commands`:**
- Purpose: Action boundary for UI, scripts, and input.
- Contains: Command interface and default implementation.
- Key files: `src/main/java/kepplr/commands/SimulationCommands.java`, `src/main/java/kepplr/commands/DefaultSimulationCommands.java`

**`src/main/java/kepplr/state`:**
- Purpose: Shared observable simulation state and serialization.
- Contains: Read-only state interface, mutable implementation, body-in-view records, colors, snapshots, codec.
- Key files: `src/main/java/kepplr/state/SimulationState.java`, `src/main/java/kepplr/state/DefaultSimulationState.java`, `src/main/java/kepplr/state/StateSnapshot.java`, `src/main/java/kepplr/state/StateSnapshotCodec.java`, `src/main/java/kepplr/state/BodyInView.java`, `src/main/java/kepplr/state/FrustumColor.java`

**`src/main/java/kepplr/core`:**
- Purpose: Core non-render services.
- Contains: Simulation clock, time anchor, screenshot/capture service.
- Key files: `src/main/java/kepplr/core/SimulationClock.java`, `src/main/java/kepplr/core/TimeAnchor.java`, `src/main/java/kepplr/core/CaptureService.java`

**`src/main/java/kepplr/config`:**
- Purpose: Application configuration schema and singleton configuration loader.
- Contains: Jackfruit config block interfaces, configuration singleton, body/SPICE/spacecraft block schemas.
- Key files: `src/main/java/kepplr/config/KEPPLRConfiguration.java`, `src/main/java/kepplr/config/KEPPLRConfigBlock.java`, `src/main/java/kepplr/config/BodyBlock.java`, `src/main/java/kepplr/config/SPICEBlock.java`, `src/main/java/kepplr/config/SpacecraftBlock.java`

**`src/main/java/kepplr/ephemeris`:**
- Purpose: High-level ephemeris, frame, spacecraft, instrument, lookup, and orbital element services.
- Contains: Picante/SPICE-backed service and domain interfaces/value objects.
- Key files: `src/main/java/kepplr/ephemeris/KEPPLREphemeris.java`, `src/main/java/kepplr/ephemeris/BodyLookupService.java`, `src/main/java/kepplr/ephemeris/OsculatingElements.java`, `src/main/java/kepplr/ephemeris/Instrument.java`, `src/main/java/kepplr/ephemeris/Spacecraft.java`

**`src/main/java/kepplr/ephemeris/spice`:**
- Purpose: Low-level SPICE bundle assembly and Picante provider access.
- Contains: `SpiceBundle`.
- Key files: `src/main/java/kepplr/ephemeris/spice/SpiceBundle.java`

**`src/main/java/kepplr/ui`:**
- Purpose: JavaFX windows, dialogs, bridge, and log display.
- Contains: Status window, dialogs, FX dispatcher, log window/appender, state bridge.
- Key files: `src/main/java/kepplr/ui/KepplrStatusWindow.java`, `src/main/java/kepplr/ui/SimulationStateFxBridge.java`, `src/main/java/kepplr/ui/FxDispatch.java`, `src/main/java/kepplr/ui/LogWindow.java`, `src/main/java/kepplr/ui/SetTimeDialog.java`, `src/main/java/kepplr/ui/SetTimeRateDialog.java`, `src/main/java/kepplr/ui/SetFovDialog.java`

**`src/main/java/kepplr/scripting`:**
- Purpose: Groovy automation and command recording.
- Contains: Script runner, script API facade, recorder, wait helper, output/listener helpers.
- Key files: `src/main/java/kepplr/scripting/ScriptRunner.java`, `src/main/java/kepplr/scripting/KepplrScript.java`, `src/main/java/kepplr/scripting/CommandRecorder.java`, `src/main/java/kepplr/scripting/WaitTransition.java`

**`src/main/java/kepplr/stars`:**
- Purpose: Star catalog interfaces and collection abstractions.
- Contains: Star, catalog, tiled catalog, tile set interfaces/classes and exceptions.
- Key files: `src/main/java/kepplr/stars/Star.java`, `src/main/java/kepplr/stars/StarCatalog.java`, `src/main/java/kepplr/stars/TiledStarCatalog.java`, `src/main/java/kepplr/stars/StarTile.java`, `src/main/java/kepplr/stars/TileSet.java`

**`src/main/java/kepplr/stars/catalogs`:**
- Purpose: Concrete catalog implementations and catalog build tooling.
- Contains: Abstract catalog base, Gaia catalog/tools, tiled uniform catalog, Yale Bright Star Catalog.
- Key files: `src/main/java/kepplr/stars/catalogs/AbstractStarCatalog.java`, `src/main/java/kepplr/stars/catalogs/gaia/GaiaCatalog.java`, `src/main/java/kepplr/stars/catalogs/gaia/tools/GaiaCsvToTilePack.java`, `src/main/java/kepplr/stars/catalogs/tiled/uniform/UniformBandTiledCatalog.java`, `src/main/java/kepplr/stars/catalogs/yaleBSC/YaleBrightStarCatalog.java`

**`src/main/java/kepplr/stars/services`:**
- Purpose: Star attribute calculation service interfaces.
- Contains: Color, location, and magnitude calculator interfaces plus defaults.
- Key files: `src/main/java/kepplr/stars/services/ColorCalculator.java`, `src/main/java/kepplr/stars/services/LocationCalculator.java`, `src/main/java/kepplr/stars/services/LocationCalculators.java`, `src/main/java/kepplr/stars/services/MagnitudeCalculator.java`

**`src/main/java/kepplr/templates`:**
- Purpose: Common CLI template behavior for app/tool entry points.
- Contains: `KEPPLRTool` interface and default tool.
- Key files: `src/main/java/kepplr/templates/KEPPLRTool.java`, `src/main/java/kepplr/templates/DefaultKEPPLRTool.java`

**`src/main/java/kepplr/util`:**
- Purpose: Shared low-level utility classes and constants.
- Contains: Version, constants, Log4j configurator, resource utilities, wrapping helpers.
- Key files: `src/main/java/kepplr/util/KepplrConstants.java`, `src/main/java/kepplr/util/Log4j2Configurator.java`, `src/main/java/kepplr/util/ResourceUtils.java`, `src/main/java/kepplr/util/AppVersion.java`, `src/main/java/kepplr/util/WrapUtil.java`

**`src/main/resources`:**
- Purpose: Packaged runtime assets.
- Contains: JME shader/material definitions, body maps, GLB shapes, SPICE kernels/metakernels, star catalog resources.
- Key files: `src/main/resources/kepplr/shaders/Bodies/EclipseLighting.j3md`, `src/main/resources/kepplr/shaders/Rings/SaturnRings.j3md`, `src/main/resources/kepplr/shaders/SunHalo/SunHalo.j3md`, `src/main/resources/resources/spice/kepplr.tm`, `src/main/resources/kepplr/stars/catalogs/yaleBSC/ybsc5.gz`

**`src/test/java/kepplr`:**
- Purpose: Unit and integration tests mirroring production packages.
- Contains: Tests for apps, camera, commands, config, core, ephemeris, render managers, scripting, stars, state, UI bridge.
- Key files: `src/test/java/kepplr/testsupport/TestHarness.java`, `src/test/java/kepplr/core/SimulationClockTest.java`, `src/test/java/kepplr/commands/DefaultSimulationCommandsTest.java`, `src/test/java/kepplr/render/body/BodyCullerTest.java`, `src/test/java/kepplr/ui/SimulationStateFxBridgeTest.java`

**`src/test/resources`:**
- Purpose: Test fixture resources.
- Contains: Minimal SPICE metakernel and supporting CK/FK/IK/LSK/PCK/SCLK/SPK files.
- Key files: `src/test/resources/spice/kepplr_test.tm`, `src/test/resources/spice/spk/kepplr_test.bsp`, `src/test/resources/spice/lsk/naif0012.tls`

**`doc`:**
- Purpose: Sphinx documentation.
- Contains: User guide, GUI docs, configuration docs, scripting docs, tool docs, static images.
- Key files: `doc/index.rst`, `doc/usersguide.rst`, `doc/configuration.rst`, `doc/gui.rst`, `doc/scripting.rst`, `doc/tools-src/PrintEphemeris.rst`, `doc/tools-src/GlbModelViewer.rst`

**`src/main/python`:**
- Purpose: Python-side asset tooling.
- Contains: GLB normalization/conversion script and README.
- Key files: `src/main/python/apps/convert_to_normalized_glb.py`, `src/main/python/README.md`

## Key File Locations

**Entry Points:**
- `src/main/java/kepplr/apps/KEPPLR.java`: Main GUI CLI launcher.
- `src/main/java/kepplr/render/KepplrApp.java`: JME `SimpleApplication` implementation and default-launch `main`.
- `src/main/java/kepplr/apps/PrintEphemeris.java`: Ephemeris CLI tool.
- `src/main/java/kepplr/apps/DumpConfig.java`: Configuration CLI tool.
- `src/main/java/kepplr/apps/GlbModelViewer.java`: Standalone GLB viewer.
- `src/main/java/kepplr/apps/PngToMovie.java`: PNG sequence movie tool.
- `src/main/java/kepplr/stars/catalogs/gaia/tools/GaiaBuildSourceIndex.java`: Gaia catalog source index builder.
- `src/main/java/kepplr/stars/catalogs/gaia/tools/GaiaCsvToTilePack.java`: Gaia CSV-to-tile-pack converter.
- `src/main/java/kepplr/stars/catalogs/gaia/tools/GaiaMergeTilePacks.java`: Gaia tile pack merger.
- `src/main/python/apps/convert_to_normalized_glb.py`: Python GLB asset conversion tool.

**Configuration:**
- `pom.xml`: Maven dependencies, Java 21 compiler release, JavaFX platform profiles, test plugin setup, packaging.
- `src/main/java/kepplr/config/KEPPLRConfiguration.java`: Runtime configuration singleton and reload/template logic.
- `src/main/java/kepplr/config/KEPPLRConfigBlock.java`: Top-level config schema.
- `src/main/java/kepplr/config/BodyBlock.java`: Body config schema.
- `src/main/java/kepplr/config/SPICEBlock.java`: SPICE config schema.
- `src/main/java/kepplr/config/SpacecraftBlock.java`: Spacecraft config schema.
- `src/main/resources/resources/spice/kepplr.tm`: Packaged SPICE metakernel.
- `src/test/resources/spice/kepplr_test.tm`: Test SPICE metakernel.

**Core Logic:**
- `src/main/java/kepplr/render/KepplrApp.java`: Runtime orchestration and per-frame loop.
- `src/main/java/kepplr/commands/DefaultSimulationCommands.java`: State-transition implementation.
- `src/main/java/kepplr/state/DefaultSimulationState.java`: Mutable state store.
- `src/main/java/kepplr/core/SimulationClock.java`: Time advancement and ET/rate anchoring.
- `src/main/java/kepplr/ephemeris/KEPPLREphemeris.java`: High-level SPICE/Picante service.
- `src/main/java/kepplr/render/body/BodySceneManager.java`: Body/spacecraft scene management.
- `src/main/java/kepplr/camera/TransitionController.java`: Camera transition request processing.
- `src/main/java/kepplr/ui/SimulationStateFxBridge.java`: JME-to-JavaFX state bridge.
- `src/main/java/kepplr/scripting/KepplrScript.java`: Script API surface.

**Testing:**
- `src/test/java/kepplr/testsupport/TestHarness.java`: Test helper/harness support.
- `src/test/java/kepplr/core/SimulationClockTest.java`: Clock behavior tests.
- `src/test/java/kepplr/commands/DefaultSimulationCommandsTest.java`: Command behavior tests.
- `src/test/java/kepplr/state/DefaultSimulationStateTest.java`: State behavior tests.
- `src/test/java/kepplr/state/StateSnapshotCodecTest.java`: State snapshot serialization tests.
- `src/test/java/kepplr/ui/SimulationStateFxBridgeTest.java`: JavaFX bridge tests.
- `src/test/java/kepplr/render/body/BodyCullerTest.java`: Culling policy tests.
- `src/test/resources/spice`: Test kernels.

**Documentation:**
- `doc/index.rst`: Documentation root.
- `doc/usersguide.rst`: User guide.
- `doc/configuration.rst`: Configuration documentation.
- `doc/gui.rst`: GUI documentation.
- `doc/scripting.rst`: Scripting API documentation.
- `doc/scripting_examples.rst`: Script examples.
- `doc/javadoc.rst`: Javadoc documentation page.
- `doc/python_tools.rst`: Python tool documentation.

## Naming Conventions

**Files:**
- Java source files use UpperCamelCase matching the public type: `src/main/java/kepplr/render/KepplrApp.java`, `src/main/java/kepplr/state/DefaultSimulationState.java`.
- Interfaces use nouns or capability names without an `I` prefix: `src/main/java/kepplr/commands/SimulationCommands.java`, `src/main/java/kepplr/render/vector/VectorType.java`, `src/main/java/kepplr/stars/StarCatalog.java`.
- Implementations often use `Default` for the primary concrete implementation: `src/main/java/kepplr/commands/DefaultSimulationCommands.java`, `src/main/java/kepplr/state/DefaultSimulationState.java`, `src/main/java/kepplr/templates/DefaultKEPPLRTool.java`.
- Tests mirror source class names with `Test` suffix: `src/test/java/kepplr/camera/TransitionProgressTest.java`, `src/test/java/kepplr/render/trail/TrailManagerTest.java`.
- Package docs use `package-info.java`: `src/main/java/kepplr/config/package-info.java`, `src/main/java/kepplr/ephemeris/package-info.java`.
- Resource shader files keep JME naming by shader domain and extension: `src/main/resources/kepplr/shaders/Rings/SaturnRings.frag`, `src/main/resources/kepplr/shaders/Rings/SaturnRings.j3md`.

**Directories:**
- Java packages are lowercase by architectural area: `src/main/java/kepplr/render`, `src/main/java/kepplr/commands`, `src/main/java/kepplr/state`.
- Render subpackages are grouped by overlay/scene concern: `src/main/java/kepplr/render/body`, `src/main/java/kepplr/render/trail`, `src/main/java/kepplr/render/vector`, `src/main/java/kepplr/render/label`.
- Catalog implementations nest under provider/strategy names: `src/main/java/kepplr/stars/catalogs/yaleBSC`, `src/main/java/kepplr/stars/catalogs/gaia`, `src/main/java/kepplr/stars/catalogs/tiled/uniform`.
- Test packages mirror production packages under `src/test/java/kepplr`.
- Runtime resources keep JME-compatible roots: `src/main/resources/assets`, `src/main/resources/kepplr/shaders`, `src/main/resources/resources/maps`, `src/main/resources/resources/spice`.

## Where to Add New Code

**New User Command:**
- Primary code: Add method to `src/main/java/kepplr/commands/SimulationCommands.java`.
- Implementation: Add behavior to `src/main/java/kepplr/commands/DefaultSimulationCommands.java`.
- Recording/scripting: Add forwarding/recording in `src/main/java/kepplr/scripting/CommandRecorder.java` and script facade methods in `src/main/java/kepplr/scripting/KepplrScript.java` when user-facing automation needs it.
- UI wiring: Call the command from `src/main/java/kepplr/ui/KepplrStatusWindow.java` or `src/main/java/kepplr/camera/CameraInputHandler.java`.
- Tests: Add package-mirrored tests under `src/test/java/kepplr/commands` and scripting/UI tests as needed.

**New State Field:**
- Primary code: Add read-only property to `src/main/java/kepplr/state/SimulationState.java`.
- Mutable implementation: Add backing property, getter, and setter to `src/main/java/kepplr/state/DefaultSimulationState.java`.
- UI bridge: Add formatted/bound JavaFX property to `src/main/java/kepplr/ui/SimulationStateFxBridge.java`.
- Snapshot persistence: Add to `src/main/java/kepplr/state/StateSnapshot.java` and `src/main/java/kepplr/state/StateSnapshotCodec.java` if the state should survive sharing/restoration.
- Tests: Add tests under `src/test/java/kepplr/state` and `src/test/java/kepplr/ui`.

**New Render Overlay:**
- Primary code: Add a focused manager under `src/main/java/kepplr/render/<overlay>` or, for a top-level single class, under `src/main/java/kepplr/render`.
- Orchestration: Construct and update it from `src/main/java/kepplr/render/KepplrApp.java`.
- State controls: Add visibility/config state under `src/main/java/kepplr/state` and command methods under `src/main/java/kepplr/commands`.
- Resources: Place shaders/materials under `src/main/resources/kepplr/shaders` or `src/main/resources/assets` and textures/models under `src/main/resources/resources`.
- Tests: Add manager/policy tests under `src/test/java/kepplr/render`.

**New Body Rendering Behavior:**
- Primary code: Add behavior under `src/main/java/kepplr/render/body`.
- Orchestration point: Prefer extending `BodySceneManager`, `BodySceneNode`, `BodyNodeFactory`, or specialized managers such as `SaturnRingManager` depending on scope.
- Tests: Add tests under `src/test/java/kepplr/render/body`.

**New Camera Behavior:**
- Primary code: Add math/transition/input behavior under `src/main/java/kepplr/camera`.
- Command entry: Expose user/script actions through `src/main/java/kepplr/commands`.
- Frame loop: Integrate frame updates in `src/main/java/kepplr/render/KepplrApp.java` only when it needs render-thread camera access.
- Tests: Add tests under `src/test/java/kepplr/camera`.

**New Configuration Property:**
- Primary code: Add schema to the relevant config block in `src/main/java/kepplr/config`.
- Loader behavior: Update `src/main/java/kepplr/config/KEPPLRConfiguration.java` if derived maps, defaults, or reload behavior change.
- Documentation: Update `doc/configuration.rst`.
- Tests: Add config tests under `src/test/java/kepplr/config`.

**New Ephemeris Capability:**
- Primary code: Add high-level behavior to `src/main/java/kepplr/ephemeris/KEPPLREphemeris.java` or low-level bundle behavior to `src/main/java/kepplr/ephemeris/spice/SpiceBundle.java`.
- Lookup helpers: Use `src/main/java/kepplr/ephemeris/BodyLookupService.java` for name/NAIF resolution behavior.
- Tests: Add tests under `src/test/java/kepplr/ephemeris` or `src/test/java/kepplr/ephemeris/spice`.

**New JavaFX View/Dialog:**
- Primary code: Add a class under `src/main/java/kepplr/ui`.
- State access: Bind to `SimulationStateFxBridge` properties; do not read `SimulationState` directly from the view.
- Actions: Call `SimulationCommands`; do not mutate `DefaultSimulationState` directly.
- Tests: Add bridge or view-model tests under `src/test/java/kepplr/ui` where practical.

**New CLI Tool:**
- Primary code: Add an entry point under `src/main/java/kepplr/apps`.
- Shared CLI behavior: Implement or reuse `src/main/java/kepplr/templates/KEPPLRTool.java`.
- Documentation: Add a page under `doc/tools-src` and link from relevant docs.
- Tests: Add tests under `src/test/java/kepplr/apps`.

**New Star Catalog:**
- Primary code: Add concrete catalog under `src/main/java/kepplr/stars/catalogs/<provider>`.
- Interfaces: Reuse `src/main/java/kepplr/stars/StarCatalog.java`, `src/main/java/kepplr/stars/TiledStarCatalog.java`, and service interfaces under `src/main/java/kepplr/stars/services`.
- Build tools: Add provider-specific tooling under `src/main/java/kepplr/stars/catalogs/<provider>/tools` if needed.
- Resources: Add packaged catalog data under `src/main/resources/kepplr/stars/catalogs/<provider>`.
- Tests: Add tests under `src/test/java/kepplr/stars/catalogs/<provider>`.

**Utilities:**
- Shared helpers: Add broadly reusable Java helpers under `src/main/java/kepplr/util`.
- Domain helpers: Prefer package-local helpers near the domain, such as `src/main/java/kepplr/render/util`, `src/main/java/kepplr/render/body`, or `src/main/java/kepplr/camera`.

## Special Directories

**`src/main/resources/resources/spice`:**
- Purpose: Packaged SPICE kernels and metakernel for runtime ephemeris.
- Generated: No
- Committed: Yes

**`src/test/resources/spice`:**
- Purpose: Minimal SPICE fixture set for tests.
- Generated: No
- Committed: Yes

**`src/main/resources/resources/maps`:**
- Purpose: Body texture maps used by body materials.
- Generated: No
- Committed: Yes

**`src/main/resources/resources/shapes`:**
- Purpose: GLB spacecraft/body shape models.
- Generated: Some assets may be produced by `src/main/python/apps/convert_to_normalized_glb.py`; directory contents are runtime assets.
- Committed: Yes

**`src/main/resources/kepplr/shaders`:**
- Purpose: Custom JME shader and material definitions for bodies, rings, sun halo, and stars.
- Generated: No
- Committed: Yes

**`src/main/resources/assets`:**
- Purpose: Additional JME asset-manager-compatible shader/material resources.
- Generated: No
- Committed: Yes

**`src/main/resources/kepplr/stars/catalogs/yaleBSC`:**
- Purpose: Packaged Yale Bright Star Catalog data.
- Generated: No
- Committed: Yes

**`src/main/java/kepplr/stars/catalogs/gaia/tools`:**
- Purpose: Java command-line tooling for generating Gaia catalog assets.
- Generated: No
- Committed: Yes

**`src/main/python`:**
- Purpose: Auxiliary Python tooling outside Maven Java runtime.
- Generated: No
- Committed: Yes

**`src/main/bash`:**
- Purpose: Shell helpers for version file and test kernel generation.
- Generated: No
- Committed: Yes

**`doc/_static/images`:**
- Purpose: Documentation screenshots and static images.
- Generated: No
- Committed: Yes

**`target`:**
- Purpose: Maven build output, generated sources, classes, reports, and packaged artifacts.
- Generated: Yes
- Committed: No

---

*Structure analysis: 2026-04-25*
