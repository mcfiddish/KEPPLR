// Saturn ring fragment shader (GLSL 150 -- version injected by JME)
//
// Computes ring color and opacity from the Bjorn Jonsson radial optical-depth profile,
// applies an observer/sun phase function for forward/back scattering, and computes
// analytic eclipse shadows (Step 16b):
//
//   CASTER:   ring casts shadows onto Saturn's disk (handled by EclipseLighting.frag)
//   RECEIVER: ring receives shadows from Saturn (ellipsoid ray test below)
//   RECEIVER: ring receives shadows from Saturn's moons (sphere ray test, analytic penumbra)
//
// All positions and directions are in Saturn body-fixed (model) space, pre-computed in Java.
// InnerRadius and OuterRadius are in km, matching the mesh geometry.
//
// Algorithm derived from prototype: SaturnRings150.frag — reimplemented for new architecture
// with continuous penumbra (analytic angular disk) for moon shadows.

uniform sampler2D m_BrightnessTex;    // 1-D luminance; inner edge = coord 1.0, outer = 0.0
uniform sampler2D m_TransparencyTex;  // 1-D luminance; same UV convention
uniform vec4      m_RingColor;        // warm ring tint
uniform float     m_InnerRadius;      // km - inner annulus boundary
uniform float     m_OuterRadius;      // km - outer annulus boundary
uniform vec3      m_SunDir;           // normalized sun direction in body-fixed space
uniform vec3      m_ObserverPos;      // camera position in body-fixed space (km)
uniform vec3      m_SaturnRadii;      // Saturn principal radii (km) — for Saturn-on-ring shadow

// Step 16b shadow parameters (set by EclipseShadowManager; 0/disabled default in 16a)
uniform float m_ShadowDarkness;        // Saturn shadow scale (0.9 default)
uniform float m_MoonShadowDarkness;    // Moon shadow scale (0.6 default)
uniform int   m_NumShadowCasters;      // number of active moon casters
uniform vec3  m_ShadowCasterPos[8];   // moon positions in Saturn body-fixed (km)
uniform float m_ShadowCasterRadius[8]; // moon radii (km)
uniform float m_SunRadius;             // Sun radius (km), for analytic penumbra
uniform float m_SunDistance;           // Saturn-to-Sun distance (km), for sun angular size

in vec3 vLocalPos;
in vec3 vWorldPos;

out vec4 fragColor;

// ── Saturn-on-ring shadow: ray-ellipsoid intersection ────────────────────────────────────────
// Returns true if the ray from ring fragment position toward Sun intersects the Saturn ellipsoid
// (and the intersection is in the Sun direction, not behind the fragment).
// Algorithm derived from prototype: SaturnRings150.frag — reimplemented for new architecture.
bool intersectsSaturn(vec3 pos, vec3 sunDir) {
    if (!(m_SaturnRadii.x > 0.0) || !(m_SaturnRadii.y > 0.0) || !(m_SaturnRadii.z > 0.0)) {
        return false;
    }
    // Scale pos and direction to unit-sphere space
    vec3 q = vec3(pos.x / m_SaturnRadii.x, pos.y / m_SaturnRadii.y, pos.z / m_SaturnRadii.z);
    vec3 d = vec3(sunDir.x / m_SaturnRadii.x, sunDir.y / m_SaturnRadii.y, sunDir.z / m_SaturnRadii.z);
    float a = dot(d, d);
    float b = 2.0 * dot(q, d);
    float c = dot(q, q) - 1.0;
    float disc = b * b - 4.0 * a * c;
    if (disc <= 0.0) return false;
    float sqrtDisc = sqrt(disc);
    float invDen = 0.5 / a;
    float t0 = (-b - sqrtDisc) * invDen;
    float t1 = (-b + sqrtDisc) * invDen;
    float tMin = min(t0, t1);
    float tMax = max(t0, t1);
    // tMax > 0 means intersection is in front; tMin > 1e-6 avoids self-intersection
    return (tMax > 0.0 && tMin > 1e-6);
}

// ── Analytic angular disk shadow fraction ────────────────────────────────────────────────────
// Computes the shadow fraction [0, 1] from a sphere-shaped caster at casterPos with casterRadius.
// Uses extended-source angular disk geometry (same as EclipseLighting.frag body shader).
// Returns 0.0 = no shadow, 1.0 = full umbra.
// Algorithm derived from prototype: EclipseLighting150.frag — reimplemented for ring receiver.
float moonShadowFraction(vec3 fragPos, vec3 sunDir, vec3 casterPos, float casterRadius) {
    if (!(casterRadius > 0.0)) return 0.0;
    vec3 toOcc = casterPos - fragPos;
    float dOcc = length(toOcc);
    if (!(dOcc > 0.0)) return 0.0;
    float alphaOcc = asin(clamp(casterRadius / dOcc, 0.0, 1.0));
    if (!(alphaOcc > 0.0)) return 0.0;

    float alphaSun = asin(clamp(m_SunRadius / max(m_SunDistance, 1.0), 0.0, 1.0));

    float cosTheta = clamp(dot(sunDir, toOcc / dOcc), -1.0, 1.0);
    float theta = acos(cosTheta);

    float umbraLimit = alphaOcc - alphaSun;
    float penumbraEnd = alphaOcc + alphaSun;

    if (umbraLimit > 0.0 && theta < umbraLimit) {
        return 1.0; // full umbra
    }
    if (theta > penumbraEnd) {
        return 0.0; // full sunlight
    }
    float penumbraStart = abs(umbraLimit);
    float denom = max(penumbraEnd - penumbraStart, 1e-6);
    return clamp((penumbraEnd - theta) / denom, 0.0, 1.0);
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
    float ringLightFactor = 1.0;

    float sunLen = length(m_SunDir);
    if (sunLen > 0.0) {
        vec3 sunDir = normalize(m_SunDir);

        vec3 toObserver = m_ObserverPos - vLocalPos;
        float obsLen = length(toObserver);
        if (obsLen > 0.0) {
            vec3 obsDir = toObserver / obsLen;
            float cosPhase = clamp(dot(obsDir, sunDir), -1.0, 1.0);
            float halfPhase   = 0.5 * (1.0 + cosPhase);
            float halfPhaseSq = halfPhase * halfPhase;

            float sunSide = sign(sunDir.z);
            float obsSide = sign(m_ObserverPos.z);
            bool sameSide = (abs(sunSide) < 0.001 || abs(obsSide) < 0.001)
                           || (sunSide == obsSide);

            if (sameSide) {
                ringLightFactor = max(halfPhaseSq, 0.05);
            } else {
                ringLightFactor = max(halfPhaseSq * 0.5, 0.02);
            }
        }

        // ── Saturn shadow on ring (Step 16b) ──────────────────────────────────────────────────
        // Uses ellipsoid intersection test. Saturn-on-ring penumbra is geometrically very narrow
        // at typical ring distances (angular penumbra < 0.01°) so binary umbra is used here.
        // The ShadowDarkness uniform controls the overall darkness of the shadow band.
        if (m_ShadowDarkness > 0.0 && intersectsSaturn(vLocalPos.xyz, sunDir)) {
            ringLightFactor *= (1.0 - clamp(m_ShadowDarkness, 0.0, 1.0));
        }

        // ── Moon shadow on ring (Step 16b, analytic penumbra) ────────────────────────────────
        // Iterate active moon shadow casters; take maximum shadow contribution.
        if (m_MoonShadowDarkness > 0.0 && m_NumShadowCasters > 0) {
            float maxMoonShadow = 0.0;
            for (int i = 0; i < 8; i++) {
                if (i >= m_NumShadowCasters) break;
                float sf = moonShadowFraction(
                        vLocalPos.xyz, sunDir, m_ShadowCasterPos[i], m_ShadowCasterRadius[i]);
                maxMoonShadow = max(maxMoonShadow, sf);
            }
            ringLightFactor *= (1.0 - clamp(m_MoonShadowDarkness * maxMoonShadow, 0.0, 1.0));
        }
    }

    vec3 color = m_RingColor.rgb * brightness;
    fragColor = vec4(color * ringLightFactor, alpha);
}
