package kepplr.stars.catalogs.yaleBSC;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Comparator;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import kepplr.stars.Star;
import kepplr.stars.TileSet;
import org.junit.jupiter.api.Test;
import picante.math.coords.CoordConverters;
import picante.math.coords.RaDecVector;
import picante.math.vectorspace.UnwritableVectorIJK;
import picante.math.vectorspace.VectorIJK;

public class YaleBSCTest {

    public static final YaleBrightStarCatalog bsc =
            YaleBrightStarCatalog.loadFromResource("/kepplr/stars/catalogs/yaleBSC/ybsc5.gz");

    @Test
    public void testGetStar() {
        YaleBrightStar sirius = bsc.getStar("BSC5:HR2491");
        assertEquals(
                "YaleBrightStar{id='BSC5:HR2491', vmag=-1.46, hr=2491, hd=48915, name='9Alp CMa'}", sirius.toString());
    }

    @Test
    public void testFilter() {
        NavigableSet<Star> stars = new TreeSet<>(Comparator.comparingDouble(Star::getMagnitude));

        for (Star star : bsc.filter(input -> {
            assert input != null;
            return input.getMagnitude() < 0;
        })) {
            stars.add(star);
        }

        assertEquals(4, stars.size());
        assertTrue(stars.contains(bsc.getStar("BSC5:HR2491"))); // Sirius
        assertTrue(stars.contains(bsc.getStar("BSC5:HR2326"))); // Canopus
        assertTrue(stars.contains(bsc.getStar("BSC5:HR5340"))); // Aldebaran
        assertTrue(stars.contains(bsc.getStar("BSC5:HR5459"))); // Alpha Centauri
    }

    @Test
    public void testLookup() {
        UnwritableVectorIJK lookDir = CoordConverters.convert(new RaDecVector(
                1.0, Math.toRadians(15 * (3. + 47. / 60 + 24. / 3600)), Math.toRadians(24 + 7 / 60. + 0 / 3600.)));
        TileSet<? super Star> tileSet = new TileSet<>();
        bsc.lookup(lookDir, Math.toRadians(1), tileSet);
        NavigableSet<Star> stars = new TreeSet<>(Comparator.comparingDouble(Star::getMagnitude));
        stars.addAll(StreamSupport.stream(tileSet.spliterator(), false).collect(Collectors.toSet()));
        assertEquals(13, stars.size());
        for (Star star : stars) {
            VectorIJK location = star.getLocation(0, new VectorIJK());
            assertTrue(location.getSeparation(lookDir) < Math.toRadians(1));
        }
    }
}
