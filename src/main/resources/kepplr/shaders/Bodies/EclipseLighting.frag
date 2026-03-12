// Eclipse lighting fragment shader (GLSL 150 -- version injected by JME)
//
// Implements analytic eclipse shadow geometry (REDESIGN.md §9.3, hybrid Option C).
//
// Lighting model:
//   finalColor = (ambientFactor + dayFactor * sunScale * eclipseFactor * ringShadowFactor) * diffuse
//
// where:
//   ambientFactor  = BODY_AMBIENT_FACTOR (constant night-side floor)
//   dayFactor      = smooth day/night terminator from N·L
//   sunScale       = sun color (white * 2, to match JME PointLight intensity)
//   eclipseFactor  = 1 − maxShadow across all occluders (continuous [0,1])
//   ringShadowFactor = ring-plane ray trace result (Saturn only, 1 = no shadow)
//
// Shadow uniforms are set every frame by EclipseShadowManager. All positions are in km
// (floating-origin heliocentric J2000 world space).

// ── Sun and eclipse uniforms ────────────────────────────────────────────────────────────────
uniform vec3  m_SunPosition;    // world-space Sun center (km)
uniform float m_SunRadius;      // Sun radius (km); retrieved from ephemeris at point-of-use
uniform int   m_OccluderCount;  // number of active occluders (0 = no shadow computation)
uniform vec3  m_OccluderPositions[8]; // world-space occluder centers (km)
uniform float m_OccluderRadii[8];     // occluder radii (km)

// ── Ring shadow uniforms (Saturn only, gated by #ifdef HAS_RINGS) ────────────────────────────
uniform vec3  m_RingNormal;       // ring-plane normal in world space (normalized)
uniform vec3  m_RingSaturnCenter; // Saturn world-space center (km) — same as body center
uniform float m_RingInner;        // inner ring edge in km (body-fixed = world here)
uniform float m_RingOuter;        // outer ring edge in km
uniform float m_RingTauScale;     // Beer-Lambert tau scale (= RING_TAU_SCALE constant)
uniform sampler2D m_RingTransparencyTex;  // 1-D transparency profile (inner=1.0, outer=0.0)

// ── Surface material ────────────────────────────────────────────────────────────────────────
#ifdef DIFFUSE_MAP
uniform sampler2D m_DiffuseMap;
#endif
uniform vec4  m_DiffuseColor;   // fallback flat color (used when no DiffuseMap)

in vec3 vWorldPos;
in vec3 vWorldNormal;
in vec2 vTexCoord;

out vec4 fragColor;

// ── Constants ───────────────────────────────────────────────────────────────────────────────
// Night-side luminance floor (matches KepplrConstants.BODY_AMBIENT_FACTOR).
const float AMBIENT = 0.05;

// Smooth-step half-width for the terminator (matches KepplrConstants.SHADOW_TERMINATOR_WIDTH_RAD).
const float TERMINATOR_W = 0.05;

// ── Day/night terminator ────────────────────────────────────────────────────────────────────
float dayFactor(vec3 worldNormal, vec3 sunDir) {
    float NdotL = dot(normalize(worldNormal), sunDir);
    return smoothstep(-TERMINATOR_W, TERMINATOR_W, NdotL);
}

// ── Analytic eclipse shadow ─────────────────────────────────────────────────────────────────
// Returns shadow fraction in [0, 1]: 0 = full sunlight, 1 = full umbra.
// Algorithm derived from prototype: SaturnShadowController / EclipseLighting150 — reimplemented
// for new architecture with continuous penumbra.
float shadowFraction(vec3 toSun, float dSun, float alphaSun, vec3 occPos, float occRadius) {
    vec3 toOcc = occPos - vWorldPos;
    float dOcc = length(toOcc);
    if (!(dOcc > 0.0)) return 0.0;

    float alphaOcc = asin(clamp(occRadius / dOcc, 0.0, 1.0));
    if (!(alphaOcc > 0.0)) return 0.0;

    float cosTheta = clamp(dot(toSun / dSun, toOcc / dOcc), -1.0, 1.0);
    float theta = acos(cosTheta);

    float umbraLimit = alphaOcc - alphaSun;   // positive iff umbra exists
    float penumbraEnd = alphaOcc + alphaSun;

    if (umbraLimit > 0.0 && theta < umbraLimit) {
        return 1.0; // full umbra
    }
    if (theta > penumbraEnd) {
        return 0.0; // full sunlight
    }
    // Penumbra: linear blend from umbraLimit (or 0) to penumbraEnd
    float penumbraStart = abs(umbraLimit);
    float denom = penumbraEnd - penumbraStart;
    if (denom <= 0.0) return 0.0;
    return clamp((penumbraEnd - theta) / denom, 0.0, 1.0);
}

// Point-source variant: binary test only (LOW quality).
float shadowFractionPointSource(vec3 toSun, float dSun, vec3 occPos, float occRadius) {
    vec3 toOcc = occPos - vWorldPos;
    float dOcc = length(toOcc);
    if (!(dOcc > 0.0)) return 0.0;
    float alphaOcc = asin(clamp(occRadius / dOcc, 0.0, 1.0));
    if (!(alphaOcc > 0.0)) return 0.0;
    float cosTheta = clamp(dot(toSun / dSun, toOcc / dOcc), -1.0, 1.0);
    float theta = acos(cosTheta);
    return (theta < alphaOcc) ? 1.0 : 0.0;
}

// ── Ring shadow on Saturn (ray-plane intersection, Beer-Lambert opacity) ───────────────────
#ifdef HAS_RINGS
float ringShadowFactor(vec3 sunDir) {
    float ringSpan = m_RingOuter - m_RingInner;
    if (!(ringSpan > 0.0)) return 1.0;

    float ringNormalLen = length(m_RingNormal);
    if (!(ringNormalLen > 0.0)) return 1.0;
    vec3 rn = m_RingNormal / ringNormalLen;

    float denom = dot(rn, sunDir);
    float absDenom = abs(denom);
    if (absDenom < 1e-4) return 1.0; // ray nearly parallel to ring plane — no shadow

    // t to ring plane along sun direction
    float tPlane = dot(rn, m_RingSaturnCenter - vWorldPos) / denom;
    if (tPlane <= 0.0) return 1.0; // plane is behind the fragment (shadow source is below)

    vec3 hit = vWorldPos + sunDir * tPlane;
    vec3 fromCenter = hit - m_RingSaturnCenter;
    float proj = dot(fromCenter, rn);
    float rho = length(fromCenter - rn * proj); // radial distance from Saturn axis at hit point

    if (rho < m_RingInner || rho > m_RingOuter) return 1.0; // hit outside ring annulus

    float v = clamp((rho - m_RingInner) / ringSpan, 0.0, 1.0);
    float sampleCoord = 1.0 - v; // inner=1.0, outer=0.0 (Bjorn Jonsson profile order)
    float transparency = texture(m_RingTransparencyTex, vec2(sampleCoord, 0.5)).r;
    float tau = max(0.0, 1.0 - transparency);
    float mu0 = max(1e-3, absDenom);
    // Beer-Lambert attenuation: atten = exp(−TauScale × τ / cos(incidence angle))
    return exp(-m_RingTauScale * tau / mu0);
}
#endif

// ── Main ────────────────────────────────────────────────────────────────────────────────────
void main() {
    // Surface color: texture or flat color
    vec4 diffuse;
#ifdef DIFFUSE_MAP
    diffuse = texture(m_DiffuseMap, vTexCoord);
#else
    diffuse = m_DiffuseColor;
#endif

    // Sun direction from fragment
    vec3 toSun = m_SunPosition - vWorldPos;
    float dSun = length(toSun);
    if (!(dSun > 0.0)) {
        fragColor = vec4(diffuse.rgb * AMBIENT, diffuse.a);
        return;
    }
    vec3 sunDir = toSun / dSun;

    // Day/night terminator
    float day = dayFactor(vWorldNormal, sunDir);

    // Eclipse shadow: iterate occluders, take maximum shadow contribution
    float maxShadow = 0.0;
    if (m_OccluderCount > 0) {
#ifdef EXTENDED_SOURCE
        float alphaSun = asin(clamp(m_SunRadius / dSun, 0.0, 1.0));
        for (int i = 0; i < 8; i++) {
            if (i >= m_OccluderCount) break;
            float sf = shadowFraction(toSun, dSun, alphaSun, m_OccluderPositions[i], m_OccluderRadii[i]);
            maxShadow = max(maxShadow, sf);
        }
#else
        // Point-source (LOW quality): binary shadow, no penumbra
        for (int i = 0; i < 8; i++) {
            if (i >= m_OccluderCount) break;
            float sf = shadowFractionPointSource(toSun, dSun, m_OccluderPositions[i], m_OccluderRadii[i]);
            maxShadow = max(maxShadow, sf);
        }
#endif
    }

    // TODO Step 16b stub — shape-model shadow refinement for near-frustum bodies.
    // When shape models are available (deferred step), replace this with per-triangle
    // intersection against the loaded shape model. Until then, sphere approximation is used.

    float eclipseFactor = 1.0 - maxShadow;

    // Ring shadow on Saturn's disk (only compiled for Saturn material)
#ifdef HAS_RINGS
    float rsf = ringShadowFactor(sunDir);
#else
    float rsf = 1.0;
#endif

    // Final color: ambient floor + day-side illumination scaled by eclipse and ring shadow.
    // Range: [AMBIENT, 1.0] — no clipping on lit side, dim but visible on night side.
    float lit = AMBIENT + (1.0 - AMBIENT) * day * eclipseFactor * rsf;
    fragColor = vec4(diffuse.rgb * lit, diffuse.a);
}
