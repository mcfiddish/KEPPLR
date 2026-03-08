in vec4 vColor;   // [coreBrightness, coreBrightness, coreBrightness, haloStrength]
in vec2 vSizes;   // [pointSizePx, haloRadiusPx]

out vec4 fragColor;

void main() {
    float pointSizePx  = vSizes.x;
    float haloRadiusPx = vSizes.y;
    float totalSize    = pointSizePx + 2.0 * haloRadiusPx;

    // gl_PointCoord is [0,1]×[0,1]; map to signed [-1,1] centred coordinates.
    vec2  coord = gl_PointCoord * 2.0 - vec2(1.0);
    float r2    = dot(coord, coord);

    // Radii relative to the full point size
    float coreRadius = clamp(pointSizePx / totalSize, 0.1, 1.0);
    float haloRadius = clamp((pointSizePx + haloRadiusPx) / totalSize, coreRadius, 1.0);

    // Gaussian sigma values
    float sigmaCore = max(0.05, coreRadius * 0.35);
    float sigmaHalo = max(sigmaCore * 2.0, haloRadius * 0.5);

    float core = exp(-r2 / (sigmaCore * sigmaCore));
    float halo = exp(-r2 / (sigmaHalo * sigmaHalo));

    float coreBrightness = vColor.r;
    float haloStrength   = vColor.a;

    float intensity = coreBrightness * core + haloStrength * halo * 0.75;
    fragColor = vec4(intensity, intensity, intensity, 1.0);
}
