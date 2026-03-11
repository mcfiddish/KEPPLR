// Saturn ring fragment shader (GLSL 150 -- version injected by JME)
//
// Computes ring color and opacity from the Bjorn Jonsson radial optical-depth profile,
// and applies an observer/sun phase function for forward/back scattering.
//
// Shadow roles (Step 16a -- math in Step 16b):
//   CASTER   : ring casts shadows onto Saturn's disk
//   RECEIVER : ring receives shadows from Saturn (stub below)
//   SUN_HALO : ring occludes Sun halo (Step 17)
//
// All positions and directions are in body-fixed (model) space, pre-computed in Java.
// InnerRadius and OuterRadius are in km, matching the mesh geometry.

uniform sampler2D m_BrightnessTex;   // 1-D luminance; sampled at (1-t, 0.5) for radial coord t
uniform sampler2D m_TransparencyTex; // 1-D luminance; same UV convention
uniform vec4      m_RingColor;       // warm ring tint
uniform float     m_InnerRadius;     // km - inner annulus boundary
uniform float     m_OuterRadius;     // km - outer annulus boundary
uniform vec3      m_SunDir;          // normalized sun direction in body-fixed space
uniform vec3      m_ObserverPos;     // camera position in body-fixed space (km)
uniform vec3      m_SaturnRadii;     // Saturn principal radii (km) - for Step 16b shadow test

// Step 16b shadow parameters (present but inactive when ShadowDarkness = 0)
uniform float     m_ShadowDarkness;       // 0 in 16a; ~0.9 when 16b is enabled
uniform float     m_MoonShadowDarkness;   // 0 in 16a
uniform int       m_NumShadowCasters;     // 0 in 16a
uniform vec3      m_ShadowCasterPos[8];   // moon positions in body-fixed space (km)
uniform float     m_ShadowCasterRadius[8]; // moon radii (km)

in vec3 vLocalPos;
in vec3 vWorldPos;

out vec4 fragColor;

// Step 16b stub: Saturn-on-ring shadow ray test
// TODO Step 16b: Replace with actual ellipsoid intersection test.
//   Scale pos and dir by 1/radii, solve quadratic for sphere-ray intersection
//   (t > epsilon means ring fragment is in Saturn's shadow).
bool intersectsSaturn_STUB(vec3 pos, vec3 sunDir) {
    return false;
}

void main() {
    float span = m_OuterRadius - m_InnerRadius;
    if (span <= 0.0) discard;

    // Radial distance from Saturn centre (ring mesh is centred at origin in model space)
    float rho = length(vLocalPos.xy);
    float t = clamp((rho - m_InnerRadius) / span, 0.0, 1.0);

    // UV convention: inner edge = coord 1.0, outer edge = 0.0 (Bjorn Jonsson profile order)
    float sampleCoord = 1.0 - t;
    vec2 uv = vec2(sampleCoord, 0.5);

    float brightness   = texture(m_BrightnessTex,   uv).r;
    float transparency = texture(m_TransparencyTex, uv).r;

    // Alpha: fully transparent where transparency = 1; fully opaque where transparency = 0
    float alpha = clamp(1.0 - transparency, 0.0, 1.0);
    if (alpha < 0.01) discard;

    // Phase function: rings scatter light differently forward vs. back.
    // Computed in body-fixed space where SunDir and ObserverPos are pre-transformed.
    float ringLightFactor = 1.0;

    float sunLen = length(m_SunDir);
    if (sunLen > 0.0) {
        vec3 sunDir = normalize(m_SunDir);

        vec3 toObserver = m_ObserverPos - vLocalPos;
        float obsLen = length(toObserver);
        if (obsLen > 0.0) {
            vec3 obsDir = toObserver / obsLen;
            // Phase angle between observer and sun directions at this ring fragment
            float cosPhase = clamp(dot(obsDir, sunDir), -1.0, 1.0);
            float halfPhase   = 0.5 * (1.0 + cosPhase);
            float halfPhaseSq = halfPhase * halfPhase;

            // Determine which side of the ring plane sun and observer are on
            float sunSide = sign(sunDir.z);
            float obsSide = sign(m_ObserverPos.z);
            bool sameSide = (abs(sunSide) < 0.001 || abs(obsSide) < 0.001)
                           || (sunSide == obsSide);

            if (sameSide) {
                // Reflected light: ring is brightest forward-scattered
                ringLightFactor = max(halfPhaseSq, 0.05);
            } else {
                // Transmitted light: ring is dimmer when viewed from the shadowed side
                ringLightFactor = max(halfPhaseSq * 0.5, 0.02);
            }
        }

        // Step 16b: Saturn shadow on rings (inactive while m_ShadowDarkness = 0)
        if (m_ShadowDarkness > 0.0 && intersectsSaturn_STUB(vLocalPos.xyz, sunDir)) {
            ringLightFactor *= (1.0 - clamp(m_ShadowDarkness, 0.0, 1.0));
        }

        // Step 16b: Moon shadow on rings
        // TODO: iterate m_ShadowCasterPos[]/m_ShadowCasterRadius[] and darken by m_MoonShadowDarkness
    }

    vec3 color = m_RingColor.rgb * brightness;
    fragColor = vec4(color * ringLightFactor, alpha);
}
