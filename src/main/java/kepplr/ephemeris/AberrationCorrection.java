package kepplr.ephemeris;

/**
 * Aberration correction modes supported by KEPPLR.
 *
 * <p>Only two modes are allowed (REDESIGN.md §6.1):
 * <ul>
 *   <li>{@link #NONE} — geometric (no correction)</li>
 *   <li>{@link #LT_S} — light-time plus stellar aberration</li>
 * </ul>
 *
 * <p>This enum maps to Picante's {@code AberrationCorrection} while enforcing
 * the restricted set at compile time.
 */
public enum AberrationCorrection {

    /** No aberration correction; use geometric positions. */
    NONE(picante.mechanics.providers.aberrated.AberrationCorrection.NONE),

    /** Light-time plus stellar aberration correction. */
    LT_S(picante.mechanics.providers.aberrated.AberrationCorrection.LT_S);

    private final picante.mechanics.providers.aberrated.AberrationCorrection picanteValue;

    AberrationCorrection(picante.mechanics.providers.aberrated.AberrationCorrection picanteValue) {
        this.picanteValue = picanteValue;
    }

    /**
     * Returns the corresponding Picante {@code AberrationCorrection} constant.
     *
     * @return Picante-compatible aberration correction value
     */
    public picante.mechanics.providers.aberrated.AberrationCorrection toPicante() {
        return picanteValue;
    }
}
