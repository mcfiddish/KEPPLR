package kepplr.render;

import static org.junit.jupiter.api.Assertions.*;

import com.google.common.base.Predicate;
import java.util.Collections;
import java.util.Iterator;
import kepplr.stars.Star;
import kepplr.stars.StarCatalog;
import kepplr.stars.StarCatalogLookupException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picante.math.vectorspace.VectorIJK;

/**
 * Unit tests for {@link StarFieldManager}.
 *
 * <p>The manager is created with {@code null} JME nodes; methods that do not invoke rendering work correctly. Tests
 * that call {@link StarFieldManager#update} with a non-null catalog will exercise rendering only if the nodes are
 * non-null, so update tests use a null catalog or an empty catalog to avoid touching the scene graph.
 */
@DisplayName("StarFieldManager")
class StarFieldManagerTest {

    /** Create a StarFieldManager with null JME nodes — valid for non-rendering tests. */
    private StarFieldManager manager() {
        return new StarFieldManager(null, null);
    }

    // ── Minimal Star implementation ────────────────────────────────────────────────────────

    /** A star stub with a configurable visual magnitude. */
    private static Star stubStar(String id, double vmag, double x, double y, double z) {
        return new Star() {
            @Override public String getID() { return id; }
            @Override public double getMagnitude() { return vmag; }
            @Override public VectorIJK getLocation(double et, VectorIJK buffer) {
                buffer.setI(x); buffer.setJ(y); buffer.setK(z);
                return buffer;
            }
            @Override public boolean equals(Object o) { return this == o; }
            @Override public int hashCode() { return System.identityHashCode(this); }
        };
    }

    // ── Minimal StarCatalog implementation ───────────────────────────────────────────────

    private static StarCatalog<Star> catalogOf(Iterable<Star> stars) {
        return new StarCatalog<Star>() {
            @Override public Iterator<Star> iterator() { return stars.iterator(); }
            @Override public Iterable<Star> filter(Predicate<? super Star> filter) {
                java.util.List<Star> result = new java.util.ArrayList<>();
                for (Star s : stars) { if (filter.apply(s)) result.add(s); }
                return result;
            }
            @Override public Star getStar(String id) { throw new StarCatalogLookupException("not implemented"); }
        };
    }

    private static StarCatalog<Star> emptyCatalog() {
        return catalogOf(Collections.emptyList());
    }

    // ── Tests ─────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("default catalog is null")
    void testDefaultCatalogIsNull() {
        StarFieldManager mgr = manager();
        // Internal field is null — calling update() should be a no-op (no NPE)
        assertDoesNotThrow(() -> mgr.update(0, new double[3]));
    }

    @Test
    @DisplayName("setCatalog() with a mock catalog does not throw")
    void testSetCatalogDoesNotThrow() {
        StarFieldManager mgr = manager();
        assertDoesNotThrow(() -> mgr.setCatalog(emptyCatalog()));
    }

    @Test
    @DisplayName("setMagnitudeCutoff() does not throw")
    void testSetMagnitudeCutoffDoesNotThrow() {
        StarFieldManager mgr = manager();
        assertDoesNotThrow(() -> mgr.setMagnitudeCutoff(5.0));
    }

    @Test
    @DisplayName("update() with null catalog does not throw")
    void testUpdateWithNullCatalogDoesNotThrow() {
        StarFieldManager mgr = manager();
        // catalog is null by default
        assertDoesNotThrow(() -> mgr.update(0.0, new double[3]));
    }

    @Test
    @DisplayName("update() with empty catalog does not throw")
    void testUpdateWithEmptyCatalogDoesNotThrow() {
        StarFieldManager mgr = manager();
        mgr.setCatalog(emptyCatalog());
        assertDoesNotThrow(() -> mgr.update(0.0, new double[3]));
    }

    @Test
    @DisplayName("NaN-magnitude star is excluded — update does not throw")
    void testNaNMagnitudeStarIsExcluded() {
        Star nanStar = stubStar("BSC5:NaN", Double.NaN, 1, 0, 0);
        StarCatalog<Star> catalog = catalogOf(Collections.singletonList(nanStar));

        StarFieldManager mgr = manager();
        mgr.setCatalog(catalog);
        // With a null farNode, renderer.update() would NPE if it tried to attach anything.
        // Confirming no exception means the NaN star was properly skipped before any attach.
        assertDoesNotThrow(() -> mgr.update(0.0, new double[3]));
    }

    @Test
    @DisplayName("setCatalog(null) after setting a catalog clears it — update is a no-op")
    void testSetCatalogNullClearsField() {
        StarFieldManager mgr = manager();
        mgr.setCatalog(emptyCatalog());
        mgr.setCatalog(null);
        // After clearing, update should be a no-op (no NPE)
        assertDoesNotThrow(() -> mgr.update(0.0, new double[3]));
    }
}
