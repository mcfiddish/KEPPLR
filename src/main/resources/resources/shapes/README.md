# KEPPLR shape models

Shape models should be in [glTF](https://en.wikipedia.org/wiki/GlTF) format.  See the `Python Tools` page in the 
documentation for information on `convert_to_normalized_glb` which is a utility to convert a variety of shape model
formats to `.glb`.

A number of spacecraft models can be downloaded from https://github.com/nasa/NASA-3D-Resources/tree/master/3D%20Models.

## Example

Download a Phobos shape model from https://sbmt.jhuapl.edu/shared-files/:

```bash
curl -RO https://sbmt.jhuapl.edu/shared-files/files/phobos_g_296m_spc_obj_0000n00000_v004.obj.zip
```

Uncompress and convert it to `.glb`:

```bash
unzip phobos_g_296m_spc_obj_0000n00000_v004.obj.zip
blender -b -P convert_to_normalized_glb.py -- --input phobos_g_296m_spc_obj_0000n00000_v004.obj --output phobos_g_296m_spc_obj_0000n00000_v004.glb
```

Add it to your configuration file:

```
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
```