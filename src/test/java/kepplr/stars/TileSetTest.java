package kepplr.stars;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.google.common.base.Predicate;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picante.math.intervals.UnwritableInterval;
import picante.math.vectorspace.VectorIJK;

public class TileSetTest {

    private TileSet<TestStar> tileset;
    private TileSet<TestStar> filteredTileset;
    private TestTile emptyTile;
    private TestTile unfilteredTile;
    private TestTile filteredTile;
    private TestTile mixedTile;

    @BeforeEach
    public void setUp() throws Exception {

        emptyTile = new TestTile();

        unfilteredTile = new TestTile();
        unfilteredTile.addStar(new TestStar(false));
        unfilteredTile.addStar(new TestStar(false));
        unfilteredTile.addStar(new TestStar(false));
        unfilteredTile.addStar(new TestStar(false));

        filteredTile = new TestTile();
        filteredTile.addStar(new TestStar(true));
        filteredTile.addStar(new TestStar(true));
        filteredTile.addStar(new TestStar(true));

        mixedTile = new TestTile();
        mixedTile.addStar(new TestStar(true));
        mixedTile.addStar(new TestStar(true));
        mixedTile.addStar(new TestStar(false));
        mixedTile.addStar(new TestStar(false));

        tileset = new TileSet<TestStar>();
        tileset.add(emptyTile);
        tileset.add(unfilteredTile);
        tileset.add(filteredTile);
        tileset.add(mixedTile);

        filteredTileset = new TileSet<TestStar>(new TestStarFilter());
        filteredTileset.add(emptyTile);
        filteredTileset.add(unfilteredTile);
        filteredTileset.add(filteredTile);
        filteredTileset.add(mixedTile);
    }

    @Test
    public void testGetTiles() {

        Set<StarTile<? extends TestStar>> set = tileset.getTiles();
        assertTrue(set.contains(emptyTile));
        assertTrue(set.contains(filteredTile));
        assertTrue(set.contains(unfilteredTile));
        assertTrue(set.contains(mixedTile));
        assertEquals(4, set.size());

        set = filteredTileset.getTiles();
        assertTrue(set.contains(emptyTile));
        assertTrue(set.contains(filteredTile));
        assertTrue(set.contains(unfilteredTile));
        assertTrue(set.contains(mixedTile));
        assertEquals(4, set.size());
    }

    @Test
    public void testGetTilesUnmodifiableReturnValue() {
        Assertions.assertThrows(
                UnsupportedOperationException.class, () -> tileset.getTiles().add(emptyTile));
    }

    @Test
    public void testGetTilesFilteredUnmodifiableReturnValue() {
        Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> filteredTileset.getTiles().add(emptyTile));
    }

    @Test
    public void testAdd() {
        TestTile newTile = new TestTile();
        newTile.addStar(new TestStar(true));
        newTile.addStar(new TestStar(false));

        tileset.add(newTile);
        assertEquals(5, tileset.getTiles().size());
        assertTrue(tileset.getTiles().contains(newTile));

        filteredTileset.add(newTile);
        assertEquals(5, filteredTileset.getTiles().size());
        assertTrue(filteredTileset.getTiles().contains(newTile));
    }

    @Test
    public void testClearTiles() {
        tileset.clearTiles();
        assertFalse(tileset.iterator().hasNext());
        assertEquals(0, tileset.getTiles().size());

        filteredTileset.clearTiles();
        assertFalse(filteredTileset.iterator().hasNext());
        assertEquals(0, filteredTileset.getTiles().size());
    }

    @Test
    public void testIterator() {

        /*
         * Collect all the stars and all the stars that would pass the filter into two separate sets.
         */
        Set<TestStar> filterSet = new HashSet<TestStar>();
        Set<TestStar> allSet = new HashSet<TestStar>();

        populateSets(unfilteredTile, filterSet, allSet);
        populateSets(filteredTile, filterSet, allSet);
        populateSets(mixedTile, filterSet, allSet);

        /*
         * Now test each iterator from the tile set. Accumulate each collected star into a set. As stars
         * are added to the set, verify that the length of the set increases as this insures the tileset
         * is not providing any star more than once.
         */
        Set<TestStar> results = new HashSet<TestStar>();
        for (TestStar star : tileset) {
            assertTrue(allSet.contains(star));
            int resultsLength = results.size();
            results.add(star);
            assertTrue(results.size() > resultsLength);
        }

        results.clear();
        for (TestStar star : filteredTileset) {
            assertTrue(filterSet.contains(star));
            int resultsLength = results.size();
            results.add(star);
            assertTrue(results.size() > resultsLength);
        }
    }

    /**
     * Simple convenience method to capture the logic necessary to populate the two sets of tiles, filtered and
     * unfiltered for test purposes.
     *
     * @param tile the tile whose stars are to be added to the appropriate sets
     * @param filterSet the set of stars who have filter set to true
     * @param allSet the set of all stars
     */
    private void populateSets(TestTile tile, Set<TestStar> filterSet, Set<TestStar> allSet) {

        for (TestStar star : tile) {
            if (star.filter) {
                filterSet.add(star);
            }
            allSet.add(star);
        }
    }
}

class TestStarFilter implements Predicate<TestStar> {

    @Override
    public boolean apply(TestStar star) {
        return star.filter;
    }
}

class TestStar implements Star {

    boolean filter;

    public TestStar() {
        filter = false;
    }

    public TestStar(boolean filter) {
        this.filter = filter;
    }

    @Override
    public String getID() {
        throw new UnsupportedOperationException();
    }

    @Override
    public double getMagnitude() {
        throw new UnsupportedOperationException();
    }

    @Override
    public VectorIJK getLocation(@SuppressWarnings("unused") double et, @SuppressWarnings("unused") VectorIJK buffer) {
        throw new UnsupportedOperationException();
    }
}

class TestTile implements StarTile<TestStar> {

    List<TestStar> stars = new LinkedList<TestStar>();

    @Override
    public UnwritableInterval getDeclinationRange() {
        throw new UnsupportedOperationException();
    }

    @Override
    public UnwritableInterval getRightAscensionRange() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Iterator<TestStar> iterator() {
        return stars.iterator();
    }

    public void addStar(TestStar star) {
        stars.add(star);
    }
}
