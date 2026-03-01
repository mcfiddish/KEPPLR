# Creating Shape Models for KEPPLR

`convert_to_normalized_glb.py` converts assorted spacecraft/shape model formats into a **normalized GLB** that is more likely to load consistently in rendering engines (including jMonkeyEngine / KEPPLR).

It runs **inside Blender** (`bpy`), and uses optional helper tools for some formats.

---

## What “normalized” means here

The script aims to produce a GLB that:
- Imports cleanly into Blender and typical glTF viewers.
- Has textures in common formats (PNG where possible).
- Handles CMOD models that reference missing textures (Cosmographia-style behavior).
- Writes KEPPLR metadata describing the model’s intended intrinsic basis:

`asset.extras.kepplr.modelToBodyFixedQuat`

This is meant to help KEPPLR interpret model-space consistently relative to body-fixed frames.

---

## Supported input formats

- `.glb` / `.gltf`  
  Imported directly via Blender glTF importer.

- `.obj`  
  Imported via Blender OBJ importer. If textures exist and Blender loads them, the script normalizes images to PNG for export.

- `.3ds`  
  Converted via **assimp** to an intermediate `.glb`, then imported.

- `.cmod`  
  Converted via **cmodconvert** to `.obj` + `.mtl`, then imported.
  CMOD packages often reference texture filenames that are not shipped; Cosmographia can still render them using material constants. This script supports two missing-texture policies:
  - `strip` (default): remove `map_*` lines and rely on `Kd/Ks/Ns` material values.
  - `swatch`: generate 1×1 PNG swatches from each material’s `Kd` so the GLB has usable (solid-color) textures.

---

## Requirements

### Required
- **Blender** (run the script with `blender -b -P ...`)
  - Blender 5.0+ recommended.

### Required for some inputs
- **assimp** (only for `.3ds`)
  - Ubuntu/Debian: `sudo apt install assimp-utils`
  - macOS: `brew install assimp`

- **cmodconvert** (only for `.cmod`)
  - https://github.com/ajtribick/cmodconvert

### Optional
- **ImageMagick** (helps convert DDS → PNG when DDS textures exist)
  - Ubuntu/Debian: `sudo apt install imagemagick`
  - macOS: `brew install imagemagick`
  - Pass `--magick /path/to/magick` if needed.

---

## Usage

All arguments must come after Blender's `--` separator.

### Convert a single model
```bash
blender -b -P convert_to_normalized_glb.py -- \
  --input /path/to/model.cmod \
  --output /path/to/out.glb \
  --cmodconvert /path/to/cmodconvert
```


# Fixing Texture and UV Issues in Blender (for CMOD → GLB Conversion)

This repository converts Cosmographia `.cmod` models to normalized `.glb` files
using `convert_to_normalized_glb.py`.

Some `.cmod` features are **not preserved** during conversion — especially
multi-texture materials and per-channel UV mappings. As a result, you may see:

- Wrong textures applied to parts of the model
- Tiled or repeated atlas textures where none should appear
- Geometry that should be foil-shaded or plain gray instead sampling part of an atlas

These are **data issues**, not renderer bugs.  
The correct fix is to adjust the model in **Blender** and re-export the `.glb`.

---

## Background: How Textures Work After Conversion

After conversion:

- The GLB typically contains **one texture atlas** (e.g. `Main_txt.png`)
- Each vertex has **one UV coordinate set**
- Materials either:
  - sample that atlas, or
  - have *no texture at all* (color/lighting only)

There is **no per-face or per-triangle texture selection**.
Texture selection is entirely controlled by **UV coordinates**.

---

## When You Need to Fix the Model

You should open the model in Blender if you see:

- Solar panels or structures showing the *wrong* part of an atlas
- Structural parts (booms, connectors, body panels) showing random textures
- Parts that should be untextured appearing noisy or patterned

---

## Opening the Model in Blender

1. Open Blender
2. **File → Import → glTF 2.0 (.glb/.gltf)**
3. Import the converted `.glb`
4. Switch to **Shading** or **UV Editing** workspace

---

## A) Fixing the UV Tile Used by a Structure

This applies when a structure is sampling the *wrong region* of a texture atlas.

### Example
- The atlas is vertically stacked
- Solar panels should use the **top half**
- Body panels should use the **bottom half**

### Steps

1. **Select the object**
   - Click the object in the 3D viewport or Outliner

2. Enter **Edit Mode**
   - Press `Tab`

3. Select all faces of that object
   - Press `A`

4. Open the **UV Editor**
   - You should see the atlas image and UV islands

5. Move the UVs to the correct tile
   - Press `G` to move
   - Press `S` to scale if needed
   - For vertical atlases:
     - Bottom tile: `V` in `[0.0, 1.0)`
     - Top tile: `V` in `[1.0, 2.0)` (or normalized equivalent)

6. Ensure all UVs stay **within the intended tile**
   - No wrapping unless intentional

7. Exit Edit Mode (`Tab`)

✅ Result: That structure now samples the correct atlas region.

---

## B) Removing a Texture from a Structure (Shading Only)

Some parts (e.g. spacecraft body, trusses, hinges) should **not** use any texture.

### This is the most common fix.

### Steps

1. Select the object

2. Open the **Material Properties** tab

3. Select the material used by that object

4. In the **Principled BSDF** node:
   - Disconnect the **Base Color** texture input
   - Or delete the Image Texture node entirely

5. Set:
   - **Base Color** to a neutral gray or foil color
   - Adjust:
     - Roughness
     - Metallic
     - Specular
     - As desired

6. (Optional) Rename the material
   - Example: `Foil_Body`, `Untextured_Structure`

✅ Result: The object renders with lighting only, no texture sampling.

---

## C) Adding a Texture to a Structure That Has None

If a structure should be textured but is not:

1. Select the object
2. Open **Material Properties**
3. Ensure it has a material (click `New` if needed)
4. In **Shading** view:
   - Add **Image Texture** node
   - Load the desired image
   - Connect it to **Base Color**
5. Open **UV Editing**
6. Ensure the object has valid UVs:
   - If not: `UV → Unwrap` (or `Smart UV Project`)
7. Position UVs correctly in the atlas or image

---

## D) Important Rules (Read This)

- **UVs are per-vertex**, not per-face
- If two faces share vertices, they must share UVs
- You cannot “assign a different texture” without changing UVs
- If a structure uses an atlas but *should not*, the fix is:
  **remove the texture**, not fight the UVs

---

## Exporting the Fixed Model

When done:

1. **File → Export → glTF 2.0**
2. Use:
   - Format: `glTF Binary (.glb)`
   - Apply Modifiers: ✓
   - Materials: ✓
3. Overwrite or save a new `.glb`
4. Use this file in KEPPLR

---

## Final Notes

- CMOD → GLB conversion necessarily drops some material features
- Blender is the correct place to resolve visual intent
- If a converted model looks wrong, **the renderer is telling you the truth**
- Fix the data once; all renderers will benefit

This is expected, normal, and fixable.

Happy modeling 🚀


# README-Blender-Textures.md

This guide is for **fixing texture artifacts after converting spacecraft/shape models to GLB**, using **Blender**.

Common cases:
1. **Wrong part of a texture atlas** is showing (e.g., solar array “tile” wrong).
2. A mesh (or sub-part) **should have no texture** (just shading), but ends up sampling random pixels.
3. A mesh **has no texture/material**, but you want to add one.

---

## Concepts (what Blender is editing)

### UVs are per-vertex (and effectively per “corner”)
A triangle doesn’t store a texture coordinate by itself. Instead, **each triangle corner** references a vertex, and that vertex has a **UV coordinate** (per UV set).

Blender hides a lot of that complexity, but the practical consequence is:

- If two adjacent faces share the “same” vertex position in 3D, they can still need **different UVs**.
- When that happens, the mesh must contain **duplicated vertices** (or, more precisely, duplicated *UV corners*).
- In Blender, you create that separation by introducing **UV seams** or using **Split UVs** (Blender will duplicate UV corners as needed).

So if you try to move UVs for “the connector piece” but the solar panel faces move too, it’s because they share UV corners and you must split them.

---

## A) Set the UV “tile” when using a texture atlas

A texture atlas is a single image that contains multiple regions (“tiles”) used by different parts of the model.

### 1) Identify what’s wrong
Typical symptoms:
- A part shows a repeated/tiled pattern, or it samples an unrelated region.
- The correct image exists somewhere else in the atlas.

### 2) Edit UVs for only the faces you want
1. Select the mesh.
2. `Tab` into **Edit Mode**.
3. Switch to **Face Select**.
4. Select the faces that are wrong (hover + `L` for linked selection, or box select).
5. Open the **UV Editing** workspace.
6. In the **UV Editor**, ensure you can see the UV island(s) for the selected faces.

### 3) If moving UVs also changes adjacent parts, split them
If the connector and the panel share UV corners, you need to split UVs:

**Option 1: Mark seams**
1. In Edit Mode, select edges that form a boundary between “panel faces” and “connector faces”.
2. `Ctrl+E` → **Mark Seam**
3. In the UV Editor: `U` → **Unwrap** (or **Unwrap** only selected faces)

**Option 2: Split UVs for the selected faces**
1. In the UV Editor, select the UVs for the faces you want to separate
2. UV menu → **Split** (or press `Y` in the UV Editor depending on keymap)
3. Now move the connector UVs without dragging the panel UVs.

### 4) Move/scale UVs into the desired atlas region
- In the UV Editor, use `G` (move), `S` (scale), `R` (rotate).
- Keep UVs inside the desired atlas region.
- If your atlas is arranged in halves/thirds, use snapping:
  - UV Editor → **View** → enable **Pixel Snap** or use increment snapping if helpful.

> Tip: If the atlas region is meant to be “blank/white”, you can move the UVs to a deliberately blank area.  
> That is the most robust way to prevent sampling random pixels.

---

## B) Make a part *not use a texture* (shading only)

Sometimes a sub-structure (like a connector boom) wasn’t meant to be textured, but the conversion pipeline assigns it a material that references the atlas anyway.

### Option 1 (preferred): Keep one material, but remove Base Color texture input
1. Select the object.
2. Go to **Material Properties**.
3. Go to the **Shading** workspace.
4. Find the **Principled BSDF** node.
5. If there is an **Image Texture** node connected to **Base Color**, disconnect it.
6. Set **Base Color** to a neutral value (gray/white) and tune:
   - **Metallic**
   - **Roughness**
   - (Optional) **Specular**

This leaves the material shaded by lights, but not textured.

### Option 2: Assign a separate “untextured” material to those faces
If only some faces should be untextured:
1. Select faces in Edit Mode.
2. Material Properties → `+` (new slot) → **New**
3. With faces still selected, click **Assign**
4. In Shading, do not connect any image texture to Base Color.

---

## C) Add a texture to a structure that has none

If a mesh has no textures (or you want to add one):
1. Select object → Material Properties → **New**
2. Shading workspace:
   - Add **Image Texture** node (`Shift+A` → Texture → Image Texture)
   - Click **Open** and pick your PNG/JPG
   - Connect `Color` → Principled BSDF `Base Color`
3. Ensure UVs exist:
   - Object Data Properties (green triangle) → **UV Maps**
   - If none: Edit Mode → `U` → **Unwrap** (or Smart UV Project)

### If you want the texture to repeat/tiling
In the Image Texture node:
- Set extension to **Repeat**
- In the UV editor, scale UVs beyond 0..1, or use a Mapping node:
  - Add **Texture Coordinate** + **Mapping** nodes
  - Connect `UV` → Mapping → Image Texture `Vector`
  - Set Mapping **Scale** to tile.

---

## D) Why “random textures” can appear even when there are “no textures”
If a model reports **no textures** but still shows garbage sampling:
- The material/shader may still be sampling a texture slot that contains stale data.
- Or the engine may be binding a default/previous texture.

In Blender, the fix is: ensure the material node graph **does not reference an image texture**, and that the exported GLB has no baseColorTexture for those materials (or has a clean constant base color).

---

## Exporting the fixed model
1. File → Export → **glTF 2.0**
2. Format: **GLB**
3. Ensure:
   - **Include → Selected Objects** (optional)
   - **Materials** enabled
   - **Images** set to embed (default is fine for GLB)

---

## Practical workflow recommendation
If the conversion yields:
- Correct geometry and scale
- “Mostly correct” atlas mapping
- But some parts should be untextured or use a different tile

…then fixing it in Blender is the right long-term move.


