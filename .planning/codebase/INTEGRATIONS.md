# External Integrations

**Analysis Date:** 2026-04-25

## APIs & External Services

**Ephemeris and Scientific Data:**
- NASA NAIF SPICE kernels - Runtime planetary, satellite, spacecraft, frame, time, and kernel-pool data.
  - SDK/Client: Picante `edu.jhuapl.ses:picante` loaded through `src/main/java/kepplr/ephemeris/spice/SpiceBundle.java`.
  - Auth: Not required.
  - Configuration: `spice.metakernel` in `src/main/java/kepplr/config/SPICEBlock.java`, defaulting to `resources/spice/kepplr.tm`.
  - Default metakernel: `src/main/resources/resources/spice/kepplr.tm`.
  - Optional download helper: `src/main/resources/resources/spice/spk/getSPK.bash` downloads public `.bsp` files from `https://naif.jpl.nasa.gov/pub/naif/generic_kernels`.
- Gaia star data - Offline tile-pack star catalog support for user-supplied Gaia exports.
  - SDK/Client: Local Java readers/builders in `src/main/java/kepplr/stars/catalogs/gaia`.
  - Auth: Not required by the application code.
  - Configuration: Gaia tile-pack directories are loaded from filesystem paths by `src/main/java/kepplr/stars/catalogs/gaia/GaiaCatalog.java`; helper command generation exists in `src/main/java/kepplr/stars/catalogs/gaia/tools/GaiaCsvToTilePack.java`.
- Yale Bright Star Catalog - Packaged offline star catalog.
  - SDK/Client: Local resource reader in `src/main/java/kepplr/stars/catalogs/yaleBSC/YaleBrightStarCatalog.java`.
  - Auth: Not required.
  - Data file: `src/main/resources/kepplr/stars/catalogs/yaleBSC/ybsc5.gz`.

**Build and Package Resolution:**
- Maven repositories - Resolve Java dependencies declared in `pom.xml`.
  - SDK/Client: Maven.
  - Auth: Not detected. No `.m2/settings.xml`, `.npmrc`, `.pypirc`, or repository credential file is part of the repo.
- PyPI - Documentation/package helper installs Python packages during `mkPackage.bash`.
  - SDK/Client: `python3 -m pip`.
  - Auth: Not required for the public packages used (`wheel`, `sphinx`, `sphinx-theme-pd`, `Pillow`).

**Optional Asset Sources:**
- NASA 3D Resources - Documentation points users to external spacecraft model downloads.
  - SDK/Client: Manual download; no runtime client in Java code.
  - Auth: Not required.
  - References: `src/main/resources/resources/shapes/README.md`, `doc/python_tools.rst`, and `src/main/python/README.md`.
- JHUAPL SBMT shared files - Documentation includes example shape-model downloads.
  - SDK/Client: Manual `curl` examples in `src/main/resources/resources/shapes/README.md` and `doc/python_tools.rst`.
  - Auth: Not required.
- NAIF SPICE Toolkit tools - Optional `dskexp` executable used by the Python converter for `.dsk`/`.bds` shape models.
  - SDK/Client: External command invoked by `src/main/python/apps/convert_to_normalized_glb.py`.
  - Auth: Not required.

## Data Storage

**Databases:**
- Not detected.
  - Connection: Not applicable.
  - Client: Not applicable.

**File Storage:**
- Local filesystem only.
  - Runtime resources live under `src/main/resources/resources` in source and are expected under `resources` in packaged runtime configuration.
  - Output files default under `outputRoot = output` via `src/main/java/kepplr/config/KEPPLRConfigBlock.java`.
  - PNG/movie capture output is managed by `src/main/java/kepplr/core/CaptureService.java` and app tooling such as `src/main/java/kepplr/apps/PngToMovie.java`.
  - SPICE kernels are local files referenced by `src/main/resources/resources/spice/kepplr.tm` and loaded by `src/main/java/kepplr/ephemeris/spice/SpiceBundle.java`.
  - Gaia tile packs are local files (`gaia.properties`, `gaia.idx`, `gaia.dat`) loaded by `src/main/java/kepplr/stars/catalogs/gaia/GaiaCatalog.java`.

**Caching:**
- In-memory Gaia tile cache in `src/main/java/kepplr/stars/catalogs/gaia/GaiaCatalog.java`.
- Thread-local ephemeris instances in `src/main/java/kepplr/config/KEPPLRConfiguration.java`.
- No Redis, Memcached, database-backed cache, or external cache service detected.

## Authentication & Identity

**Auth Provider:**
- None.
  - Implementation: Desktop/local application with no login, user identity, OAuth, SSO, API tokens, or role model detected.
  - Secrets: No environment-variable auth usage detected in `src/main/java`, `src/main/python`, `src/main/bash`, `doc`, `README.md`, `pom.xml`, or `mkPackage.bash`.

## Monitoring & Observability

**Error Tracking:**
- None external.

**Logs:**
- Local Log4j2 logging through `org.apache.logging.log4j` imports across `src/main/java`.
- Log level and pattern are configurable through `logLevel` and `logFormat` in `src/main/java/kepplr/config/KEPPLRConfigBlock.java`.
- Runtime logging is applied by `src/main/java/kepplr/util/Log4j2Configurator.java` and `src/main/java/kepplr/config/KEPPLRConfiguration.java`.
- UI log display exists in `src/main/java/kepplr/ui/LogWindow.java` and `src/main/java/kepplr/ui/LogAppender.java`.

## CI/CD & Deployment

**Hosting:**
- None detected. This is a desktop Java application packaged locally by `mkPackage.bash`.

**CI Pipeline:**
- None detected. No `.github/workflows`, `.gitlab-ci.yml`, `.circleci`, Jenkinsfile, or other CI config was found.

**Distribution:**
- `mkPackage.bash` builds a JAR, copies dependencies, generates per-app launch scripts, copies Python tools, and builds Sphinx/Javadoc docs into package directories.
- Maven packaging produces `target/KEPPLR.jar` and dependency directories via `pom.xml`.

## Environment Configuration

**Required env vars:**
- None detected for application configuration.
- Render/integration tests set process environment variables internally through Maven Failsafe:
  - `GLFW_PLATFORM=x11` in `pom.xml`.
  - `LIBGL_ALWAYS_SOFTWARE=1` in `pom.xml`.
- Generated Linux launch scripts in `mkPackage.bash` export desktop graphics compatibility variables:
  - `GDK_BACKEND=x11`
  - `XDG_SESSION_TYPE=x11`
  - `_JAVA_AWT_WM_NONREPARENTING=1`

**Secrets location:**
- Not applicable.
- No `.env`, secret, credential, key, or package-manager auth files were detected in the shallow repo scan.
- Do not introduce secrets into `.planning/codebase`; application configuration should remain path/property based unless a future integration explicitly requires secret management.

## Webhooks & Callbacks

**Incoming:**
- None.

**Outgoing:**
- None at runtime.
- Build/package helper scripts perform outbound downloads only when explicitly run:
  - `src/main/resources/resources/spice/spk/getSPK.bash` uses `curl` to download public NAIF SPK kernels.
  - `mkPackage.bash` uses `pip` to install public documentation dependencies.

---

*Integration audit: 2026-04-25*
