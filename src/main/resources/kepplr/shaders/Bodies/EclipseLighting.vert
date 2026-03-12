// Eclipse lighting vertex shader (GLSL 150 -- version injected by JME)
//
// Outputs world-space position and world-space normal for per-fragment eclipse shadow
// computation. The normal is transformed via the inverse-transpose of the world matrix
// (g_WorldMatrixInverseTranspose), which correctly handles non-uniformly scaled ellipsoids.
//
// All positions are in km (floating-origin heliocentric J2000 world space).

uniform mat4 g_WorldViewProjectionMatrix;
uniform mat4 g_WorldMatrix;
uniform mat3 g_WorldMatrixInverseTranspose;  // inverse-transpose of upper-left 3x3 of WorldMatrix

in vec3 inPosition;
in vec3 inNormal;
in vec2 inTexCoord;

out vec3 vWorldPos;    // world-space position (km)
out vec3 vWorldNormal; // world-space surface normal (un-normalized after interpolation)
out vec2 vTexCoord;

void main() {
    gl_Position = g_WorldViewProjectionMatrix * vec4(inPosition, 1.0);

    // World-space position (km, floating origin)
    vWorldPos = (g_WorldMatrix * vec4(inPosition, 1.0)).xyz;

    // World-space normal: inverse-transpose of world matrix correctly handles ellipsoid scale.
    vWorldNormal = g_WorldMatrixInverseTranspose * inNormal;

    vTexCoord = inTexCoord;
}
