package kepplr.render;

import static org.junit.jupiter.api.Assertions.*;

import kepplr.commands.DefaultSimulationCommands;
import kepplr.core.SimulationClock;
import kepplr.state.DefaultSimulationState;
import kepplr.util.KepplrConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RenderQuality} enum and its wiring through
 * {@link DefaultSimulationState} and {@link DefaultSimulationCommands}.
 */
class RenderQualityTest {

    private DefaultSimulationState state;
    private DefaultSimulationCommands commands;

    @BeforeEach
    void setUp() {
        state = new DefaultSimulationState();
        SimulationClock clock = new SimulationClock(state, 0.0);
        commands = new DefaultSimulationCommands(state, clock);
    }

    // ── Default value ────────────────────────────────────────────────────────────────────────

    @Test
    void defaultRenderQualityIsHigh() {
        assertEquals(RenderQuality.HIGH, state.renderQualityProperty().get());
    }

    // ── setRenderQuality via commands ────────────────────────────────────────────────────────

    @Test
    void setRenderQuality_low_reflectedInProperty() {
        commands.setRenderQuality(RenderQuality.LOW);
        assertEquals(RenderQuality.LOW, state.renderQualityProperty().get());
    }

    @Test
    void setRenderQuality_medium_reflectedInProperty() {
        commands.setRenderQuality(RenderQuality.MEDIUM);
        assertEquals(RenderQuality.MEDIUM, state.renderQualityProperty().get());
    }

    @Test
    void setRenderQuality_high_reflectedInProperty() {
        commands.setRenderQuality(RenderQuality.LOW);  // change away from HIGH first
        commands.setRenderQuality(RenderQuality.HIGH);
        assertEquals(RenderQuality.HIGH, state.renderQualityProperty().get());
    }

    // ── extendedSource() ─────────────────────────────────────────────────────────────────────

    @Test
    void low_extendedSource_isFalse() {
        assertFalse(RenderQuality.LOW.extendedSource());
    }

    @Test
    void medium_extendedSource_isTrue() {
        assertTrue(RenderQuality.MEDIUM.extendedSource());
    }

    @Test
    void high_extendedSource_isTrue() {
        assertTrue(RenderQuality.HIGH.extendedSource());
    }

    // ── maxOccluders() ───────────────────────────────────────────────────────────────────────

    @Test
    void low_maxOccluders_matchesConstant() {
        assertEquals(KepplrConstants.SHADOW_MAX_OCCLUDERS_LOW, RenderQuality.LOW.maxOccluders());
    }

    @Test
    void medium_maxOccluders_matchesConstant() {
        assertEquals(KepplrConstants.SHADOW_MAX_OCCLUDERS_MEDIUM, RenderQuality.MEDIUM.maxOccluders());
    }

    @Test
    void high_maxOccluders_matchesConstant() {
        assertEquals(KepplrConstants.SHADOW_MAX_OCCLUDERS_HIGH, RenderQuality.HIGH.maxOccluders());
    }

    @Test
    void maxOccluders_monotonicallyIncreasing() {
        assertTrue(RenderQuality.LOW.maxOccluders() < RenderQuality.MEDIUM.maxOccluders());
        assertTrue(RenderQuality.MEDIUM.maxOccluders() <= RenderQuality.HIGH.maxOccluders());
    }

    // ── trailSamplesPerPeriod() ───────────────────────────────────────────────────────────────

    @Test
    void low_trailSamples_matchesConstant() {
        assertEquals(KepplrConstants.TRAIL_SAMPLES_PER_PERIOD_LOW, RenderQuality.LOW.trailSamplesPerPeriod());
    }

    @Test
    void medium_trailSamples_matchesConstant() {
        assertEquals(KepplrConstants.TRAIL_SAMPLES_PER_PERIOD_MEDIUM, RenderQuality.MEDIUM.trailSamplesPerPeriod());
    }

    @Test
    void high_trailSamples_usesBaseline() {
        assertEquals(KepplrConstants.TRAIL_SAMPLES_PER_PERIOD, RenderQuality.HIGH.trailSamplesPerPeriod());
    }

    @Test
    void trailSamples_monotonicallyIncreasing() {
        assertTrue(RenderQuality.LOW.trailSamplesPerPeriod() < RenderQuality.MEDIUM.trailSamplesPerPeriod());
        assertTrue(RenderQuality.MEDIUM.trailSamplesPerPeriod() <= RenderQuality.HIGH.trailSamplesPerPeriod());
    }

    // ── starMagnitudeCutoff() ─────────────────────────────────────────────────────────────────

    @Test
    void low_magnitudeCutoff() {
        assertEquals(KepplrConstants.STAR_MAGNITUDE_CUTOFF_LOW, RenderQuality.LOW.starMagnitudeCutoff(), 1e-9);
    }

    @Test
    void medium_magnitudeCutoff() {
        assertEquals(KepplrConstants.STAR_MAGNITUDE_CUTOFF_MEDIUM, RenderQuality.MEDIUM.starMagnitudeCutoff(), 1e-9);
    }

    @Test
    void high_magnitudeCutoff_isHigherThanMedium() {
        assertTrue(RenderQuality.HIGH.starMagnitudeCutoff() > RenderQuality.MEDIUM.starMagnitudeCutoff());
    }

    @Test
    void magnitudeCutoff_monotonicallyIncreasing() {
        assertTrue(RenderQuality.LOW.starMagnitudeCutoff() < RenderQuality.MEDIUM.starMagnitudeCutoff());
        assertTrue(RenderQuality.MEDIUM.starMagnitudeCutoff() < RenderQuality.HIGH.starMagnitudeCutoff());
    }
}
