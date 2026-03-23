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
import kepplr.util.KepplrConstants;
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
 *   <li><b>Info display</b> (upper-left): selected body name and distance from camera
 * </ul>
 *
 * <p>Never calls {@link javafx.application.Platform#runLater} (CLAUDE.md Rule 2). Acquires
 * {@link picante.time.TimeConversion} at point-of-use (CLAUDE.md Rule 3).
 */
public final class KepplrHud {

    private static final Logger logger = LogManager.getLogger();

    private static final float TEXT_SIZE_PX = 16f;
    private static final float MARGIN_PX = 10f;
    private static final float MESSAGE_BOTTOM_MARGIN_PX = 60f;

    private final Camera cam;
    private final BitmapText utcLabel;
    private final BitmapText infoLabel;
    private final BitmapText messageLabel;

    private boolean timeVisible = true;
    private boolean infoVisible = true;

    /** Wall-clock time (System.nanoTime) when the current message was shown, or -1 if no active message. */
    private long messageStartNanos = -1;
    /** Total display duration of the current message in seconds (before fade begins). */
    private double messageDurationSec;

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

        messageLabel = new BitmapText(font, false);
        messageLabel.setSize(TEXT_SIZE_PX);
        messageLabel.setColor(ColorRGBA.White);
        messageLabel.setText("");
        messageLabel.setCullHint(Spatial.CullHint.Always);

        guiNode.attachChild(utcLabel);
        guiNode.attachChild(infoLabel);
        guiNode.attachChild(messageLabel);

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
     * Display a message on the HUD. Replaces any existing message.
     *
     * @param text message text; may contain {@code \n} for line breaks
     * @param durationSeconds display duration before fade-out begins
     */
    public void showMessage(String text, double durationSeconds) {
        messageLabel.setText(text);
        messageDurationSec = durationSeconds;
        messageStartNanos = System.nanoTime();
        repositionMessageLabel();
        messageLabel.setCullHint(Spatial.CullHint.Inherit);
    }

    /**
     * Update the HUD text for this frame.
     *
     * <p>Must be called on the JME render thread. Acquires ephemeris at point-of-use (CLAUDE.md Rule 3).
     *
     * @param currentEt current simulation ET (TDB seconds past J2000)
     * @param selectedBodyId NAIF ID of the selected body, or -1 if none
     * @param cameraHelioJ2000 camera heliocentric position in J2000 [x,y,z] km, or null
     */
    public void update(double currentEt, int selectedBodyId, double[] cameraHelioJ2000) {
        repositionLabels();

        if (timeVisible) {
            String utc = formatEt(currentEt);
            utcLabel.setText("UTC: " + utc);
        }

        if (infoVisible && selectedBodyId != -1 && cameraHelioJ2000 != null) {
            String name = BodyLookupService.formatName(selectedBodyId);
            double distKm = computeDistance(selectedBodyId, currentEt, cameraHelioJ2000);
            String distStr = distKm >= 0 ? formatDistance(distKm) : "—";
            infoLabel.setText(name + "  " + distStr);
            infoLabel.setCullHint(Spatial.CullHint.Inherit);
        } else if (infoVisible) {
            infoLabel.setCullHint(Spatial.CullHint.Always);
        }

        updateMessage();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void updateMessage() {
        if (messageStartNanos < 0) return;

        double elapsedSec = (System.nanoTime() - messageStartNanos) / 1_000_000_000.0;
        double fadeDuration = KepplrConstants.SCRIPT_MESSAGE_FADE_DURATION_SEC;
        double totalDuration = messageDurationSec + fadeDuration;

        if (elapsedSec >= totalDuration) {
            // Message fully expired
            messageLabel.setCullHint(Spatial.CullHint.Always);
            messageStartNanos = -1;
            return;
        }

        if (elapsedSec > messageDurationSec) {
            // Fading out
            float alpha = 1.0f - (float) ((elapsedSec - messageDurationSec) / fadeDuration);
            messageLabel.setColor(new ColorRGBA(1f, 1f, 1f, alpha));
        }

        repositionMessageLabel();
    }

    private void repositionMessageLabel() {
        float textWidth = messageLabel.getLineWidth();
        float textHeight = messageLabel.getHeight();
        float x = (cam.getWidth() - textWidth) / 2f;
        float y = MESSAGE_BOTTOM_MARGIN_PX + textHeight;
        messageLabel.setLocalTranslation(x, y, 0f);
    }

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
