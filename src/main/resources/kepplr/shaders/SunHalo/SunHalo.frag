// Algorithm derived from prototype: SunHaloFilter / SunHalo.frag — reimplemented for new architecture.
// Procedural corona: radial falloff from limb + animated spokes + limb glow.
// Rendered with additive blending; depth-tested so planets and rings occlude the halo naturally.

uniform vec4  m_SunColor;
uniform float m_Falloff;
uniform float m_MaxRadiusInR;   // halo outer edge in solar radii (set from KepplrConstants.SUN_HALO_MAX_RADIUS_MULTIPLIER)
uniform float m_AlphaScale;
uniform float m_Time;           // seconds; drives shimmer animation

in vec2 vUv;
out vec4 fragColor;

float saturate(float x) { return clamp(x, 0.0, 1.0); }

void main() {
    vec2 delta = vUv - vec2(0.5);

    // Discard quad corners to avoid a visible square at high contrast.
    float rUnit = length(delta) * 2.0; // 0 at quad centre, 1 at inscribed circle edge
    if (rUnit > 1.0) {
        discard;
    }

    // r is in solar-radii units: r == 1 at the Sun limb, r == MaxRadiusInR at the halo edge.
    float r = rUnit * m_MaxRadiusInR;

    float fall = max(m_Falloff, 0.0001);
    float maxR = max(m_MaxRadiusInR, 1.0001);

    // Inner gate: halo fades smoothly to zero inside the Sun disk (prevents ring/gap at limb).
    float innerGate = smoothstep(0.20, 1.00, r);

    // Outer gate: halo fades out near the billboard edge.
    float outerGate = 1.0 - smoothstep(maxR * 0.75, maxR, r);

    // ── Animated spokes (corona rays) ────────────────────────────────────────────────────────
    const float RAY_COUNT = 16.0;

    float angle = atan(delta.y, delta.x);

    // Subtle radial-dependent twist: inner and outer spokes rotate slightly differently.
    float twist = 0.15 * (1.0 - smoothstep(1.0, 1.8, r)) * sin(0.35 * m_Time);
    float a = angle + twist;

    float spoke = 0.5 + 0.5 * cos(RAY_COUNT * a);
    float sharp = 5.5 + 1.0 * sin(0.8 * m_Time);
    float spokesPrimary = pow(saturate(spoke), sharp);

    // Secondary spoke lobe (breaks strict symmetry).
    float a2 = angle - 0.07 * m_Time + 0.4 * sin(0.22 * m_Time);
    float spoke2 = 0.5 + 0.5 * cos((RAY_COUNT * 0.5) * a2 + 0.7);
    float spokesSecondary = 0.6 * pow(saturate(spoke2), 4.0);

    float spokes = max(spokesPrimary, spokesSecondary);

    // Confine spokes to the inner corona.
    float spokeFade = 1.0 - smoothstep(1.00, 1.60, r);
    spokes *= saturate(spokeFade);

    // ── Radial falloff from the limb outward ─────────────────────────────────────────────────
    float rFromLimb = max(r - 1.0, 0.0);
    float rNorm = rFromLimb / (maxR - 1.0);
    float radial = exp(-rNorm / fall);

    // Base brightness + spoke contribution + subtle intensity shimmer.
    float baseHalo = 0.05 * 19.0;
    float shimmer = 0.90 + 0.10 * sin(1.6 * m_Time + 3.0 * angle);
    float rayTerm = baseHalo * (0.55 + 0.75 * spokes) * shimmer;

    float brightness = rayTerm * radial;

    // Limb-bridge glow: smooth the transition at r ≈ 1 to hide any visible seam.
    float limbWidth = 0.08 + 0.12 * fall;
    float limb = exp(-pow((r - 1.0) / limbWidth, 2.0));
    brightness += 0.20 * limb;

    brightness *= innerGate * outerGate;

    // Reinhard tone map: prevents additive blending from blowing out bright pixels.
    float intensity = brightness / (1.0 + brightness);

    vec3  rgb   = m_SunColor.rgb * intensity;
    float alpha = saturate(intensity) * m_AlphaScale;

    fragColor = vec4(rgb, alpha);
}
