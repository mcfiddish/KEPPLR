============
Python Tools
============

The script `convert_to_normalized_glb` can convert common model formats into glTF Binary (.glb) for use
with KEPPLR.

Dependencies
------------

- `Blender <https://www.blender.org/>`__ (required, 5.0+ recommended)
    - this script runs with Blender's Python (`bpy`), not system Python

- `assimp <https://github.com/assimp/assimp>`__ (for converting `.3ds` inputs)
- `cmodconvert <https://github.com/ajtribick/cmodconvert>`__ (for converting Celestia `.cmod` inputs)
- `ImageMagick <https://imagemagick.org/>`__ (for converting `.dds` textures to `.png`)

Command-line options
--------------------

::

    All script options must come *after* `--` (Blender's argument separator).

    Required:
      --input, -i <file|dir>        Input model file or directory
      --output, -o <file|dir>       Output GLB file (single-file mode) or directory (dir mode)

    Optional:
      --recursive                   If --input is a directory, recurse into subdirectories
      --assimp <path>               Path to assimp executable (default: 'assimp' on PATH)
      --cmodconvert <path>          Path to cmodconvert executable (required for .cmod inputs)
      --magick <path>               Path to ImageMagick binary (default: try 'magick' then 'convert')
      --texture-dir <dir>           (Repeatable) Add search roots for CMOD-referenced textures
      --texture-recursive           If set, search each --texture-dir recursively
      --missing-texture-mode <mode> CMOD-only: 'strip' (default) or 'swatch'

Examples
--------

- Convert a single GLB/GLTF file:

::

     blender -b -P convert_to_normalized_glb.py -- \
       --input "Voyager Probe (A).glb" \
       --output "Voyager Probe (A)-normalized.glb"

- Convert a directory recursively (GLB/GLTF/OBJ/3DS/CMOD):

::

     blender -b -P convert_to_normalized_glb.py -- \
       --input ./spacecraft \
       --output ./normalized \
       --recursive \
       --cmodconvert ~/local/cmodconvert/cmodconvert

- Convert CMODs and search for their textures under a data directory:

::

     blender -b -P convert_to_normalized_glb.py -- \
       --input ./cosmographia \
       --output ./normalized \
       --recursive \
       --cmodconvert ~/local/cmodconvert/cmodconvert \
       --texture-dir ./cosmographia/data --texture-recursive

- CMOD missing textures: match Cosmographia behavior (recommended default):

::

     blender -b -P convert_to_normalized_glb.py -- \
       --input ./models \
       --output ./normalized \
       --recursive \
       --cmodconvert ~/local/cmodconvert/cmodconvert \
       --missing-texture-mode strip

- CMOD missing textures: generate solid-color texture swatches:

::

     blender -b -P convert_to_normalized_glb.py -- \
       --input ./models \
       --output ./normalized \
       --recursive \
       --cmodconvert ~/local/cmodconvert/cmodconvert \
       --missing-texture-mode swatch


