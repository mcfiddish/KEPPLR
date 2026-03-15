package kepplr.render.label;

import com.jme3.asset.AssetManager;
import com.jme3.font.BitmapFont;
import com.jme3.font.BitmapText;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kepplr.config.KEPPLRConfiguration;
import kepplr.ephemeris.KEPPLREphemeris;
import kepplr.ephemeris.spice.SpiceBundle;
import kepplr.util.KepplrConstants;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picante.mechanics.EphemerisID;

/**
 * Manages body name labels rendered as screen-space text on the JME GUI node (REDESIGN.md §7.8).
 *
 * <p>Labels are positioned by projecting each body's scene-graph position to screen coordinates. The decluttering
 * algorithm suppresses labels that are too close to a larger-radius body's label, producing zoom-dependent behavior:
 * major planets are labeled at large distances; satellite labels appear as the camera zooms in.
 *
 * <p>All methods must be called on the JME render thread (CLAUDE.md Rule 4).
 */
public class LabelManager {

    private static final Logger logger = LogManager.getLogger();

    private static final float LABEL_SIZE_PX = 14f;
    private static final float LABEL_OFFSET_PX = 8f;

    private final Node guiNode;
    private final BitmapFont font;

    /** Per-NAIF-ID label nodes. Created lazily on first update. */
    private final Map<Integer, BitmapText> labels = new HashMap<>();

    /** Per-NAIF-ID enabled state. */
    private final Map<Integer, Boolean> enabledLabels = new HashMap<>();

    public LabelManager(Node guiNode, AssetManager assetManager) {
        this.guiNode = guiNode;
        this.font = assetManager.loadFont("Interface/Fonts/Default.fnt");
    }

    /** Enable or disable the label for the given body. */
    public void setLabelVisible(int naifId, boolean visible) {
        enabledLabels.put(naifId, visible);
        if (!visible) {
            BitmapText label = labels.get(naifId);
            if (label != null) {
                label.setCullHint(Spatial.CullHint.Always);
            }
        }
    }

    /**
     * Update label positions and apply decluttering.
     *
     * @param cam the JME camera
     * @param nearNode near frustum root node
     * @param midNode mid frustum root node
     * @param farNode far frustum root node
     */
    public void update(Camera cam, Node nearNode, Node midNode, Node farNode) {
        // Collect all body screen discs for occlusion testing (all bodies, not just labeled)
        List<ScreenDisc> bodyDiscs = new ArrayList<>();
        collectBodyDiscs(cam, nearNode, bodyDiscs);
        collectBodyDiscs(cam, midNode, bodyDiscs);
        collectBodyDiscs(cam, farNode, bodyDiscs);

        // Collect candidates: enabled bodies with scene-graph nodes
        List<LabelCandidate> candidates = new ArrayList<>();
        collectCandidates(cam, nearNode, candidates);
        collectCandidates(cam, midNode, candidates);
        collectCandidates(cam, farNode, candidates);

        // Sort by physical radius descending (larger bodies have priority)
        candidates.sort(
                Comparator.comparingDouble(LabelCandidate::physicalRadiusKm).reversed());

        // Declutter and position
        List<LabelCandidate> approved = filterLabels(candidates);

        // Hide all labels first
        for (BitmapText label : labels.values()) {
            label.setCullHint(Spatial.CullHint.Always);
        }

        // Show and position approved labels (with occlusion check)
        for (LabelCandidate c : approved) {
            if (isOccludedByCloserBody(c, bodyDiscs)) continue;
            BitmapText label = getOrCreateLabel(c.naifId, c.name);
            label.setLocalTranslation((float) c.screenX + LABEL_OFFSET_PX, (float) c.screenY + LABEL_OFFSET_PX, 0f);
            label.setCullHint(Spatial.CullHint.Inherit);
        }
    }

    /** Collect all bodies' screen-space discs for occlusion testing. */
    private void collectBodyDiscs(Camera cam, Node layerNode, List<ScreenDisc> discs) {
        for (Spatial child : layerNode.getChildren()) {
            Integer naifId = child.getUserData("naifId");
            if (naifId == null) continue;

            Number radiusNum = child.getUserData("bodyRadiusKm");
            double radiusKm = radiusNum != null ? radiusNum.doubleValue() : 0.0;
            if (radiusKm <= 0) continue;

            Vector3f worldPos = child.getWorldTranslation();
            float distKm = worldPos.length();
            if (distKm < 1e-3) continue;

            Vector3f camDir = cam.getDirection();
            float dot = camDir.dot(worldPos.normalize());
            if (dot < 0) continue;

            Vector3f screen = cam.getScreenCoordinates(worldPos);
            // Apparent angular radius → screen pixels (approximate)
            double angularRadiusRad = Math.atan2(radiusKm, distKm);
            double fovYRad = Math.toRadians(cam.getFov());
            double screenRadiusPx = (angularRadiusRad / fovYRad) * cam.getHeight();

            discs.add(new ScreenDisc(naifId, screen.x, screen.y, distKm, screenRadiusPx));
        }
    }

    /** Check if a label position is inside any closer body's screen-space disc. */
    private static boolean isOccludedByCloserBody(LabelCandidate label, List<ScreenDisc> discs) {
        for (ScreenDisc disc : discs) {
            if (disc.naifId == label.naifId) continue; // don't self-occlude
            double dx = label.screenX - disc.screenX;
            double dy = label.screenY - disc.screenY;
            double distSq = dx * dx + dy * dy;
            if (distSq < disc.screenRadiusPx * disc.screenRadiusPx) {
                return true; // label falls inside this body's disc
            }
        }
        return false;
    }

    private void collectCandidates(Camera cam, Node layerNode, List<LabelCandidate> candidates) {
        for (Spatial child : layerNode.getChildren()) {
            Integer naifId = child.getUserData("naifId");
            if (naifId == null) continue;
            if (!Boolean.TRUE.equals(enabledLabels.get(naifId))) continue;

            Number radiusNum = child.getUserData("bodyRadiusKm");
            double radiusKm = radiusNum != null ? radiusNum.doubleValue() : 0.0;

            Vector3f worldPos = child.getWorldTranslation();
            float distKm = worldPos.length();
            if (distKm < 1e-3) continue;

            // Only label sprite-sized bodies (apparent radius below full-draw threshold)
            if (radiusKm > 0) {
                double angularRadiusRad = Math.atan2(radiusKm, distKm);
                double fovYRad = Math.toRadians(cam.getFov());
                double apparentRadiusPx = (angularRadiusRad / fovYRad) * cam.getHeight();
                if (apparentRadiusPx >= KepplrConstants.DRAW_FULL_MIN_APPARENT_RADIUS_PX) continue;
            }

            // Check if behind camera
            Vector3f camDir = cam.getDirection();
            float dot = camDir.dot(worldPos.normalize());
            if (dot < 0) continue;

            Vector3f screen = cam.getScreenCoordinates(worldPos);
            if (screen.x < 0 || screen.x > cam.getWidth() || screen.y < 0 || screen.y > cam.getHeight()) continue;

            String name = resolveName(naifId);
            candidates.add(new LabelCandidate(naifId, name, screen.x, screen.y, radiusKm));
        }
    }

    /**
     * Apply decluttering: keep only labels that are not within
     * {@link KepplrConstants#LABEL_DECLUTTER_MIN_SEPARATION_PX} of an already-approved larger-radius label.
     *
     * <p>Package-private for unit testing.
     *
     * @param candidates sorted by physical radius descending
     * @return filtered list of approved labels
     */
    static List<LabelCandidate> filterLabels(List<LabelCandidate> candidates) {
        List<LabelCandidate> approved = new ArrayList<>();
        double minSep = KepplrConstants.LABEL_DECLUTTER_MIN_SEPARATION_PX;

        for (LabelCandidate c : candidates) {
            boolean tooClose = false;
            for (LabelCandidate a : approved) {
                double dx = c.screenX - a.screenX;
                double dy = c.screenY - a.screenY;
                if (dx * dx + dy * dy < minSep * minSep) {
                    tooClose = true;
                    break;
                }
            }
            if (!tooClose) {
                approved.add(c);
            }
        }
        return approved;
    }

    private BitmapText getOrCreateLabel(int naifId, String name) {
        return labels.computeIfAbsent(naifId, id -> {
            BitmapText text = new BitmapText(font, false);
            text.setSize(LABEL_SIZE_PX);
            text.setColor(ColorRGBA.White);
            text.setText(name);
            text.setCullHint(Spatial.CullHint.Always);
            guiNode.attachChild(text);
            return text;
        });
    }

    private static String resolveName(int naifId) {
        try {
            KEPPLREphemeris eph = KEPPLRConfiguration.getInstance().getEphemeris();
            SpiceBundle bundle = eph.getSpiceBundle();
            Set<EphemerisID> known = eph.getKnownBodies();
            for (EphemerisID id : known) {
                if (bundle.getObjectCode(id).orElse(-999) == naifId) {
                    return bundle.getObjectName(id).orElse("NAIF " + naifId);
                }
            }
            // Check spacecraft
            for (var sc : eph.getSpacecraft()) {
                if (sc.code() == naifId) {
                    return sc.id().getName();
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to resolve name for NAIF {}: {}", naifId, e.getMessage());
        }
        return "NAIF " + naifId;
    }

    /** Label candidate record used during the declutter pass. Package-private for tests. */
    record LabelCandidate(int naifId, String name, double screenX, double screenY, double physicalRadiusKm) {}

    /** Screen-space body disc used for label occlusion checks. */
    private record ScreenDisc(int naifId, double screenX, double screenY, double distKm, double screenRadiusPx) {}
}
