package kepplr.ephemeris;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AberrationCorrection}.
 */
@DisplayName("AberrationCorrection")
class AberrationCorrectionTest {

    @Test
    @DisplayName("NONE maps to Picante NONE")
    void noneMapsCorrectly() {
        assertEquals(
                picante.mechanics.providers.aberrated.AberrationCorrection.NONE,
                AberrationCorrection.NONE.toPicante());
    }

    @Test
    @DisplayName("LT_S maps to Picante LT_S")
    void ltSMapsCorrectly() {
        assertEquals(
                picante.mechanics.providers.aberrated.AberrationCorrection.LT_S,
                AberrationCorrection.LT_S.toPicante());
    }

    @Test
    @DisplayName("Only two correction modes exist (§6.1)")
    void onlyTwoModes() {
        assertEquals(2, AberrationCorrection.values().length,
                "REDESIGN.md §6.1 requires exactly two aberration correction modes");
    }
}
