// Algorithm derived from prototype: SunHaloFilter / SunHalo.vert — reimplemented for new architecture.

in vec3 inPosition;
in vec2 inTexCoord;

uniform mat4 g_WorldViewProjectionMatrix;

out vec2 vUv;

void main() {
    gl_Position = g_WorldViewProjectionMatrix * vec4(inPosition, 1.0);
    vUv = inTexCoord;
}
