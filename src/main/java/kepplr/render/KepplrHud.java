package kepplr.render;

import com.jme3.asset.AssetManager;
import com.jme3.font.BitmapFont;
import com.jme3.font.BitmapText;
import com.jme3.math.ColorRGBA;
import com.jme3.renderer.Camera;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import kepplr.config.KEPPLRConfiguration;
import kepplr.ephemeris.BodyLookupService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import picante.math.vectorspace.VectorIJK;

/**
 * JME HUD overlay displaying simulation time and body info (REDESIGN.md §7.9).
 *
 * <p>Lives in the render package and is updated exclusively on the <b>JME render thread</b> via
 * {@link KepplrApp#simpleUpdate(float)}. It reads ET directly from {@link kepplr.state.SimulationState} on the same
 * thread, so the displayed time is always consistent with simulation state in the same frame.
 *
 * <p>Two independent displays:
 *
 * <ul>
 *   <li><b>Time display</b> (upper-right): current UTC time
 *   <li><b>Info display</b> (upper-left): focused body name and distance from camera
 * </ul>
 *
 * <p>Never calls {@link javafx.application.Platform#runLater} (CLAUDE.md Rule 2). Acquires
 * {@link picante.time.TimeConversion} at point-of-use (CLAUDE.md Rule 3).
 */
public final class KepplrHud {

    private static final Logger logger = LogManager.getLogger();

    private static final float TEXT_SIZE_PX = 16f;
    private static final float MARGIN_PX = 10f;

    private final Camera cam;
    private final BitmapText utcLabel;
    private final BitmapText infoLabel;

    private boolean timeVisible = true;
    private boolean infoVisible = true;

    /**
     * Create the HUD and attach its nodes to {@code guiNode}.
     *
     * @param guiNode the JME GUI node (screen-space, orthographic)
     * @param assetManager the JME asset manager used to load the default bitmap font
     * @param cam the camera — used to position the HUD relative to the viewport
     */
    public KepplrHud(Node guiNode, AssetManager assetManager, Camera cam) {
        this.cam = cam;

        BitmapFont font = assetManager.loadFont("Interface/Fonts/Default.fnt");

        utcLabel = new BitmapText(font, false);
        utcLabel.setSize(TEXT_SIZE_PX);
        utcLabel.setColor(ColorRGBA.White);
        utcLabel.setText("UTC: —");

        infoLabel = new BitmapText(font, false);
        infoLabel.setSize(TEXT_SIZE_PX);
        infoLabel.setColor(ColorRGBA.White);
        infoLabel.setText("");
        infoLabel.setCullHint(Spatial.CullHint.Always);

        guiNode.attachChild(utcLabel);
        guiNode.attachChild(infoLabel);

        repositionLabels();
    }

    /** Show or hide the time display. */
    public void setTimeVisible(boolean visible) {
        this.timeVisible = visible;
        utcLabel.setCullHint(visible ? Spatial.CullHint.Inherit : Spatial.CullHint.Always);
    }

    /** Show or hide the info display. */
    public void setInfoVisible(boolean visible) {
        this.infoVisible = visible;
        infoLabel.setCullHint(visible ? Spatial.CullHint.Inherit : Spatial.CullHint.Always);
    }

    /**
     * Update the HUD text for this frame.
     *
     * <p>Must be called on the JME render thread. Acquires ephemeris at point-of-use (CLAUDE.md Rule 3).
     *
     * @param currentEt current simulation ET (TDB seconds past J2000)
     * @param focusedBodyId NAIF ID of the focused body, or -1 if none
     * @param cameraHelioJ2000 camera heliocentric position in J2000 [x,y,z] km, or null
     */
    public void update(double currentEt, int focusedBodyId, double[] cameraHelioJ2000) {
        repositionLabels();

        if (timeVisible) {
            String utc = formatEt(currentEt);
            utcLabel.setText("UTC: " + utc);
        }

        if (infoVisible && focusedBodyId != -1 && cameraHelioJ2000 != null) {
            String name = BodyLookupService.formatName(focusedBodyId);
            double distKm = computeDistance(focusedBodyId, currentEt, cameraHelioJ2000);
            String distStr = distKm >= 0 ? formatDistance(distKm) : "—";
            infoLabel.setText(name + "  " + distStr);
            infoLabel.setCullHint(Spatial.CullHint.Inherit);
        } else if (infoVisible) {
            infoLabel.setCullHint(Spatial.CullHint.Always);
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void repositionLabels() {
        float top = cam.getHeight() - MARGIN_PX;
        // Info display: upper-left
        infoLabel.setLocalTranslation(MARGIN_PX, top, 0f);
        // Time display: upper-right
        float timeWidth = utcLabel.getLineWidth();
        utcLabel.setLocalTranslation(cam.getWidth() - timeWidth - MARGIN_PX, top, 0f);
    }

    private static double computeDistance(int focusedBodyId, double et, double[] cameraHelioJ2000) {
        try {
            VectorIJK bodyPos =
                    KEPPLRConfiguration.getInstance().getEphemeris().getHeliocentricPositionJ2000(focusedBodyId, et);
            if (bodyPos == null) return -1;
            double dx = cameraHelioJ2000[0] - bodyPos.getI();
            double dy = cameraHelioJ2000[1] - bodyPos.getJ();
            double dz = cameraHelioJ2000[2] - bodyPos.getK();
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        } catch (Exception e) {
            logger.debug("HUD: could not compute distance to NAIF {}: {}", focusedBodyId, e.getMessage());
            return -1;
        }
    }

    /** Format distance with appropriate units (km, AU, or scientific notation). */
    static String formatDistance(double km) {
        if (km < 1e6) {
            return String.format("%.0f km", km);
        } else if (km < 1.496e8 * 10) {
            // Under 10 AU, show AU
            double au = km / 1.496e8;
            return String.format("%.3f AU", au);
        } else {
            double au = km / 1.496e8;
            return String.format("%.1f AU", au);
        }
    }

    private static String formatEt(double et) {
        try {
            return KEPPLRConfiguration.getInstance()
                    .getTimeConversion()
                    .format("C")
                    .apply(et);
        } catch (Exception e) {
            logger.warn("HUD: could not format ET {}: {}", et, e.getMessage());
            return "—";
        }
    }
}
