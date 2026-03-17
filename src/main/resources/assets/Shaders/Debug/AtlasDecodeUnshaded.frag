// AtlasDecodeUnshaded fragment shader (GLSL 150 -- version injected by JME)
//
// Decodes a texture atlas UV and samples the AlbedoMap (if present).
//
// Atlas layout
// ─────────────────────────────────────────────────────────────────────────────
// The mesh may carry UV coordinates that extend beyond [0, 1] — e.g., a mesh
// whose UVs span a (TilesU × TilesV) tile grid starting at UvBase.
//
// Decode logic (UvTransformMode == 0, atlas_decode):
//   localUV  = fract(texCoord - UvBase)       // position within current tile
//   atlasUV  = (SelectedTile + localUV) / Tiles  // texel address in atlas
//
// Offset-only mode (UvTransformMode == 1):
//   UvBase is an absolute offset; no tile grid is applied.
//   atlasUV  = fract(texCoord + UvBase)
//
// Pass-through (UvTransformMode == 2):
//   atlasUV  = texCoord  (no transform; used when mesh UVs are already in [0,1])
//
// In all modes the result is multiplied by BaseColorFactor.  When AlbedoMap is
// absent the shader outputs BaseColorFactor directly.
//
// DebugTileTint (compile-time define)
//   When enabled, each atlas tile receives a distinct pseudo-random hue overlay
//   so that tile boundaries can be inspected visually.

uniform vec4  m_BaseColorFactor;
uniform int   m_UvTransformMode;
uniform vec2  m_UvBase;
uniform vec2  m_Tiles;
uniform vec2  m_SelectedTile;
uniform float m_UvOffsetEnabled;
uniform vec2  m_UvOffset;

#ifdef ALBEDO_MAP
uniform sampler2D m_AlbedoMap;
#endif

in  vec2 vTexCoord;
out vec4 outColor;

// ── Per-tile debug tint: generates a distinct hue for tile (tx, ty) ──────────
#ifdef DEBUG_TILE_TINT
vec3 tileTint(vec2 tile) {
    float idx = tile.x + tile.y * m_Tiles.x;
    return vec3(
        fract(sin(idx * 127.1 + 1.3) * 43758.5453),
        fract(sin(idx * 311.7 + 2.7) * 53284.1352),
        fract(sin(idx * 74.2  + 0.9) * 21863.8472)
    );
}
#endif

void main() {
    vec2 uv;

    if (m_UvTransformMode == 1) {
        // Offset-only: UvBase is a simple UV translation
        uv = fract(vTexCoord + m_UvBase);
    } else if (m_UvTransformMode == 2) {
        // Pass-through: mesh UVs are already in [0, 1]
        uv = vTexCoord;
    } else {
        // Atlas decode (mode 0, default)
        vec2 localUV = fract(vTexCoord - m_UvBase);
        uv = (m_SelectedTile + localUV) / m_Tiles;
    }

    // Optional additional UV offset (UV-debug modes)
    if (m_UvOffsetEnabled > 0.5) {
        uv = fract(uv + m_UvOffset);
    }

    vec4 color;
#ifdef ALBEDO_MAP
    color = texture(m_AlbedoMap, uv) * m_BaseColorFactor;
#else
    color = m_BaseColorFactor;
#endif

#ifdef DEBUG_TILE_TINT
    // Overlay: tint using the tile that this fragment belongs to in the mesh
    vec2 localUV2 = vTexCoord - m_UvBase;
    vec2 tileIdx  = floor(localUV2);
    vec3 tint = tileTint(tileIdx);
    color.rgb = mix(color.rgb, tint, 0.45);
#endif

    outColor = color;
}
