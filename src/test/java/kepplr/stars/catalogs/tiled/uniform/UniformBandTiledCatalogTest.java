package kepplr.stars.catalogs.tiled.uniform;

import static com.google.common.base.Preconditions.checkArgument;
import static org.junit.jupiter.api.Assertions.*;

import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import kepplr.stars.Star;
import kepplr.stars.StarTile;
import kepplr.stars.TileSet;
import kepplr.stars.TiledStarCatalogs;
import kepplr.stars.catalogs.AbstractStarCatalog;
import kepplr.stars.services.LocationCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picante.math.coords.CoordConverters;
import picante.math.coords.LatitudinalVector;
import picante.math.intervals.UnwritableInterval;
import picante.math.vectorspace.UnwritableVectorIJK;
import picante.math.vectorspace.VectorIJK;

public class UniformBandTiledCatalogTest {

    private static final double DECSTEP = Math.PI / 10.0;
    private static final double RASTEP = Math.PI / 7.0;
    private static final double TILESIZE = Math.PI / 4.0;

    private static final LocationCalculator POS_CALC = new LocationCalculator() {

        @Override
        public boolean hasLocation(Star s) {
            return s instanceof TestLocatableStar;
        }

        @Override
        public VectorIJK evaluateLocation(Star s, double et, VectorIJK buffer) {
            checkArgument(s instanceof TestLocatableStar);
            return ((TestLocatableStar) s).getLocation(et, buffer);
        }
    };

    private List<TestLocatableStar> stars;
    private TestCatalog catalog;
    private UniformBandTiledCatalog<TestLocatableStar> memCatNotFiltered;
    private UniformBandTiledCatalog<TestLocatableStar> memCatFiltered;

    private UniformBandedTiling tiling;

    @BeforeEach
    public void setUp() throws Exception {

        tiling = UniformBandedTilingFactory.createSinusoidalTiling(TILESIZE);

        stars = Lists.newArrayListWithCapacity(120);

        int counter = 0;
        for (double dec = Math.PI / 2.0 - DECSTEP; dec > -Math.PI / 2.0 + DECSTEP; dec -= DECSTEP) {
            for (double ra = 0; ra < 2.0 * Math.PI; ra += RASTEP) {
                stars.add(new TestLocatableStar(ra, dec, (counter++ % 3 == 0)));
            }
        }

        catalog = new TestCatalog(stars);

        memCatNotFiltered = TiledStarCatalogs.createMemoryTiledCatalog(catalog, POS_CALC, TILESIZE, 0.0);
        memCatFiltered = TiledStarCatalogs.createMemoryTiledCatalog(
                catalog, new TestLocatableStarFilter(), POS_CALC, TILESIZE, 0.0);
    }

    private static StarTile<TestLocatableStar> createBogusTile() {
        return new StarTile<TestLocatableStar>() {

            @Override
            public UnwritableInterval getDeclinationRange() {
                throw new UnsupportedOperationException();
            }

            @Override
            public UnwritableInterval getRightAscensionRange() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Iterator<TestLocatableStar> iterator() {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static void testCatalogLookup(
            List<TestLocatableStar> stars,
            UniformBandTiledCatalog<TestLocatableStar> catalog,
            UnwritableVectorIJK coneCenter,
            double halfAngle,
            boolean filterEnabled) {

        TileSet<TestLocatableStar> tileset = new TileSet<TestLocatableStar>();
        StarTile<TestLocatableStar> tile = createBogusTile();

        /*
         * Check that the catalog dumps any tiles present in the tileset when a query is performed.
         */
        tileset.add(tile);
        catalog.lookup(coneCenter, halfAngle, tileset);
        assertFalse(tileset.getTiles().contains(tile));

        /*
         * Now iterate over each star in the catalog, building a set of stars that must be in the
         * resultant list.
         */
        Set<TestLocatableStar> expected = Sets.newHashSet();
        LocationPlusFilter filter = new LocationPlusFilter(coneCenter, halfAngle, filterEnabled);
        for (TestLocatableStar star : stars) {
            if (filter.apply(star)) {
                expected.add(star);
            }
        }

        /*
         * Just to be sane, check that there are some stars in expected.
         */
        assertTrue(expected.size() > 0);

        /*
         * Verify that the resultant tileset contains every star present in expected.
         */
        for (TestLocatableStar star : tileset) {
            expected.remove(star);
        }

        assertEquals(0, expected.size());
    }

    @Test
    public void testLookupMemCatNoFilter() {
        testCatalogLookup(stars, memCatNotFiltered, VectorIJK.I, Math.PI / 3.0, false);
    }

    @Test
    public void testLookupMemCatFilter() {
        testCatalogLookup(stars, memCatNotFiltered, VectorIJK.I, Math.PI / 3.0, true);
    }

    @Test
    public void testAdd() {

        TileSet<TestLocatableStar> tileset = new TileSet<TestLocatableStar>();
        memCatNotFiltered.add(0, 0, tileset);
        Set<StarTile<? extends TestLocatableStar>> set = tileset.getTiles();
        assertEquals(1, set.size());

        /*
         * Check that the declination and right ascension ranges for the tile that was added are
         * consistent with the expectations.
         */
        for (StarTile<? extends TestLocatableStar> tile : set) {
            assertEquals(tiling.getDeclinationRange(0), tile.getDeclinationRange());
            assertEquals(tiling.getRightAscensionRange(0, 0), tile.getRightAscensionRange());
        }
    }

    private static Set<TestLocatableStar> testTileGeometry(
            UniformBandedTiling tiling, UniformBandTiledCatalog<TestLocatableStar> catalog) {
        Set<TestLocatableStar> visited = Sets.newHashSet();
        TileSet<TestLocatableStar> tileset = new TileSet<TestLocatableStar>();

        for (int b = 0; b < tiling.getNumberOfBands(); b++) {
            for (int t = 0; t < tiling.getNumberOfTiles(b); t++) {
                UnwritableInterval raRange = tiling.getRightAscensionRange(b, t);
                UnwritableInterval decRange = tiling.getDeclinationRange(b);
                LatitudinalVector coord = new LatitudinalVector(1.0, decRange.getMiddle(), raRange.getMiddle());
                UnwritableVectorIJK vector = CoordConverters.convert(coord);

                catalog.lookup(vector, 1e6, tileset);

                boolean foundTile = false;
                for (StarTile<? extends TestLocatableStar> tile : tileset.getTiles()) {

                    int numVisited = visited.size();
                    for (TestLocatableStar star : tile) {
                        visited.add(star);
                        assertTrue(visited.size() > numVisited);
                    }

                    if (tile.getDeclinationRange().equals(decRange)
                            && tile.getRightAscensionRange().equals(raRange)) {
                        foundTile = true;
                    }
                }
                assertTrue(foundTile, "Tile at " + b + "," + t);
            }
        }
        return visited;
    }

    @Test
    public void testCreateMemoryTiledCatalogStarCatalogOfSDoubleDouble() {
        Set<TestLocatableStar> visited = testTileGeometry(tiling, memCatNotFiltered);

        assertTrue(visited.containsAll(stars));
    }

    @Test
    public void testCreateMemoryTiledCatalogStarCatalogOfSStarFilterOfQsuperSDoubleDouble() {
        Set<TestLocatableStar> visited = testTileGeometry(tiling, memCatFiltered);

        List<TestLocatableStar> filteredStars = Lists.newArrayListWithCapacity(stars.size());
        for (TestLocatableStar star : stars) {
            if (star.filter) {
                filteredStars.add(star);
            }
        }

        assertTrue(visited.containsAll(filteredStars));
    }
}

class LocationPlusFilter implements Predicate<TestLocatableStar> {

    private final UnwritableVectorIJK coneCenter;
    private final double halfAngle;
    private final boolean enableFiltering;
    private final VectorIJK buffer = new VectorIJK();

    LocationPlusFilter(UnwritableVectorIJK coneCenter, double halfAngle, boolean enableFiltering) {
        super();
        this.coneCenter = coneCenter;
        this.halfAngle = halfAngle;
        this.enableFiltering = enableFiltering;
    }

    @Override
    public boolean apply(TestLocatableStar star) {

        if (enableFiltering) {
            if (!star.filter) {
                return false;
            }
        }

        return coneCenter.getSeparation(star.getLocation(0.0, buffer)) <= halfAngle;
    }
}

class TestLocatableStarFilter implements Predicate<TestLocatableStar> {

    @Override
    public boolean apply(TestLocatableStar star) {
        return star.filter;
    }
}

class TestLocatableStar implements Star {

    private final UnwritableVectorIJK vector;
    final boolean filter;

    public TestLocatableStar(double ra, double dec, boolean filter) {
        this.filter = filter;
        this.vector = CoordConverters.convert(new LatitudinalVector(1.0, dec, ra));
    }

    @Override
    public String getID() {
        throw new UnsupportedOperationException();
    }

    @Override
    public VectorIJK getLocation(@SuppressWarnings("unused") double t, VectorIJK buffer) {
        return buffer.setTo(vector);
    }

    @Override
    public double getMagnitude() {
        throw new UnsupportedOperationException();
    }
}

class TestCatalog extends AbstractStarCatalog<TestLocatableStar> {

    private final List<TestLocatableStar> stars;

    TestCatalog(List<TestLocatableStar> stars) {
        super();
        this.stars = stars;
    }

    @Override
    public TestLocatableStar getStar(@SuppressWarnings("unused") String ID) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Iterable<TestLocatableStar> filter(Predicate<? super TestLocatableStar> filter) {
        return Iterables.filter(stars, filter);
    }
}
