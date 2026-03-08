package kepplr.render.trail;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TrailManager")
public class TrailManagerTest {

    /** Phobos NAIF ID — known-good SPK data at the test epoch, used for any drawing tests. */
    private static final int PHOBOS = 401;

    /** A body never added to any trail map. */
    private static final int NEVER_ENABLED = 999;

    /**
     * Create a TrailManager with null JME nodes. Valid for enable/disable tests that never call update() (which is the
     * only method that accesses the nodes or asset manager).
     */
    private TrailManager manager() {
        return new TrailManager(null, null, null, null);
    }

    @Test
    @DisplayName("enableTrail() adds the NAIF ID to the active set")
    void testEnableTrail() {
        TrailManager tm = manager();
        tm.enableTrail(PHOBOS);

        assertTrue(tm.getEnabledIds().contains(PHOBOS), "Enabled trail set should contain PHOBOS after enableTrail");
    }

    @Test
    @DisplayName("disableTrail() removes the NAIF ID from the active set")
    void testDisableTrail() {
        TrailManager tm = manager();
        tm.enableTrail(PHOBOS);
        tm.disableTrail(PHOBOS);

        assertFalse(
                tm.getEnabledIds().contains(PHOBOS), "Enabled trail set should not contain PHOBOS after disableTrail");
        assertTrue(tm.getEnabledIds().isEmpty());
    }

    @Test
    @DisplayName("disableTrail() on a body that was never enabled does not throw")
    void testDisableMissingBody() {
        TrailManager tm = manager();
        assertDoesNotThrow(
                () -> tm.disableTrail(NEVER_ENABLED), "disableTrail on a body that was never enabled must not throw");
        assertTrue(tm.getEnabledIds().isEmpty());
    }
}
