// Saturn ring vertex shader — GLSL 150
// Passes model-space (body-fixed) position and world-space position to the fragment shader.
// The ring mesh is built in body-fixed space (XY plane, Z=0), so inPosition.xy gives the
// radial position needed for texture UV computation in the fragment shader.
#version 150

uniform mat4 g_WorldViewProjectionMatrix;
uniform mat4 g_WorldMatrix;

in vec3 inPosition;

out vec3 vLocalPos;   // model-space (body-fixed) position in km
out vec3 vWorldPos;   // world-space (J2000 scene-space) position in km

void main() {
    vLocalPos  = inPosition;
    vWorldPos  = (g_WorldMatrix * vec4(inPosition, 1.0)).xyz;
    gl_Position = g_WorldViewProjectionMatrix * vec4(inPosition, 1.0);
}
