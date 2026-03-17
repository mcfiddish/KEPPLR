// AtlasDecodeUnshaded vertex shader (GLSL 150 -- version injected by JME)
//
// Minimal pass-through shader: transforms position to clip space and forwards
// the mesh UV coordinate to the fragment stage for atlas decode.

uniform mat4 g_WorldViewProjectionMatrix;

in vec3 inPosition;
in vec2 inTexCoord;

out vec2 vTexCoord;

void main() {
    gl_Position = g_WorldViewProjectionMatrix * vec4(inPosition, 1.0);
    vTexCoord = inTexCoord;
}
