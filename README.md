# KEPPLR
**Kernel-based Ephemeris Platform for PLanetary Rendering**

KEPPLR is a 3D interactive solar system simulator written in **Java**, powered by **NASA NAIF SPICE** kernels for
high-precision ephemeris and frame calculations.  Inspired by tools like *Cosmographia*, KEPPLR provides a real-time,
physically accurate visualization of planetary positions, spacecraft trajectories, natural satellites, and reference
frames.

---

## Getting Started

### Requirements
- **Java 21+** (e.g., [Eclipse Temurin](https://adoptium.net/), [Amazon Corretto](https://aws.amazon.com/corretto/), [Azul Zulu](https://www.azul.com/downloads/))
- **Maven 3.8+**
- **SPICE kernels** — a metakernel and kernel files are required at runtime (see `doc/` for details)

### Platforms
macOS (Intel and Apple Silicon), Linux (x86_64 and aarch64), and Windows.

### Building
Clone the repository:

```bash
git clone https://github.com/mcfiddish/KEPPLR.git
cd KEPPLR
```

To build and run tests:

```bash
mvn package
```

To create distribution packages:

```bash
./mkPackage.bash
```

This creates platform packages in a `dist` directory with sample launch scripts.
The launch scripts run the Java classes in the `apps` package, which includes
`PrintEphemeris`, `DumpConfig`, `GlbModelViewer`, and `PngToMovie`.

### Key Dependencies
All dependencies are managed by Maven and resolved automatically.

| Library | Purpose |
|---|---|
| [Picante](https://github.com/JHUAPL/Picante) | SPICE-compatible ephemeris and frame computations |
| [JMonkeyEngine](https://jmonkeyengine.org/) 3.8 | 3D rendering (LWJGL3/OpenGL) |
| [JavaFX](https://openjfx.io/) 25 | Control window UI |
| [Groovy](https://groovy-lang.org/) 3.0 | Scripting engine |
| [Jackfruit](https://github.com/JHUAPL/Jackfruit) | Annotation-driven configuration |

---

## Scripting (Groovy)

KEPPLR exposes a Groovy scripting API for automation and camera control. Scripts run with a bound `kepplr`
object that implements the public scripting methods.

See the `doc/` directory for detailed documentation.
