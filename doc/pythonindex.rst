============
Python Tools
============

The script `convert_to_normalized_glb` can convert assorted model formats into a *normalized* glTF Binary (.glb) that is
more likely to load consistently in engines like jMonkeyEngine (jME).

This script is designed to run *inside* Blender (it uses `bpy`). Execute it with
Blender's `-b -P` options and pass script arguments after a `--` separator.

`NASA 3D Resources <https://github.com/nasa/NASA-3D-Resources/tree/master/3D%20Models>`__ is a good source for spacecraft models.

Use :doc:`GlbModelViewer` to view the resulting GLB model and check that the body-fixed axes are correct.

Dependencies
------------

- `Blender <https://www.blender.org/>`__ (required, 5.0+ recommended)

    - this script runs with Blender's Python (`bpy`), not system Python

- `assimp <https://github.com/assimp/assimp>`__ (for converting `.3ds` inputs)
- `cmodconvert <https://github.com/ajtribick/cmodconvert>`__ (for converting Celestia `.cmod` inputs)
- `ImageMagick <https://imagemagick.org/>`__ (for converting `.dds` textures to `.png`)

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

Command-line options
--------------------
All script options must come *after* `--` (Blender's argument separator).

::

    Required:
      --input, -i <file|dir>        Input model file or directory
      --output, -o <file|dir>       Output GLB file (single-file mode) or directory (dir mode)

    Optional:
      --recursive                   If --input is a directory, recurse into subdirectories
      --scale <float>               Uniform scale factor applied to the imported model before export
      --assimp <path>               Path to assimp executable (default: 'assimp' on PATH)
      --cmodconvert <path>          Path to cmodconvert executable (required for .cmod inputs)
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


- Download a Phobos shape model from https://sbmt.jhuapl.edu/shared-files/:

::

    curl -RO https://sbmt.jhuapl.edu/shared-files/files/phobos_g_296m_spc_obj_0000n00000_v004.obj.zip

 Uncompress and convert it to `.glb`:

::

    unzip phobos_g_296m_spc_obj_0000n00000_v004.obj.zip
    blender -b -P convert_to_normalized_glb.py -- --input phobos_g_296m_spc_obj_0000n00000_v004.obj --output phobos_g_296m_spc_obj_0000n00000_v004.glb


  Add it to your configuration file:

::

    # NAIF ID
    body.phobos.naifID = 401
    # Name.  If blank, NAIF_BODY_NAME will be used.
    body.phobos.name =
    # Body color as a hexadecimal number.  Ignored if textureMap is specified.
    body.phobos.hexColor = #B2B2B2
    # Scale dayside color by this fraction when drawing night side
    body.phobos.nightShade = 0.05
    # Path under resourcesFolder() for this body's texture map.  If blank specified color will be used.
    body.phobos.textureMap = maps/phobos.jpg
    # Center longitude (east) of texture map in degrees.
    body.phobos.centerLonDeg = 0.0
    # Path under resourcesFolder() for this body's shape model.  If blank an ellipsoid model will be used
    body.phobos.shapeModel = shapes/phobos_g_296m_spc_obj_0000n00000_v004.glb
