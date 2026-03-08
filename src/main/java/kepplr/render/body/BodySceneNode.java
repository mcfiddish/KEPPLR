package kepplr.render.body;

import com.jme3.math.Matrix3f;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import kepplr.render.frustum.FrustumLayer;
import kepplr.util.KepplrConstants;
import picante.math.vectorspace.RotationMatrixIJK;

/**
 * Holds the JME scene-graph node hierarchy for a single rendered body.
 *
 * <p>Scene hierarchy (created by {@link BodyNodeFactory}):
 *
 * <pre>
 * ephemerisNode  — local translation = body's camera-relative position (km)
 * ├── bodyFixedNode  — local rotation = J2000 → body-fixed transform
 * │   └── textureAlignNode  — center-longitude offset (child of bodyFixed)
 * │       └── fullGeom  — textured or untextured ellipsoid
 * └── spriteGeom  — point sprite (tiny sphere, sized per frame)
 * </pre>
 *
 * <p>Each frame, {@link BodySceneManager} calls {@link #updatePosition}, optionally {@link #updateRotation}, and then
 * {@link #apply} to set the cull state and frustum attachment.
 *
 * <p>Instances are created exclusively by {@link BodyNodeFactory}; the constructor is package-private.
 */
public class BodySceneNode {

    final Node ephemerisNode;
    final Node bodyFixedNode;
    final Geometry fullGeom;
    final Geometry spriteGeom;

    /** Frustum layer node this body is currently attached to; null when culled. */
    private Node currentParent;

    BodySceneNode(Node ephemerisNode, Node bodyFixedNode, Geometry fullGeom, Geometry spriteGeom) {
        this.ephemerisNode = ephemerisNode;
        this.bodyFixedNode = bodyFixedNode;
        this.fullGeom = fullGeom;
        this.spriteGeom = spriteGeom;
        this.currentParent = null;
    }

    /**
     * Update the body's scene-space position (camera-relative, km).
     *
     * @param scenePos camera-relative position in km
     */
    public void updatePosition(Vector3f scenePos) {
        ephemerisNode.setLocalTranslation(scenePos);
    }

    /**
     * Update the body-fixed-to-J2000 rotation applied to the body geometry.
     *
     * <p>The scene graph transforms local (body-fixed) coordinates to parent (J2000) coordinates: {@code v_J2000 = q *
     * v_bodyFixed}. The ephemeris provides R = J2000→bodyFixed, so we must apply its transpose R^T (bodyFixed→J2000) to
     * the node. Applying R directly would invert the rotation direction and produce a 180° phase offset at the J2000
     * epoch.
     *
     * @param rot J2000-to-body-fixed rotation matrix from {@code KEPPLREphemeris}
     */
    public void updateRotation(RotationMatrixIJK rot) {
        // Transpose: m[i][j] = rot.get(j, i) — converts J2000→bodyFixed into bodyFixed→J2000
        Matrix3f m = new Matrix3f(
                (float) rot.get(0, 0),
                (float) rot.get(1, 0),
                (float) rot.get(2, 0),
                (float) rot.get(0, 1),
                (float) rot.get(1, 1),
                (float) rot.get(2, 1),
                (float) rot.get(0, 2),
                (float) rot.get(1, 2),
                (float) rot.get(2, 2));
        Quaternion q = new Quaternion();
        q.fromRotationMatrix(m);
        bodyFixedNode.setLocalRotation(q);
    }

    /**
     * Apply the current rendering decision and frustum assignment.
     *
     * <p>Manages scene-graph attachment: ensures {@link #ephemerisNode} is attached to the correct frustum layer node,
     * and the correct geometry (full ellipsoid or point sprite) is visible.
     *
     * @param decision how to render this body
     * @param layer which frustum layer this body belongs to
     * @param nearNode near-frustum root node
     * @param midNode mid-frustum root node
     * @param farNode far-frustum root node
     * @param distKm camera-to-body distance (km), used for sprite scaling
     * @param viewportHeight viewport height in pixels
     * @param fovYDeg camera vertical FOV in degrees
     */
    public void apply(
            CullDecision decision,
            FrustumLayer layer,
            Node nearNode,
            Node midNode,
            Node farNode,
            double distKm,
            int viewportHeight,
            float fovYDeg) {

        if (decision == CullDecision.CULL) {
            detach();
            return;
        }

        Node targetParent =
                switch (layer) {
                    case NEAR -> nearNode;
                    case MID -> midNode;
                    case FAR -> farNode;
                };

        if (currentParent != targetParent) {
            if (currentParent != null) currentParent.detachChild(ephemerisNode);
            targetParent.attachChild(ephemerisNode);
            currentParent = targetParent;
        }

        if (decision == CullDecision.DRAW_FULL) {
            fullGeom.setCullHint(Spatial.CullHint.Inherit);
            spriteGeom.setCullHint(Spatial.CullHint.Always);
        } else {
            // DRAW_SPRITE
            fullGeom.setCullHint(Spatial.CullHint.Always);
            spriteGeom.setCullHint(Spatial.CullHint.Inherit);
            updateSpriteScale(distKm, viewportHeight, fovYDeg);
        }
    }

    /** Detach this body from the scene entirely (used when culled or body disappears from ephemeris). */
    public void detach() {
        if (currentParent != null) {
            currentParent.detachChild(ephemerisNode);
            currentParent = null;
        }
    }

    /**
     * Scale the sprite geometry so it appears as {@link KepplrConstants#SPACECRAFT_POINT_SPRITE_SIZE} pixels regardless
     * of distance.
     */
    private void updateSpriteScale(double distKm, int viewportHeight, float fovYDeg) {
        if (distKm <= 0 || viewportHeight <= 0 || fovYDeg <= 0) return;
        double tanHalfFov = Math.tan(Math.toRadians(fovYDeg) / 2.0);
        double worldRadius =
                KepplrConstants.SPACECRAFT_POINT_SPRITE_SIZE * distKm * tanHalfFov / (viewportHeight / 2.0);
        spriteGeom.setLocalScale((float) Math.max(worldRadius, 0.001f));
    }
}
