uniform mat4 g_WorldViewProjectionMatrix;

in vec3 inPosition;
in vec4 inColor;      // [coreBrightness, coreBrightness, coreBrightness, haloStrength]
in vec2 inTexCoord;   // [pointSizePx, haloRadiusPx]

out vec4 vColor;
out vec2 vSizes;

void main() {
    gl_Position = g_WorldViewProjectionMatrix * vec4(inPosition, 1.0);
    // Clamp depth slightly inside the clip volume; prevents stars from being
    // discarded at the far plane boundary due to floating-point precision.
    gl_Position.z = gl_Position.w * 0.9999;
    gl_PointSize = inTexCoord.x + 2.0 * inTexCoord.y;
    vColor = inColor;
    vSizes = inTexCoord;
}
