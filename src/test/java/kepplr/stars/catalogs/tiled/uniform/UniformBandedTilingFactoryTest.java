package kepplr.stars.catalogs.tiled.uniform;

import static org.junit.Assert.assertEquals;

import org.junit.jupiter.api.Test;

public class UniformBandedTilingFactoryTest {

    @Test
    public void testCreateSinusoidalTiling() {

        UniformBandedTiling tiling = UniformBandedTilingFactory.createSinusoidalTiling(Math.PI / 9.72);

        /*
         * The declination band structure should Math.ceil the 9.72, rather than round or truncate.
         */
        assertEquals(10, tiling.getNumberOfBands());
        assertEquals(4, tiling.getNumberOfTiles(0));
        assertEquals(9, tiling.getNumberOfTiles(1));
        assertEquals(14, tiling.getNumberOfTiles(2));
        assertEquals(18, tiling.getNumberOfTiles(3));
        assertEquals(20, tiling.getNumberOfTiles(4));
        assertEquals(20, tiling.getNumberOfTiles(5));
        assertEquals(18, tiling.getNumberOfTiles(6));
        assertEquals(14, tiling.getNumberOfTiles(7));
        assertEquals(9, tiling.getNumberOfTiles(8));
        assertEquals(4, tiling.getNumberOfTiles(9));
    }
}
