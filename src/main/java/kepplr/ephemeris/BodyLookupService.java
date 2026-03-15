package kepplr.ephemeris;

import kepplr.config.KEPPLRConfiguration;
import kepplr.ephemeris.spice.SpiceBundle;
import picante.mechanics.EphemerisID;

/**
 * Resolves body names and NAIF IDs without storing ephemeris references (CLAUDE.md Rule 3).
 *
 * <p>All lookups acquire the ephemeris at point-of-use via
 * {@link KEPPLRConfiguration#getInstance()}.
 *
 * <p>This class lives in {@code kepplr.ephemeris} so that UI code in {@code kepplr.ui} can delegate
 * name resolution here without containing any resolution logic itself (Step 19 hard constraint).
 */
public final class BodyLookupService {

    private BodyLookupService() {}

    /**
     * Resolve a user-supplied string to a NAIF ID.
     *
     * <p>If the input parses as an integer it is treated as a NAIF ID directly (validated against
     * the SPICE bundle). Otherwise it is treated as a body name and looked up case-insensitively.
     *
     * @param input body name or NAIF ID string; must not be null or blank
     * @return the resolved NAIF ID
     * @throws IllegalArgumentException if the input cannot be resolved
     */
    public static int resolve(String input) {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Input must not be null or blank");
        }
        String trimmed = input.trim();
        SpiceBundle bundle = KEPPLRConfiguration.getInstance().getEphemeris().getSpiceBundle();
        EphemerisID id = bundle.getObject(trimmed);
        if (id == null) {
            throw new IllegalArgumentException("Cannot resolve body: " + trimmed);
        }
        return bundle.getObjectCode(id).orElseThrow(
                () -> new IllegalArgumentException("No NAIF code for: " + trimmed));
    }

    /**
     * Format a NAIF ID as a human-readable body name.
     *
     * @param naifId NAIF body code, or -1 for "no body"
     * @return the SPICE object name (title-cased), or {@code "—"} if {@code naifId == -1},
     *         or {@code "NAIF <id>"} if the name cannot be resolved
     */
    public static String formatName(int naifId) {
        if (naifId == -1) return "—";
        try {
            SpiceBundle bundle = KEPPLRConfiguration.getInstance().getEphemeris().getSpiceBundle();
            EphemerisID id = bundle.getObject(naifId);
            if (id == null) return "NAIF " + naifId;
            return bundle.getObjectName(id)
                    .map(BodyLookupService::titleCase)
                    .orElse("NAIF " + naifId);
        } catch (Exception e) {
            return "NAIF " + naifId;
        }
    }

    /**
     * Title-case a SPICE name: first letter uppercase, rest lowercase.
     *
     * <p>SPICE names are typically all-uppercase (e.g. "EARTH", "MARS BARYCENTER").
     * This converts to "Earth", "Mars Barycenter" for display.
     */
    public static String titleCase(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder sb = new StringBuilder(s.length());
        boolean capitalizeNext = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || c == '_' || c == '-') {
                sb.append(c);
                capitalizeNext = true;
            } else if (capitalizeNext) {
                sb.append(Character.toUpperCase(c));
                capitalizeNext = false;
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }
}
