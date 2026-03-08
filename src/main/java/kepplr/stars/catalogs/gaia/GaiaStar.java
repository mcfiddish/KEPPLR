package kepplr.stars.catalogs.gaia;

import java.util.Objects;
import kepplr.stars.Star;
import picante.math.vectorspace.VectorIJK;

/**
 * Gaia star record, stored as a unit direction vector (J2000/ICRS) plus magnitude.
 *
 * <p>This is the "runtime-minimal" representation intended for fast sky rendering and cone queries:
 *
 * <ul>
 *   <li>{@code sourceId} (Gaia source identifier)
 *   <li>Unit direction vector {@code (x,y,z)} in an inertial frame (ICRS/J2000)
 *   <li>Gaia G-band magnitude {@code phot_g_mean_mag}
 * </ul>
 *
 * <p>No epoch propagation is performed by default. If you later want proper motion/parallax support, extend the tile
 * pack format to store those columns and apply propagation in {@link #getLocation(double, VectorIJK)}.
 *
 * <p>ID scheme: {@code GAIA:SOURCE_ID}.
 */
public final class GaiaStar implements Star {

    public static final String CATALOG_PREFIX = "GAIA:";

    private final long sourceId;
    private final float x;
    private final float y;
    private final float z;
    private final float gMag;

    GaiaStar(long sourceId, float x, float y, float z, float gMag) {
        this.sourceId = sourceId;
        this.x = x;
        this.y = y;
        this.z = z;
        this.gMag = gMag;
    }

    public long getSourceId() {
        return sourceId;
    }

    public float getX() {
        return x;
    }

    public float getY() {
        return y;
    }

    public float getZ() {
        return z;
    }

    // ---------------- Star ----------------

    @Override
    public String getID() {
        return CATALOG_PREFIX + sourceId;
    }

    @Override
    public double getMagnitude() {
        return gMag;
    }

    @Override
    public VectorIJK getLocation(double et, VectorIJK buffer) {
        // This catalog stores fixed directions; et is unused.
        // Adapt if you later add proper motion/parallax.
        buffer.setTo(x, y, z);
        return buffer;
    }

    // ---------------- equality ----------------

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GaiaStar)) return false;
        GaiaStar gaiaStar = (GaiaStar) o;
        return sourceId == gaiaStar.sourceId;
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceId);
    }

    @Override
    public String toString() {
        return "GaiaStar{" + "sourceId=" + sourceId + ", gMag=" + gMag + '}';
    }
}
