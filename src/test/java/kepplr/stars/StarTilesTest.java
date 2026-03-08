package kepplr.stars;

import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import java.util.Set;
import javax.annotation.Nullable;
import org.easymock.EasyMock;
import org.junit.jupiter.api.Test;
import picante.math.intervals.Interval;
import picante.math.intervals.UnwritableInterval;
import picante.math.vectorspace.VectorIJK;

public class StarTilesTest {

    private static final Predicate<TestTileStar> oddFilter = new Predicate<TestTileStar>() {

        @Override
        public boolean apply(@Nullable TestTileStar input) {
            if (input != null) {
                return input.getIDAsInt() % 2 == 1;
            }
            return false;
        }
    };

    @Test
    public void testCreateCachedTile() {

        Interval raRange = new Interval(0.2, 0.4);
        Interval decRange = new Interval(1.1, 1.4);
        Set<TestTileStar> stars = Sets.newHashSet(new TestTileStar(), new TestTileStar(), new TestTileStar());

        StarTile<TestTileStar> tile = StarTiles.createCachedTile(raRange, decRange, stars);

        assertEquals(raRange, tile.getRightAscensionRange());
        assertNotSame(raRange, tile.getRightAscensionRange());
        UnwritableInterval testRange = new UnwritableInterval(raRange);
        raRange.set(0.0, 0.1);
        assertFalse(raRange.equals(tile.getRightAscensionRange()));
        assertEquals(testRange, tile.getRightAscensionRange());

        assertEquals(decRange, tile.getDeclinationRange());
        assertNotSame(decRange, tile.getDeclinationRange());
        testRange = new UnwritableInterval(decRange);
        decRange.set(0.0, 0.1);
        assertTrue(!decRange.equals(tile.getDeclinationRange()));
        assertEquals(testRange, tile.getDeclinationRange());

        Set<TestTileStar> tileStars = Sets.newHashSet(tile);
        assertEquals(tileStars, stars);

        stars.add(new TestTileStar());

        tileStars = Sets.newHashSet(tile);
        assertFalse(tileStars.equals(stars));
    }

    @Test
    public void testFilter() {

        Set<TestTileStar> stars = ImmutableSet.of(
                new TestTileStar(), new TestTileStar(), new TestTileStar(), new TestTileStar(), new TestTileStar());

        UnwritableInterval raRange = new UnwritableInterval(0.0, 0.5);
        UnwritableInterval decRange = new UnwritableInterval(0.5, 1.0);

        @SuppressWarnings("unchecked")
        StarTile<TestTileStar> tile = EasyMock.createMock(StarTile.class);
        expect(tile.iterator()).andReturn(stars.iterator()).once();
        expect(tile.getRightAscensionRange()).andReturn(raRange).anyTimes();
        expect(tile.getDeclinationRange()).andReturn(decRange).anyTimes();
        replay(tile);

        StarTile<TestTileStar> filtered = StarTiles.filter(tile, oddFilter);

        assertSame(raRange, filtered.getRightAscensionRange());
        assertSame(decRange, filtered.getDeclinationRange());

        Set<TestTileStar> set = Sets.newHashSet(filtered);

        Set<TestTileStar> expected = Sets.filter(stars, oddFilter);

        assertEquals(set, expected);
    }
}

class TestTileStar implements Star {

    static int COUNTER = 0;

    private int starID;

    public TestTileStar() {
        this.starID = COUNTER++;
    }

    public int getIDAsInt() {
        return starID;
    }

    @Override
    public String getID() {
        return "TEST" + starID;
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#hashCode()
     */
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + starID;
        return result;
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#equals(java.lang.Object)
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (!(obj instanceof TestTileStar)) {
            return false;
        }
        TestTileStar other = (TestTileStar) obj;
        if (starID != other.starID) {
            return false;
        }
        return true;
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
