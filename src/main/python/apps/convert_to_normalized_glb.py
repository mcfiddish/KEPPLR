#!/usr/bin/env python3
"""
convert_to_normalized_glb.py

Purpose
-------
Convert assorted spacecraft model formats into a *normalized* glTF Binary (.glb) that is
more likely to load consistently in engines like jMonkeyEngine (jME), by:

1) Importing the source model into Blender.
2) Ensuring referenced textures are usable (e.g., converting DDS -> PNG when possible).
3) Optionally handling missing CMOD textures in a Cosmographia-compatible way.
4) Exporting a single output .glb.

This script is designed to run *inside* Blender (it uses `bpy`). You execute it with
Blender's `-b -P` options and pass script arguments after a `--` separator.

Coordinate-frame note (important)
---------------------------------
glTF defines a canonical coordinate system (right-handed, +Y up, -Z forward). Blender's
scene is Z-up. When exporting glTF with `export_yup=True` (spec-correct glTF), Blender's
exporter will apply a fixed basis conversion (effectively an X-axis rotation) so the
result conforms to glTF's convention.

For spacecraft and other SPICE-framed assets, you often want to treat the model's
*intrinsic* vertex basis (as imported into Blender) as the spacecraft body-fixed frame,
and then apply SPICE frame rotations at runtime.

To preserve that intent while still producing spec-correct glTF, this script injects a
metadata quaternion into the exported GLB at:

  asset.extras.kepplr.modelToBodyFixedQuat

This quaternion maps from exported glTF model-space into the "body-fixed" model-space
you intend to use in KEPPLR (i.e., the intrinsic basis as imported into Blender).

Coordinate handling
-------------------
JME's glTF loader handles the glTF Y-up basis conversion via the scene graph, so
vertices loaded from the exported GLB are already in their original frame (e.g., the
body-fixed frame of an OBJ). No format-based basis correction quaternion is needed.

The default modelToBodyFixedQuat is therefore identity (0,0,0,1) for all source formats.

If a particular model's vertex frame does not match the expected SPICE body-fixed frame,
use --apply-rotation x,y,z,angle_deg to inject a correction quaternion. KEPPLR applies
this quaternion once at load time via glbModelRoot.setLocalRotation().

Supported input formats
-----------------------
- `.glb` / `.gltf`
    Imported directly via Blender's built-in glTF importer.

- `.3ds`
    Always converted via **assimp** to an intermediate `.glb`, then imported.
    This avoids relying on Blender add-ons for 3DS import.

- `.cmod` (Cosmographia/Celestia model)
    Converted via **cmodconvert** to `.obj` + `.mtl` in a temporary folder, then imported.

    CMOD models frequently reference "texture filenames" in the generated MTL
    (e.g., `DARKGREY.DDS`, `NH_FOIL2.DDS`) that may *not* be distributed with the model.
    Cosmographia can still render such models using the MTL's per-material constants
    (`Kd`, `Ks`, `Ns`) even when image files are missing.

    To match that behavior, this script supports a missing-texture policy:
      - `strip` (default): remove `map_*` lines and rely on `Kd/Ks/Ns` colors.
      - `swatch`: generate 1×1 PNG swatches from each material's `Kd` and replace `map_Kd`
                 so the exported GLB has actual image textures (solid-color), but still works
                 even without the original images.

- `.obj` (Wavefront OBJ)
    Imported via Blender's built-in OBJ importer (`bpy.ops.wm.obj_import`). If a matching `.mtl`
    file is referenced and textures are available, Blender will load them and this script will
    normalize any loaded images to PNG on export.

- `.dsk` / `.bds` (NAIF SPICE Digital Shape Kernel)
    Converted via NAIF **dskexp** to temporary OBJ/vertex-facet text, then imported.
    This supports DSK type 2 triangular plate model segments, matching DSKEXP's scope.
    DSK vertex coordinates are already in kilometers in the segment's body-fixed frame.

External dependencies
---------------------
This script relies on a few command-line tools. You only need the ones that match the
formats you are converting.

1) Blender (required)
   - Blender 5.0+ recommended.
   - This script must be run with Blender's Python (`bpy`), not system Python.

2) assimp (required for `.3ds` inputs)
   - Linux (Debian/Ubuntu): `sudo apt install assimp-utils`
   - macOS (Homebrew): `brew install assimp`

3) cmodconvert (required for `.cmod` inputs)
   - Project: https://github.com/ajtribick/cmodconvert
   - Provide its path using `--cmodconvert /path/to/cmodconvert`

4) ImageMagick (optional; used to convert CMOD `.dds` textures to `.png` when available)
   - Linux (Debian/Ubuntu): `sudo apt install imagemagick`
   - macOS (Homebrew): `brew install imagemagick`
   - If `magick` is not on PATH, pass `--magick /path/to/magick`.
     Some distros expose the command as `convert`; this script tries both.

5) dskexp (required for `.dsk` / `.bds` inputs)
   - Included with the NAIF SPICE Toolkit.
   - Provide its path using `--dskexp /path/to/dskexp`, or put `dskexp` on PATH.

No other external tools are required.

Command-line options
--------------------
All script options must come *after* `--` (Blender's argument separator).

Required:
  --input, -i <file|dir>        Input model file or directory
  --output, -o <file|dir>       Output GLB file (single-file mode) or directory (dir mode)

Optional:
  --recursive                   If --input is a directory, recurse into subdirectories
  --scale <float>               Uniform scale factor applied to the imported model before export
  --assimp <path>               Path to assimp executable (default: 'assimp' on PATH)
  --cmodconvert <path>          Path to cmodconvert executable (required for .cmod inputs)
  --dskexp <path>               Path to NAIF dskexp executable (default: 'dskexp' on PATH)
  --dsk-prec <1..17>            Vertex mantissa precision passed to dskexp (default: 17)
  --magick <path>               Path to ImageMagick binary (default: try 'magick' then 'convert')
  --texture-dir <dir>           (Repeatable) Add search roots for CMOD-referenced textures
  --texture-recursive           If set, search each --texture-dir recursively
  --missing-texture-mode <mode> CMOD-only: 'strip' (default) or 'swatch'
  --apply-rotation x,y,z,a      Rotation to inject as modelToBodyFixedQuat (axis-angle form).
                                  x,y,z is the rotation axis (need not be unit length),
                                  a is the angle in degrees. Use this when a model's vertex
                                  coordinates are not in the expected SPICE body-fixed frame.
                                  Mutually exclusive with --apply-quaternion.
  --apply-quaternion w,x,y,z    Rotation to inject as modelToBodyFixedQuat (quaternion form).
                                  Components are in w,x,y,z order. Cosmographia's "meshRotation"
                                  values from SSC/JSON config files can be used directly here.
                                  Mutually exclusive with --apply-rotation.

Behavior notes
--------------
- Directory mode preserves relative paths under the output directory, replacing extensions with `.glb`.
- Temporary work files are written under:
    <output>/.tmp_work/<relative-path>/<stem>/
  You can inspect those folders when debugging conversion issues.
- The exported GLB uses Blender's default glTF exporter settings plus `export_apply=True`.
- This script normalizes Blender image datablocks to PNG files under:
    <tmp_root>/png_textures/
  and exports the GLB referencing those PNGs.
- The exported GLB will contain KEPPLR metadata under `asset.extras.kepplr`, including
  `modelToBodyFixedQuat` as described above.

  Examples
  --------
  1) Convert a single GLB/GLTF file:
     blender -b -P convert_to_normalized_glb.py -- \
       --input "Voyager Probe (A).glb" \
       --output "Voyager Probe (A)-normalized.glb"

  2) Convert a directory recursively (GLB/GLTF/OBJ/3DS/CMOD):
     blender -b -P convert_to_normalized_glb.py -- \
       --input ./spacecraft \
       --output ./normalized \
       --recursive \
       --cmodconvert ~/local/cmodconvert/cmodconvert

  3) Convert CMODs and search for their textures under a data directory:
     blender -b -P convert_to_normalized_glb.py -- \
       --input ./cosmographia \
       --output ./normalized \
       --recursive \
       --cmodconvert ~/local/cmodconvert/cmodconvert \
       --texture-dir ./cosmographia/data --texture-recursive

  4) CMOD missing textures: match Cosmographia behavior (recommended default):
     blender -b -P convert_to_normalized_glb.py -- \
       --input ./models \
       --output ./normalized \
       --recursive \
       --cmodconvert ~/local/cmodconvert/cmodconvert \
       --missing-texture-mode strip

  5) CMOD missing textures: generate solid-color texture swatches:
     blender -b -P convert_to_normalized_glb.py -- \
       --input ./models \
       --output ./normalized \
       --recursive \
       --cmodconvert ~/local/cmodconvert/cmodconvert \
       --missing-texture-mode swatch \
       --scale 1.0

  6) Convert a NAIF SPICE DSK/BDS triangular plate model:
     blender -b -P convert_to_normalized_glb.py -- \
       --input phobos.bds \
       --output phobos.glb \
       --dskexp ~/naif/toolkit/exe/dskexp

  Troubleshooting
  ---------------
  - If CMOD textures are reported missing, that usually means the model package does not
    include the referenced image files. Use `--missing-texture-mode strip` (default) to
    rely on material colors, or add `--texture-dir ...` to point at a texture library.
  - If DDS->PNG conversion fails, try installing ImageMagick or passing `--magick`.
  - If 3DS conversion fails, verify `assimp` is installed and on PATH, or pass `--assimp`.
  - If DSK conversion fails, verify the file contains type 2 plate-model segments and
    `dskexp` is installed and on PATH, or pass `--dskexp`.

"""
import bpy
import sys
import shutil
import subprocess
from pathlib import Path
import re
import zlib
import struct
import json
import math


def log(msg: str):
    print(f"[convert] {msg}")


def parse_args(argv):
    if "--" not in argv:
        return None
    user_args = argv[argv.index("--") + 1 :]

    input_path = None
    output_path = None
    recursive = False
    cmodconvert_path = None
    assimp_path = None
    dskexp_path = None
    dsk_precision = 17
    magick_path = None
    texture_dirs = []
    texture_recursive = False
    missing_texture_mode = "strip"  # strip | swatch
    scale_factor = 1.0
    apply_rotation = None  # None or (x, y, z, angle_deg)
    apply_quaternion = None  # None or (w, x, y, z)

    i = 0
    while i < len(user_args):
        a = user_args[i]
        if a in ("--input", "-i"):
            i += 1
            input_path = Path(user_args[i])
        elif a in ("--output", "-o"):
            i += 1
            output_path = Path(user_args[i])
        elif a == "--recursive":
            recursive = True
        elif a == "--cmodconvert":
            i += 1
            cmodconvert_path = Path(user_args[i])
        elif a == "--assimp":
            i += 1
            assimp_path = Path(user_args[i])
        elif a == "--dskexp":
            i += 1
            dskexp_path = Path(user_args[i])
        elif a == "--dsk-prec":
            i += 1
            try:
                dsk_precision = int(user_args[i])
            except ValueError:
                raise SystemExit("--dsk-prec must be an integer in the range 1..17")
            if dsk_precision < 1 or dsk_precision > 17:
                raise SystemExit("--dsk-prec must be in the range 1..17")
        elif a == "--magick":
            i += 1
            magick_path = Path(user_args[i])
        elif a == "--texture-dir":
            i += 1
            texture_dirs.append(Path(user_args[i]))
        elif a == "--texture-recursive":
            texture_recursive = True
        elif a == "--missing-texture-mode":
            i += 1
            missing_texture_mode = user_args[i].strip().lower()
            if missing_texture_mode not in ("strip", "swatch"):
                raise SystemExit("--missing-texture-mode must be 'strip' or 'swatch'")
        elif a == "--scale":
            i += 1
            try:
                scale_factor = float(user_args[i])
            except ValueError:
                raise SystemExit("--scale must be a floating point number")
            if not (scale_factor > 0.0):
                raise SystemExit("--scale must be > 0")
        elif a == "--apply-rotation":
            i += 1
            try:
                parts = user_args[i].split(",")
                if len(parts) != 4:
                    raise ValueError
                apply_rotation = (float(parts[0]), float(parts[1]), float(parts[2]), float(parts[3]))
            except (ValueError, IndexError):
                raise SystemExit("--apply-rotation must be x,y,z,angle_deg (e.g. 1,0,0,90)")
        elif a == "--apply-quaternion":
            i += 1
            try:
                parts = user_args[i].split(",")
                if len(parts) != 4:
                    raise ValueError
                apply_quaternion = (float(parts[0]), float(parts[1]), float(parts[2]), float(parts[3]))
            except (ValueError, IndexError):
                raise SystemExit("--apply-quaternion must be w,x,y,z (e.g. 0.707,-0.707,0,0)")
        else:
            raise SystemExit(f"Unknown arg: {a}")
        i += 1

    if input_path is None or output_path is None:
        raise SystemExit(
            "Usage: --input <file|dir> --output <file|dir> [--recursive] "
            "[--cmodconvert <path>] [--assimp <path>] [--magick <path>] "
            "[--dskexp <path>] [--dsk-prec <1..17>] "
            "[--texture-dir <dir>]... [--texture-recursive] "
            "[--missing-texture-mode strip|swatch] [--scale <float>] "
            "[--apply-rotation x,y,z,angle_deg] [--apply-quaternion w,x,y,z]"
        )

    if apply_rotation is not None and apply_quaternion is not None:
        raise SystemExit("--apply-rotation and --apply-quaternion are mutually exclusive")

    return (
        input_path,
        output_path,
        recursive,
        cmodconvert_path,
        assimp_path,
        dskexp_path,
        dsk_precision,
        magick_path,
        texture_dirs,
        texture_recursive,
        missing_texture_mode,
        scale_factor,
        apply_rotation,
        apply_quaternion,
    )


def reset_scene():
    bpy.ops.wm.read_factory_settings(use_empty=True)
    if bpy.context.scene is None:
        raise RuntimeError("No active scene after factory reset.")


def ensure_dir(p: Path):
    p.mkdir(parents=True, exist_ok=True)


def sanitize_name(name: str) -> str:
    safe = "".join(c if c.isalnum() or c in "._- " else "_" for c in name).strip()
    return safe if safe else "image"


def convert_3ds_with_assimp(
    src_3ds: Path, tmp_dir: Path, assimp_path: Path | None
) -> Path:
    """Converts 3DS -> GLB using assimp, then returns the GLB path."""
    ensure_dir(tmp_dir)
    out_glb = tmp_dir / (src_3ds.stem + ".glb")
    assimp = str(assimp_path) if assimp_path else "assimp"

    log(f"3DS: converting via {assimp} -> {out_glb.name}")

    try:
        subprocess.run(
            [assimp, "export", str(src_3ds), str(out_glb)],
            check=True,
            capture_output=True,
            text=True,
        )
    except FileNotFoundError:
        raise RuntimeError(
            "assimp not found. Install: sudo apt install assimp-utils (or pass --assimp <path>)"
        )
    except subprocess.CalledProcessError as e:
        raise RuntimeError(
            "assimp export failed.\n"
            f"Command: {e.cmd}\n"
            f"Exit code: {e.returncode}\n"
            f"STDOUT:\n{e.stdout}\n"
            f"STDERR:\n{e.stderr}\n"
        )

    if not out_glb.exists():
        raise RuntimeError(f"assimp output not found: {out_glb}")

    return out_glb


def convert_dsk_with_dskexp(
    src_dsk: Path, tmp_dir: Path, dskexp_path: Path | None, dsk_precision: int
) -> list[Path]:
    """Convert DSK/BDS -> OBJ using NAIF dskexp, returning one OBJ per segment."""
    ensure_dir(tmp_dir)
    base_obj = tmp_dir / (src_dsk.stem + ".obj")
    dskexp = str(dskexp_path) if dskexp_path else "dskexp"

    for old in tmp_dir.glob(base_obj.name + "*"):
        if old.is_file():
            old.unlink()

    log(f"DSK: exporting via {dskexp} -> {base_obj.name}")

    try:
        subprocess.run(
            [
                dskexp,
                "-dsk",
                str(src_dsk),
                "-text",
                str(base_obj),
                "-format",
                "obj",
                "-prec",
                str(dsk_precision),
            ],
            check=True,
            capture_output=True,
            text=True,
        )
    except FileNotFoundError:
        raise RuntimeError(
            "dskexp not found. Install the NAIF SPICE Toolkit, or pass --dskexp <path>"
        )
    except subprocess.CalledProcessError as e:
        raise RuntimeError(
            "dskexp export failed.\n"
            f"Command: {e.cmd}\n"
            f"Exit code: {e.returncode}\n"
            f"STDOUT:\n{e.stdout}\n"
            f"STDERR:\n{e.stderr}\n"
        )

    exported = [p for p in tmp_dir.glob(base_obj.name + "*") if p.is_file()]
    exported.sort(key=lambda p: (len(p.name), p.name))
    if not exported:
        raise RuntimeError(f"dskexp output not found: {base_obj}")

    obj_paths = []
    for index, exported_obj in enumerate(exported):
        normalized = tmp_dir / f"{src_dsk.stem}_segment_{index + 1}.obj"
        if exported_obj != normalized:
            exported_obj.rename(normalized)
        obj_paths.append(normalized)

    log(f"DSK: exported {len(obj_paths)} OBJ segment file(s)")
    return obj_paths


_MTL_MAP_RE = re.compile(
    r"^\s*(map_Kd|map_Ka|map_Ks|map_Bump|bump|map_d)\s+(.+?)\s*$", re.IGNORECASE
)
_MTL_NEWMTL_RE = re.compile(r"^\s*newmtl\s+(.+?)\s*$", re.IGNORECASE)
_MTL_KD_RE = re.compile(
    r"^\s*Kd\s+([0-9.eE+-]+)\s+([0-9.eE+-]+)\s+([0-9.eE+-]+)\s*$", re.IGNORECASE
)


def _extract_texture_token(rhs: str) -> list[str]:
    """Return candidate filenames from the RHS of a map_* line."""
    rhs = rhs.strip()

    m = re.search(r"\"([^\"]+)\"", rhs)
    cands = []
    if m:
        cands.append(m.group(1).strip())

    cands.append(rhs)
    parts = rhs.split()
    if parts:
        cands.append(parts[-1].strip())

    expanded = []
    for c in cands:
        if not c:
            continue
        expanded.append(c)
        expanded.append(c.replace(" ", "_"))
        expanded.append(c.replace("_", " "))
    seen = set()
    out = []
    for c in expanded:
        key = Path(c).name.lower()
        if key not in seen:
            seen.add(key)
            out.append(Path(c).name)
    return out


def _parse_mtl_texture_refs(mtl_path: Path) -> list[str]:
    refs = []
    if not mtl_path.exists():
        return refs
    for line in mtl_path.read_text(errors="ignore").splitlines():
        m = _MTL_MAP_RE.match(line)
        if not m:
            continue
        rhs = m.group(2)
        refs.extend(_extract_texture_token(rhs))
    seen = set()
    out = []
    for r in refs:
        key = r.lower()
        if key not in seen:
            seen.add(key)
            out.append(r)
    return out


def _build_texture_index(roots: list[Path], recursive: bool) -> dict[str, Path]:
    """Map lowercase filename -> Path for quick lookups."""
    index: dict[str, Path] = {}
    for root in roots:
        if not root.exists():
            continue
        if root.is_file():
            index[root.name.lower()] = root
            continue
        it = root.rglob("*") if recursive else root.glob("*")
        for p in it:
            if p.is_file():
                key = p.name.lower()
                if key not in index:
                    index[key] = p
    return index


def _convert_dds_to_png(src_dds: Path, dst_png: Path, magick_path: Path | None):
    """Convert DDS -> PNG via ImageMagick. Tries magick then convert."""
    candidates = []
    if magick_path:
        candidates.append(str(magick_path))
    candidates.extend(["magick", "convert"])

    last_err = None
    for exe in candidates:
        try:
            subprocess.run(
                [exe, str(src_dds), str(dst_png)],
                check=True,
                capture_output=True,
                text=True,
            )
            return
        except FileNotFoundError as e:
            last_err = e
            continue
        except subprocess.CalledProcessError as e:
            last_err = e
            continue

    raise RuntimeError(
        "DDS->PNG conversion failed. Install ImageMagick (sudo apt install imagemagick), "
        "or pass --magick /path/to/magick. Last error: "
        + (str(last_err) if last_err else "unknown")
    )


def _rewrite_mtl_with_mapping(mtl_path: Path, mapping: dict[str, str]):
    """Rewrite map_* RHS filenames to the mapped local filenames (case-insensitive)."""
    lines = mtl_path.read_text(errors="ignore").splitlines()
    out_lines = []
    for line in lines:
        m = _MTL_MAP_RE.match(line)
        if not m:
            out_lines.append(line)
            continue
        key = m.group(1)
        rhs = m.group(2).strip()
        cands = _extract_texture_token(rhs)
        chosen = None
        for c in cands:
            repl = mapping.get(c.lower())
            if repl:
                chosen = repl
                break
        if chosen:
            out_lines.append(f"{key} {chosen}")
        else:
            out_lines.append(line)
    mtl_path.write_text("\n".join(out_lines) + "\n", encoding="utf-8")


def _png_chunk(chunk_type: bytes, data: bytes) -> bytes:
    return (
        struct.pack(">I", len(data))
        + chunk_type
        + data
        + struct.pack(">I", zlib.crc32(chunk_type + data) & 0xFFFFFFFF)
    )


def write_png_1x1_rgb(path: Path, r: float, g: float, b: float):
    """Write a minimal 1x1 RGB PNG. r,g,b are floats in [0,1]."""
    rr = max(0, min(255, int(round(r * 255.0))))
    gg = max(0, min(255, int(round(g * 255.0))))
    bb = max(0, min(255, int(round(b * 255.0))))

    sig = b"\x89PNG\r\n\x1a\n"
    ihdr = struct.pack(">IIBBBBB", 1, 1, 8, 2, 0, 0, 0)
    raw = bytes([0, rr, gg, bb])
    idat = zlib.compress(raw, level=9)

    png = sig
    png += _png_chunk(b"IHDR", ihdr)
    png += _png_chunk(b"IDAT", idat)
    png += _png_chunk(b"IEND", b"")

    path.write_bytes(png)


def fix_missing_mtl_textures(
    mtl_path: Path, obj_dir: Path, missing_texture_mode: str
) -> int:
    """Strip map_* lines or generate Kd-based swatches; returns swatches written."""
    lines = mtl_path.read_text(errors="ignore").splitlines()
    out_lines = []

    current_mtl = None
    current_kd = (1.0, 1.0, 1.0)
    swatches_written = 0

    for line in lines:
        m_new = _MTL_NEWMTL_RE.match(line)
        if m_new:
            current_mtl = m_new.group(1).strip()
            current_kd = (1.0, 1.0, 1.0)
            out_lines.append(line)
            continue

        m_kd = _MTL_KD_RE.match(line)
        if m_kd:
            try:
                current_kd = (
                    float(m_kd.group(1)),
                    float(m_kd.group(2)),
                    float(m_kd.group(3)),
                )
            except Exception:
                pass
            out_lines.append(line)
            continue

        m_map = _MTL_MAP_RE.match(line)
        if m_map:
            map_key = m_map.group(1).lower()
            if missing_texture_mode == "strip":
                continue

            if missing_texture_mode == "swatch":
                if map_key == "map_kd":
                    mat_name = sanitize_name(current_mtl or "material")
                    swatch_name = f"{mat_name}_Kd.png"
                    swatch_path = obj_dir / swatch_name
                    if not swatch_path.exists():
                        write_png_1x1_rgb(
                            swatch_path, current_kd[0], current_kd[1], current_kd[2]
                        )
                        swatches_written += 1
                    out_lines.append(f"map_Kd {swatch_name}")
                    continue
                else:
                    continue

        out_lines.append(line)

    mtl_path.write_text("\n".join(out_lines) + "\n", encoding="utf-8")
    return swatches_written



def _resolve_texture_ref(ref: str, texture_index: dict[str, Path]) -> Path | None:
    """Resolve a texture reference from an MTL to an actual file on disk.

    CMOD/OBJ MTLs are often messy:
    - filenames may use spaces vs underscores inconsistently (e.g., 'Main txt.png' vs 'Main_txt.png')
    - extensions may differ from what is actually shipped (e.g., MTL references .dds but package ships .png)
    - casing may differ

    We resolve by trying:
    1) Exact match (case-insensitive via texture_index)
    2) Space/underscore variants
    3) Extension fallbacks on the same stem: .png/.jpg/.jpeg/.dds/.tga/.webp
    """
    name = Path(ref).name
    cand_names: list[str] = []

    def add(n: str):
        n = Path(n).name
        if not n:
            return
        key = n.lower()
        if key not in {c.lower() for c in cand_names}:
            cand_names.append(n)

    add(name)
    add(name.replace(" ", "_"))
    add(name.replace("_", " "))

    p = Path(name)
    stem = p.stem
    suffix = p.suffix.lower()

    stem_variants = {stem, stem.replace(" ", "_"), stem.replace("_", " ")}
    exts = [".png", ".jpg", ".jpeg", ".dds", ".tga", ".webp"]

    # Try referenced extension first (if any), then fall back.
    if suffix:
        for sv in stem_variants:
            add(sv + suffix)

    for ext in exts:
        for sv in stem_variants:
            add(sv + ext)

    for cand in cand_names:
        hit = texture_index.get(cand.lower())
        if hit is not None:
            return hit
    return None

def run_cmodconvert(
    input_cmod: Path,
    obj_dir: Path,
    cmodconvert_path: Path,
    magick_path: Path | None,
    texture_index: dict[str, Path],
    missing_texture_mode: str,
):
    """Run cmodconvert; gather textures and convert DDS->PNG; apply missing policy."""
    ensure_dir(obj_dir)

    out_obj = obj_dir / f"{input_cmod.stem}.obj"
    out_mtl = obj_dir / f"{input_cmod.stem}.mtl"

    for p in (out_obj, out_mtl):
        try:
            if p.exists():
                p.unlink()
        except Exception:
            pass

    cmd = [str(cmodconvert_path), str(input_cmod)]
    log(f"CMOD: converting via {cmodconvert_path} (cwd={obj_dir})")

    try:
        subprocess.run(
            cmd, check=True, cwd=str(obj_dir), capture_output=True, text=True
        )
    except subprocess.CalledProcessError as e:
        raise RuntimeError(
            "cmodconvert failed.\n"
            f"Command: {e.cmd}\n"
            f"Exit code: {e.returncode}\n"
            f"STDOUT:\n{e.stdout}\n"
            f"STDERR:\n{e.stderr}\n"
        )

    if not out_obj.exists():
        alt_obj = input_cmod.with_suffix(".obj")
        alt_mtl = input_cmod.with_suffix(".mtl")
        if alt_obj.exists():
            shutil.move(str(alt_obj), str(out_obj))
            if alt_mtl.exists():
                shutil.move(str(alt_mtl), str(out_mtl))
        else:
            raise RuntimeError(
                f"cmodconvert reported success but did not create {out_obj.name}"
            )

    if not out_mtl.exists():
        log(
            "WARNING: cmodconvert did not create an MTL file; textures/materials may be lost."
        )
        return out_obj

    refs = _parse_mtl_texture_refs(out_mtl)
    if not refs:
        log("CMOD: no texture references found in MTL.")
        return out_obj

    mapping: dict[str, str] = {}
    missing = []

    for ref in refs:
        src = _resolve_texture_ref(ref, texture_index)
        if src is None:
            missing.append(ref)
            continue

        dst = obj_dir / src.name
        if not dst.exists():
            shutil.copy2(src, dst)

        if dst.suffix.lower() == ".dds":
            png_name = dst.with_suffix(".png").name
            png_path = obj_dir / png_name
            if not png_path.exists():
                try:
                    _convert_dds_to_png(dst, png_path, magick_path)
                except Exception as e:
                    log(f"WARNING: DDS->PNG failed for {dst.name}: {e}")
                    continue
            mapping[ref.lower()] = png_name
        else:
            mapping[ref.lower()] = dst.name

    if mapping:
        _rewrite_mtl_with_mapping(out_mtl, mapping)

    if missing:
        log("WARNING: Missing CMOD textures: " + ", ".join(missing))
        if missing_texture_mode == "strip":
            log(
                "         Applying --missing-texture-mode strip (removing map_* lines; keeping Kd/Ks)."
            )
            fix_missing_mtl_textures(out_mtl, obj_dir, "strip")
        else:
            log(
                "         Applying --missing-texture-mode swatch (writing 1x1 PNG swatches from Kd)."
            )
            n = fix_missing_mtl_textures(out_mtl, obj_dir, "swatch")
            log(f"         Wrote {n} swatch PNG(s) into: {obj_dir}")
        log(
            "         If you later locate the real textures (or if the MTL references .dds but your package ships .png), \
         rerun with --texture-dir <dir> (and optionally --texture-recursive) so they can be discovered."
        )

    return out_obj


def normalize_images_to_png(work_dir: Path):
    """Write all image datablocks with pixels to PNG files and repoint them."""
    ensure_dir(work_dir)
    converted = 0

    for img in list(bpy.data.images):
        try:
            w, h = img.size
            if w == 0 or h == 0:
                continue
        except Exception:
            continue

        safe_name = sanitize_name(img.name)
        out_path = work_dir / f"{safe_name}.png"

        try:
            try:
                _ = img.pixels[0]
            except Exception:
                pass

            img.filepath_raw = str(out_path)
            img.file_format = "PNG"

            try:
                img.save()
            except Exception:
                img.save_render(filepath=str(out_path), scene=bpy.context.scene)

            img.filepath = str(out_path)
            img.filepath_raw = str(out_path)
            img.file_format = "PNG"

            try:
                if img.packed_file is not None:
                    img.unpack(method="WRITE_LOCAL")
            except Exception:
                pass

            converted += 1
        except Exception as e:
            log(f"WARNING: Could not write PNG for image '{img.name}': {e}")

    log(f"Converted/normalized {converted} images to PNG in {work_dir}")


def _pad4(b: bytes) -> bytes:
    return b + (b" " * ((4 - (len(b) % 4)) % 4))


def inject_kepplr_asset_extras(glb_path: Path, quat_xyzw: list[float]):
    """Inject/merge asset.extras.kepplr.modelToBodyFixedQuat into a GLB file."""
    data = glb_path.read_bytes()
    if len(data) < 12:
        raise RuntimeError(f"Invalid GLB (too small): {glb_path}")

    magic, version, length = struct.unpack_from("<4sII", data, 0)
    if magic != b"glTF":
        raise RuntimeError(f"Not a GLB (bad magic): {glb_path}")

    off = 12
    chunks = []
    json_chunk_index = None

    while off + 8 <= len(data):
        chunk_len, chunk_type = struct.unpack_from("<I4s", data, off)
        off += 8
        chunk_data = data[off : off + chunk_len]
        off += chunk_len
        chunks.append((chunk_type, chunk_data))
        if chunk_type == b"JSON":
            json_chunk_index = len(chunks) - 1

    if json_chunk_index is None:
        raise RuntimeError(f"GLB missing JSON chunk: {glb_path}")

    json_bytes = chunks[json_chunk_index][1]
    json_str = json_bytes.decode("utf-8").rstrip()
    doc = json.loads(json_str)

    asset = doc.setdefault("asset", {})
    extras = asset.setdefault("extras", {})
    kepplr = extras.setdefault("kepplr", {})

    kepplr["modelToBodyFixedQuat"] = {
        "type": "quaternion_xyzw",
        "value": [
            float(quat_xyzw[0]),
            float(quat_xyzw[1]),
            float(quat_xyzw[2]),
            float(quat_xyzw[3]),
        ],
        "note": "Apply once in runtime to map glTF model-space vectors into KEPPLR body-fixed model-space.",
    }

    new_json = json.dumps(doc, ensure_ascii=False, separators=(",", ":")).encode(
        "utf-8"
    )
    new_json = _pad4(new_json)

    chunks[json_chunk_index] = (b"JSON", new_json)

    out = bytearray()
    out += struct.pack("<4sII", b"glTF", version, 0)
    for ctype, cdata in chunks:
        out += struct.pack("<I4s", len(cdata), ctype)
        out += cdata
    struct.pack_into("<I", out, 8, len(out))
    glb_path.write_bytes(out)


def export_glb(out_file: Path):
    """Export GLB; filter kwargs to supported properties (Blender-version tolerant)."""
    ensure_dir(out_file.parent)

    desired = dict(
        filepath=str(out_file),
        export_format="GLB",
        export_apply=True,
        export_yup=True,
        export_texcoords=True,
        export_normals=True,
        export_materials="EXPORT",
        export_cameras=False,
        export_lights=False,
        export_animations=True,
        export_skins=True,
        export_morph=True,
        export_image_format="AUTO",
    )

    op = bpy.ops.export_scene.gltf
    supported = {p.identifier for p in op.get_rna_type().properties}
    filtered = {k: v for k, v in desired.items() if k in supported}

    bpy.ops.export_scene.gltf(**filtered)


def axis_angle_to_quat(x: float, y: float, z: float, angle_deg: float) -> list[float]:
    """Convert axis-angle (axis need not be unit) to quaternion [x, y, z, w]."""
    length = math.sqrt(x * x + y * y + z * z)
    if length < 1e-12:
        return [0.0, 0.0, 0.0, 1.0]
    ux, uy, uz = x / length, y / length, z / length
    half = math.radians(angle_deg) / 2.0
    s = math.sin(half)
    c = math.cos(half)
    return [ux * s, uy * s, uz * s, c]


def compute_model_to_bodyfixed_quat(
    apply_rotation: tuple[float, float, float, float] | None,
    apply_quaternion: tuple[float, float, float, float] | None,
) -> list[float]:
    """Compute the modelToBodyFixedQuat for the exported GLB.

    By default this is identity — JME's glTF loader handles the Y-up basis conversion
    via the scene graph, so the loaded vertices are already in their original frame.

    If --apply-rotation is specified (axis-angle), the quaternion is computed from it.
    If --apply-quaternion is specified (w,x,y,z), it is converted to glTF/JME order (x,y,z,w).
    """
    if apply_rotation is not None:
        return axis_angle_to_quat(*apply_rotation)
    if apply_quaternion is not None:
        w, x, y, z = apply_quaternion
        return [x, y, z, w]
    return [0.0, 0.0, 0.0, 1.0]


def import_model(
    path: Path,
    tmp_work_dir: Path,
    cmodconvert_path: Path | None,
    assimp_path: Path | None,
    dskexp_path: Path | None,
    dsk_precision: int,
    magick_path: Path | None,
    texture_index: dict[str, Path],
    missing_texture_mode: str,
):
    ext = path.suffix.lower()

    if ext in (".glb", ".gltf"):
        bpy.ops.import_scene.gltf(filepath=str(path))
        return

    if ext == ".obj":
        try:
            bpy.ops.wm.obj_import(filepath=str(path))
        except Exception as e:
            raise RuntimeError(
                "OBJ import failed. Your Blender build may not include 'bpy.ops.wm.obj_import'. "
                "Try upgrading Blender (5.0+ recommended). Under some Blender versions, the operator may be "
                "'bpy.ops.import_scene.obj'. "
                f"Original error: {e}"
            )
        return

    if ext == ".3ds":
        glb = convert_3ds_with_assimp(path, tmp_work_dir / "assimp_3ds", assimp_path)
        bpy.ops.import_scene.gltf(filepath=str(glb))
        return

    if ext == ".cmod":
        if not cmodconvert_path:
            raise RuntimeError("CMOD input requires --cmodconvert <path>")
        obj_dir = tmp_work_dir / "cmod_obj"
        out_obj = run_cmodconvert(
            path,
            obj_dir,
            cmodconvert_path,
            magick_path,
            texture_index,
            missing_texture_mode,
        )
        bpy.ops.wm.obj_import(filepath=str(out_obj))
        return

    if ext in (".dsk", ".bds"):
        obj_paths = convert_dsk_with_dskexp(
            path, tmp_work_dir / "dsk_obj", dskexp_path, dsk_precision
        )
        for obj_path in obj_paths:
            try:
                bpy.ops.wm.obj_import(filepath=str(obj_path))
            except Exception as e:
                raise RuntimeError(
                    "DSK OBJ import failed after dskexp conversion. Your Blender build may not include "
                    "'bpy.ops.wm.obj_import'. Try upgrading Blender (5.0+ recommended). Under some "
                    "Blender versions, the operator may be 'bpy.ops.import_scene.obj'. "
                    f"Original error: {e}"
                )
        return

    raise ValueError(f"Unsupported input extension: {ext}")


def apply_model_scale(scale_factor: float):
    if scale_factor == 1.0:
        return
    roots = [obj for obj in bpy.context.scene.objects if obj.parent is None]
    if not roots:
        return
    for obj in roots:
        obj.scale = (
            obj.scale[0] * scale_factor,
            obj.scale[1] * scale_factor,
            obj.scale[2] * scale_factor,
        )
    log(f"Applied uniform scale {scale_factor} to {len(roots)} root object(s)")


def collect_models(input_dir: Path, recursive: bool):
    exts = {".glb", ".gltf", ".obj", ".3ds", ".cmod", ".dsk", ".bds"}
    if recursive:
        return [
            p for p in input_dir.rglob("*") if p.is_file() and p.suffix.lower() in exts
        ]
    return [p for p in input_dir.iterdir() if p.is_file() and p.suffix.lower() in exts]


def convert_one(
    src: Path,
    out_file: Path,
    tmp_root: Path,
    cmodconvert_path: Path | None,
    assimp_path: Path | None,
    dskexp_path: Path | None,
    dsk_precision: int,
    magick_path: Path | None,
    texture_index: dict[str, Path],
    missing_texture_mode: str,
    scale_factor: float,
    apply_rotation: tuple[float, float, float, float] | None = None,
    apply_quaternion: tuple[float, float, float, float] | None = None,
):
    log(f"Converting: {src.name}")
    reset_scene()

    if tmp_root.exists():
        shutil.rmtree(tmp_root, ignore_errors=True)
    ensure_dir(tmp_root)

    import_model(
        src,
        tmp_root,
        cmodconvert_path,
        assimp_path,
        dskexp_path,
        dsk_precision,
        magick_path,
        texture_index,
        missing_texture_mode,
    )
    apply_model_scale(scale_factor)
    normalize_images_to_png(tmp_root / "png_textures")
    export_glb(out_file)

    quat = compute_model_to_bodyfixed_quat(apply_rotation, apply_quaternion)
    try:
        inject_kepplr_asset_extras(out_file, quat)
        log(f"Injected asset.extras.kepplr.modelToBodyFixedQuat = {quat}")
    except Exception as e:
        log(f"WARNING: Failed to inject KEPPLR extras into GLB: {e}")

    log(f"OK: wrote {out_file}")


def main():
    parsed = parse_args(sys.argv)
    if not parsed:
        raise SystemExit("Missing '--' args.")

    (
        input_path,
        output_path,
        recursive,
        cmodconvert_path,
        assimp_path,
        dskexp_path,
        dsk_precision,
        magick_path,
        texture_dirs,
        texture_recursive,
        missing_texture_mode,
        scale_factor,
        apply_rotation,
        apply_quaternion,
    ) = parsed

    input_path = input_path.expanduser().resolve()
    output_path = output_path.expanduser().resolve()
    if cmodconvert_path:
        cmodconvert_path = cmodconvert_path.expanduser().resolve()
    if assimp_path:
        assimp_path = assimp_path.expanduser().resolve()
    if dskexp_path:
        dskexp_path = dskexp_path.expanduser().resolve()
    if magick_path:
        magick_path = magick_path.expanduser().resolve()

    if not texture_dirs:
        # Default texture search behavior:
        # - If input is a directory, search within it (recursive) for referenced textures.
        # - If input is a single file, search next to the file (its parent directory),
        #   because that is where model packages typically store textures.
        texture_dirs = [input_path.parent if input_path.is_file() else input_path]
        texture_recursive = True

    texture_dirs = [p.expanduser().resolve() for p in texture_dirs]
    log(
        f"Texture search roots: {', '.join(str(p) for p in texture_dirs)} (recursive={texture_recursive})"
    )
    texture_index = _build_texture_index(texture_dirs, texture_recursive)
    log(f"Texture index: {len(texture_index)} files")

    if not input_path.exists():
        raise SystemExit(f"Input path does not exist: {input_path}")

    if input_path.is_file():
        tmp_root = output_path.parent / ".tmp_work" / input_path.stem
        convert_one(
            input_path,
            output_path.with_suffix(".glb"),
            tmp_root,
            cmodconvert_path,
            assimp_path,
            dskexp_path,
            dsk_precision,
            magick_path,
            texture_index,
            missing_texture_mode,
            scale_factor,
            apply_rotation,
            apply_quaternion,
        )
        log("Done.")
        return

    models = collect_models(input_path, recursive)
    ensure_dir(output_path)

    log(f"Found {len(models)} model(s). Converting...")
    for src in models:
        rel = src.relative_to(input_path)
        out_file = (output_path / rel).with_suffix(".glb")
        ensure_dir(out_file.parent)
        tmp_root = output_path / ".tmp_work" / rel.parent / rel.stem
        try:
            convert_one(
                src,
                out_file,
                tmp_root,
                cmodconvert_path,
                assimp_path,
                dskexp_path,
                dsk_precision,
                magick_path,
                texture_index,
                missing_texture_mode,
                scale_factor,
                apply_rotation,
                apply_quaternion,
            )
        except Exception as e:
            log(f"ERROR converting {src}: {e}")

    log("Done.")
    log(f"Work dir under: {output_path / '.tmp_work'}")


if __name__ == "__main__":
    main()
