package kepplr.render.vector;

import static org.junit.jupiter.api.Assertions.*;

import com.jme3.math.ColorRGBA;
import kepplr.config.KEPPLRConfiguration;
import kepplr.testsupport.TestHarness;
import kepplr.util.KepplrConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link VectorManager}.
 *
 * <p>The manager is created with {@code null} JME nodes; tests that call {@code enableVector} / {@code disableVector}
 * only test set management — no rendering occurs. This matches the pattern established by {@code TrailManagerTest}.
 */
@DisplayName("VectorManager")
class VectorManagerTest {

    private static final int EARTH = 399;
    private static final int NEW_HORIZONS = -98; // no body-fixed frame in test kernels

    /** Create a VectorManager with null JME nodes — valid for enable/disable tests only. */
    private VectorManager manager() {
        return new VectorManager(null, null, null, null);
    }

    /** Convenience factory for a minimal VectorDefinition. */
    private VectorDefinition def(String label, VectorType type, int naifId) {
        return new VectorDefinition(label, type, naifId, ColorRGBA.White, KepplrConstants.VECTOR_DEFAULT_SCALE_KM);
    }

    @BeforeEach
    void setUp() {
        TestHarness.resetSingleton();
        KEPPLRConfiguration.getTestTemplate();
    }

    @Test
    @DisplayName("enableVector() adds the definition to the active set")
    void testEnableVector() {
        VectorManager vm = manager();
        VectorDefinition d = def("earth-vel", VectorTypes.velocity(), EARTH);
        vm.enableVector(d);

        assertTrue(vm.getActiveDefinitions().contains(d), "Active set must contain the definition after enableVector");
        assertEquals(1, vm.getActiveDefinitions().size());
    }

    @Test
    @DisplayName("disableVector() removes the definition from the active set")
    void testDisableVector() {
        VectorManager vm = manager();
        VectorDefinition d = def("earth-vel", VectorTypes.velocity(), EARTH);
        vm.enableVector(d);
        vm.disableVector(d);

        assertFalse(
                vm.getActiveDefinitions().contains(d),
                "Active set must not contain the definition after disableVector");
        assertTrue(vm.getActiveDefinitions().isEmpty());
    }

    @Test
    @DisplayName("disableVector() on a definition that was never enabled is a no-op")
    void testDisableNeverEnabled() {
        VectorManager vm = manager();
        VectorDefinition d = def("earth-vel", VectorTypes.velocity(), EARTH);
        assertDoesNotThrow(() -> vm.disableVector(d), "disableVector on a never-enabled definition must not throw");
        assertTrue(vm.getActiveDefinitions().isEmpty());
    }

    @Test
    @DisplayName("enableVector() with the same instance twice adds it only once")
    void testEnableSameInstanceIdempotent() {
        VectorManager vm = manager();
        VectorDefinition d = def("earth-vel", VectorTypes.velocity(), EARTH);
        vm.enableVector(d);
        vm.enableVector(d);
        assertEquals(1, vm.getActiveDefinitions().size(), "Same instance enabled twice must appear only once");
    }

    @Test
    @DisplayName("two distinct instances with identical fields are treated as separate entries")
    void testDistinctInstancesAreIndependent() {
        VectorManager vm = manager();
        VectorDefinition d1 = def("earth-vel", VectorTypes.velocity(), EARTH);
        VectorDefinition d2 = def("earth-vel", VectorTypes.velocity(), EARTH);
        vm.enableVector(d1);
        vm.enableVector(d2);
        assertEquals(2, vm.getActiveDefinitions().size(), "Two distinct instances must appear as two entries");
    }

    @Test
    @DisplayName("enableVector with bodyAxisX for a body with no orientation data does not throw")
    void testBodyAxisNoOrientationDataDoesNotThrow() {
        // New Horizons has no body-fixed frame; computeDirection returns null gracefully.
        // enableVector must not attempt ephemeris access (no-op here; rendering is where null is handled).
        VectorManager vm = manager();
        VectorDefinition d = def("nh-axis-x", VectorTypes.bodyAxisX(), NEW_HORIZONS);
        assertDoesNotThrow(() -> vm.enableVector(d), "enableVector must not throw for a body with no orientation data");
        assertTrue(
                vm.getActiveDefinitions().contains(d),
                "Definition must be in active set even if orientation is unavailable");
    }

    @Test
    @DisplayName("insertion order of active definitions is preserved")
    void testInsertionOrderPreserved() {
        VectorManager vm = manager();
        VectorDefinition d1 = def("first", VectorTypes.velocity(), EARTH);
        VectorDefinition d2 = def("second", VectorTypes.towardBody(10), EARTH);
        VectorDefinition d3 = def("third", VectorTypes.bodyAxisZ(), EARTH);
        vm.enableVector(d1);
        vm.enableVector(d2);
        vm.enableVector(d3);

        var active = vm.getActiveDefinitions();
        assertSame(d1, active.get(0), "First enabled definition must be first in list");
        assertSame(d2, active.get(1), "Second enabled definition must be second in list");
        assertSame(d3, active.get(2), "Third enabled definition must be third in list");
    }
}
