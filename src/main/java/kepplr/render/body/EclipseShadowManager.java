package kepplr.render.body;

import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Spatial;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import kepplr.config.KEPPLRConfiguration;
import kepplr.ephemeris.KEPPLREphemeris;
import kepplr.render.RenderQuality;
import kepplr.state.SimulationState;
import kepplr.util.KepplrConstants;
import picante.surfaces.Ellipsoid;

/**
 * Updates eclipse shadow uniforms on all body materials and the Saturn ring material each frame.
 *
 * <p>Called from {@link BodySceneManager#update} on the JME render thread after body scene positions have been set for
 * the current frame. All shadow computation runs on the JME render thread only (CLAUDE.md Rule 4).
 *
 * <h3>Shadow model</h3>
 *
 * <p>For each receiver body with a {@code DRAW_FULL} scene node the manager:
 *
 * <ol>
 *   <li>Finds all potential shadow casters (every other body in the active body-node map).
 *   <li>Applies the {@link ShadowGeometry#canCastShadow} early-out to skip geometrically impossible casters.
 *   <li>Limits the occluder list to {@link RenderQuality#maxOccluders()}.
 *   <li>Sets per-frame uniforms: {@code SunPosition}, {@code SunRadius}, {@code OccluderCount},
 *       {@code OccluderPositions[0..n-1]}, {@code OccluderRadii[0..n-1]}, {@code ExtendedSource}.
 * </ol>
 *
 * <p>The ring material (Saturn) is updated separately with ring-specific caster positions in Saturn body-fixed space.
 *
 * <h3>Ephemeris access</h3>
 *
 * <p>Body radii and Sun radius are retrieved from {@code KEPPLRConfiguration.getInstance().getEphemeris()} at
 * point-of-use (CLAUDE.md Rule 3). Scene positions (already computed by {@link BodySceneManager}) are read directly
 * from the scene graph.
 *
 * <h3>No hardcoded caster/receiver pairs</h3>
 *
 * <p>The caster/receiver loop is fully general: every body is a potential caster for every other body. No body is
 * hardcoded as the sole caster or receiver for any other.
 */
class EclipseShadowManager {

    private static final int SUN_NAIF_ID = 10;

    /** Simulation state; read each frame for render quality (§9.4). */
    private final SimulationState state;

    /** Saturn ring manager; provides the ring material for ring-shadow uniform updates. */
    private final SaturnRingManager saturnRingManager;

    // ── Scratch arrays — reused every frame to avoid allocation ─────────────────────────────

    private final Vector3f[] occluderPositionsBuf = new Vector3f[KepplrConstants.SHADOW_MAX_OCCLUDERS];
    private final float[] occluderRadiiBuf = new float[KepplrConstants.SHADOW_MAX_OCCLUDERS];
    private final Vector3f[] moonPosBuf = new Vector3f[KepplrConstants.SHADOW_MAX_OCCLUDERS];
    private final float[] moonRadiiBuf = new float[KepplrConstants.SHADOW_MAX_OCCLUDERS];

    EclipseShadowManager(SimulationState state, SaturnRingManager saturnRingManager) {
        this.state = state;
        this.saturnRingManager = saturnRingManager;
        for (int i = 0; i < KepplrConstants.SHADOW_MAX_OCCLUDERS; i++) {
            occluderPositionsBuf[i] = new Vector3f();
            moonPosBuf[i] = new Vector3f();
        }
    }

    /**
     * Update shadow uniforms for all active body materials and the Saturn ring material.
     *
     * @param bodyNodes all currently active body scene nodes (DRAW_FULL and DRAW_SPRITE)
     * @param saturnBsn Saturn's BodySceneNode when Saturn is DRAW_FULL; null otherwise
     * @param et current simulation ET (TDB seconds past J2000)
     * @param cameraHelioJ2000 camera heliocentric J2000 position in km (length-3 array)
     */
    void update(
            Collection<BodySceneNode> bodyNodes,
            BodySceneNode saturnBsn,
            double et,
            double[] cameraHelioJ2000) {

        RenderQuality quality = state.renderQualityProperty().get();
        int maxOccluders = quality.maxOccluders();
        boolean extendedSource = quality.extendedSource();

        // Sun scene position (floating origin: Sun helio pos = [0,0,0])
        Vector3f sunScenePos = new Vector3f(
                (float) -cameraHelioJ2000[0],
                (float) -cameraHelioJ2000[1],
                (float) -cameraHelioJ2000[2]);

        // Sun radius from ephemeris at point-of-use (Architecture Rule 3)
        float sunRadius = sunRadiusKm();

        // Collect all DRAW_FULL nodes (those with fullGeom visible = CullHint.Inherit)
        List<BodySceneNode> fullNodes = new ArrayList<>();
        for (BodySceneNode bsn : bodyNodes) {
            if (isDrawFull(bsn)) {
                fullNodes.add(bsn);
            }
        }


        // ── Per-body shadow update ────────────────────────────────────────────────────────────
        for (BodySceneNode receiver : fullNodes) {
            if (receiver.naifId == SUN_NAIF_ID) continue; // Sun does not receive shadows

            // Check if this body's material is an EclipseLighting material (not Unshaded)
            if (!isEclipseMaterial(receiver.fullGeom)) continue;

            Vector3f receiverPos = receiver.ephemerisNode.getWorldTranslation();

            // Collect valid shadow casters for this receiver
            int count = 0;
            for (BodySceneNode caster : fullNodes) {
                if (count >= maxOccluders) break;
                if (caster == receiver) continue;
                if (caster.naifId == SUN_NAIF_ID) continue; // Sun does not cast shadows on others

                Vector3f casterPos = caster.ephemerisNode.getWorldTranslation();

                // Early-out: caster is behind the receiver relative to the Sun
                if (!canCastShadow(receiverPos, sunScenePos, casterPos)) continue;

                float casterRadius = bodyMeanRadiusKm(caster.naifId);
                if (!(casterRadius > 0f)) continue;

                occluderPositionsBuf[count].set(casterPos);
                occluderRadiiBuf[count] = casterRadius;
                count++;
            }

            setBodyShadowUniforms(
                    receiver.fullGeom, sunScenePos, sunRadius, count, extendedSource, saturnBsn);
        }

        // ── Ring material shadow update (Saturn only) ─────────────────────────────────────────
        updateRingShadow(saturnBsn, fullNodes, sunScenePos, sunRadius, maxOccluders, et);
    }

    // ── Private helpers ──────────────────────────────────────────────────────────────────────────

    private void setBodyShadowUniforms(
            Geometry fullGeom,
            Vector3f sunPos,
            float sunRadius,
            int occluderCount,
            boolean extendedSource,
            BodySceneNode saturnBsn) {

        var mat = fullGeom.getMaterial();
        mat.setVector3("SunPosition", sunPos);
        mat.setFloat("SunRadius", sunRadius);
        mat.setInt("OccluderCount", occluderCount);
        if (occluderCount > 0) {
            mat.setParam("OccluderPositions", com.jme3.shader.VarType.Vector3Array, occluderPositionsBuf);
            mat.setParam("OccluderRadii", com.jme3.shader.VarType.FloatArray, occluderRadiiBuf);
        }
        mat.setBoolean("ExtendedSource", extendedSource);

        // Ring shadow uniforms for Saturn's disk (if this is Saturn and rings exist)
        if (saturnBsn != null && fullGeom == saturnBsn.fullGeom) {
            var ringMat = saturnRingManager.getRingMaterial();
            if (ringMat != null) {
                // Ring plane normal in world space = Saturn body-fixed Z axis transformed to world
                Quaternion bodyFixedToWorld = saturnBsn.bodyFixedNode.getWorldRotation();
                Vector3f ringNormal = bodyFixedToWorld.mult(Vector3f.UNIT_Z);
                mat.setVector3("RingNormal", ringNormal);
                mat.setVector3("RingSaturnCenter", saturnBsn.ephemerisNode.getWorldTranslation());
                mat.setFloat("RingInner", (float) KepplrConstants.SATURN_RING_INNER_RADIUS_KM);
                mat.setFloat("RingOuter", (float) KepplrConstants.SATURN_RING_OUTER_RADIUS_KM);
                mat.setFloat("RingTauScale", KepplrConstants.RING_TAU_SCALE);
                // RingTransparencyTex is the same texture used by the ring material
                var texParam = ringMat.getTextureParam("TransparencyTex");
                if (texParam != null) {
                    mat.setTexture("RingTransparencyTex", texParam.getTextureValue());
                }
            }
        }
    }

    private void updateRingShadow(
            BodySceneNode saturnBsn,
            List<BodySceneNode> fullNodes,
            Vector3f sunScenePos,
            float sunRadius,
            int maxOccluders,
            double et) {

        var ringMaterial = saturnRingManager.getRingMaterial();
        if (ringMaterial == null || saturnBsn == null) return;

        // Enable shadow darkness parameters (0 by default in 16a j3md)
        ringMaterial.setFloat("ShadowDarkness", KepplrConstants.RING_SHADOW_DARKNESS);
        ringMaterial.setFloat("MoonShadowDarkness", KepplrConstants.RING_MOON_SHADOW_DARKNESS);

        // Add SunRadius and SunDistance to ring material for penumbra computation
        ringMaterial.setFloat("SunRadius", sunRadius);
        Vector3f saturnScenePos = saturnBsn.ephemerisNode.getWorldTranslation();
        float sunDist = saturnScenePos.distance(sunScenePos);
        ringMaterial.setFloat("SunDistance", sunDist);

        // Collect moon shadow casters for the ring (bodies orbiting Saturn, not Saturn itself)
        // The ring shader works in Saturn body-fixed space, so positions must be transformed.
        Quaternion worldToBodyFixed = saturnBsn.bodyFixedNode.getWorldRotation().inverse();
        Vector3f saturnWorld = saturnBsn.ephemerisNode.getWorldTranslation();

        int moonCount = 0;
        for (BodySceneNode caster : fullNodes) {
            if (moonCount >= maxOccluders) break;
            if (caster.naifId == SUN_NAIF_ID) continue;
            if (caster.naifId == KepplrConstants.SATURN_NAIF_ID) continue;
            // Satellites of Saturn: NAIF IDs 600–699 (not ending in 00)
            if (!isSaturnMoon(caster.naifId)) continue;

            float moonRadius = bodyMeanRadiusKm(caster.naifId);
            if (!(moonRadius > 0f)) continue;

            Vector3f moonWorld = caster.ephemerisNode.getWorldTranslation();
            // Transform moon position to Saturn body-fixed space:
            // bodyFixedPos = worldToBodyFixed * (moonWorld - saturnWorld)
            Vector3f relWorld = moonWorld.subtract(saturnWorld);
            Vector3f moonBodyFixed = worldToBodyFixed.mult(relWorld);

            moonPosBuf[moonCount].set(moonBodyFixed);
            moonRadiiBuf[moonCount] = moonRadius;
            moonCount++;
        }

        ringMaterial.setInt("NumShadowCasters", moonCount);
        if (moonCount > 0) {
            ringMaterial.setParam("ShadowCasterPos", com.jme3.shader.VarType.Vector3Array, moonPosBuf);
            ringMaterial.setParam("ShadowCasterRadius", com.jme3.shader.VarType.FloatArray, moonRadiiBuf);
        }
    }

    /**
     * Returns the mean radius of a body in km, retrieved from ephemeris at point-of-use.
     * Returns 0 if the shape is unavailable.
     */
    private static float bodyMeanRadiusKm(int naifId) {
        try {
            var eph = KEPPLRConfiguration.getInstance().getEphemeris();
            var body = eph.getSpiceBundle().getObject(naifId);
            Ellipsoid shape = eph.getShape(body);
            if (shape == null) return 0f;
            return (float) ((shape.getA() + shape.getB() + shape.getC()) / 3.0);
        } catch (Exception e) {
            return 0f;
        }
    }

    /** Returns the Sun's mean radius in km from ephemeris at point-of-use. */
    private static float sunRadiusKm() {
        return bodyMeanRadiusKm(SUN_NAIF_ID);
    }

    /**
     * Early-out geometry test: returns true if the caster could geometrically cast a shadow on the receiver.
     *
     * <p>A caster in the anti-solar hemisphere of the receiver cannot cast a shadow on it.
     */
    private static boolean canCastShadow(Vector3f receiverPos, Vector3f sunPos, Vector3f casterPos) {
        // sunDir and casterDir from receiver; if dot product <= 0, caster is behind receiver
        float sx = sunPos.x - receiverPos.x;
        float sy = sunPos.y - receiverPos.y;
        float sz = sunPos.z - receiverPos.z;
        float cx = casterPos.x - receiverPos.x;
        float cy = casterPos.y - receiverPos.y;
        float cz = casterPos.z - receiverPos.z;
        return (sx * cx + sy * cy + sz * cz) > 0.0f;
    }

    /** Returns true if the body node's fullGeom has an EclipseLighting material. */
    private static boolean isEclipseMaterial(Geometry fullGeom) {
        return Boolean.TRUE.equals(fullGeom.getUserData("eclipseMaterial"));
    }

    /**
     * Returns true if the body is currently rendered as DRAW_FULL.
     *
     * <p>{@link BodySceneNode#apply} sets {@code fullGeom} to {@code CullHint.Always} for DRAW_SPRITE and
     * {@code CullHint.Inherit} for DRAW_FULL. JME 3.8.1's {@link Spatial#getCullHint} resolves inherited hints up the
     * parent chain; a DRAW_FULL fullGeom with stored {@code Inherit} resolves to {@code Dynamic} because the custom
     * viewport root nodes carry no explicit hint. Testing {@code != Always} is therefore more reliable than
     * {@code == Inherit}.
     */
    private static boolean isDrawFull(BodySceneNode bsn) {
        return bsn.fullGeom.getCullHint() != Spatial.CullHint.Always;
    }

    /** Returns true if the NAIF ID belongs to a Saturn moon (600–699, not 699 itself). */
    private static boolean isSaturnMoon(int naifId) {
        return naifId >= 600 && naifId <= 699 && naifId != KepplrConstants.SATURN_NAIF_ID;
    }
}
