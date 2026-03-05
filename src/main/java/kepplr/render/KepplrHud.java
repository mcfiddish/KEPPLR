package kepplr.render;

import com.jme3.asset.AssetManager;
import com.jme3.font.BitmapFont;
import com.jme3.font.BitmapText;
import com.jme3.math.ColorRGBA;
import com.jme3.renderer.Camera;
import com.jme3.scene.Node;
import kepplr.config.KEPPLRConfiguration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * JME HUD overlay displaying current simulation time as UTC (REDESIGN.md §10.2).
 *
 * <p>Lives in the render package and is updated exclusively on the <b>JME render thread</b> via
 * {@link KepplrApp#simpleUpdate(float)}. It reads ET directly from {@link kepplr.state.SimulationState} on the same
 * thread, so the displayed time is always consistent with simulation state in the same frame.
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

    /**
     * Create the HUD and attach its nodes to {@code guiNode}.
     *
     * @param guiNode the JME GUI node (screen-space, orthographic)
     * @param assetManager the JME asset manager used to load the default bitmap font
     * @param cam the camera — used to position the HUD relative to the viewport height
     */
    public KepplrHud(Node guiNode, AssetManager assetManager, Camera cam) {
        this.cam = cam;

        BitmapFont font = assetManager.loadFont("Interface/Fonts/Default.fnt");

        utcLabel = new BitmapText(font, false);
        utcLabel.setSize(TEXT_SIZE_PX);
        utcLabel.setColor(ColorRGBA.White);
        utcLabel.setText("UTC: —");
        repositionLabel();

        guiNode.attachChild(utcLabel);
    }

    /**
     * Update the HUD text for this frame.
     *
     * <p>Must be called on the JME render thread. Acquires {@link picante.time.TimeConversion} at point-of-use
     * (CLAUDE.md Rule 3).
     *
     * @param currentEt current simulation ET (TDB seconds past J2000)
     */
    public void update(double currentEt) {
        repositionLabel();
        String utc = formatEt(currentEt);
        utcLabel.setText("UTC: " + utc);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private void repositionLabel() {
        // Top-left corner: JME GUI coordinates have y=0 at bottom, y=height at top
        utcLabel.setLocalTranslation(MARGIN_PX, cam.getHeight() - MARGIN_PX, 0f);
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
