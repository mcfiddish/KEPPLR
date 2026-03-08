package kepplr.stars.catalogs.yaleBSC;

import java.util.Objects;
import kepplr.stars.Star;
import picante.math.coords.CoordConverters;
import picante.math.coords.RaDecVector;
import picante.math.vectorspace.VectorIJK;

/**
 * Yale Bright Star Catalog (BSC5 / VizieR V/50) star.
 *
 * <p>This implementation models a star as a direction on the unit celestial sphere in an inertial/J2000 frame. Proper
 * motion (if present) is applied linearly using the BSC5 pmRA/pmDE fields (arcsec/year), where pmRA is the "projected"
 * value: cos(dec) * d(ra)/dt.
 *
 * <p>ID scheme: {@code BSC5:HR####} (unique across catalogs if you keep the prefix unique).
 */
public final class YaleBrightStar implements Star {

    public static final String CATALOG_PREFIX = "BSC5:HR";

    // --- Required by Star ---
    private final String id;
    private final double vmag;

    // --- J2000.0 reference coordinates ---
    private final double ra0Rad;
    private final double dec0Rad;

    // Proper motions in arcsec/year as provided by BSC5.
    // pmRa is the projected value: cos(dec) * d(ra)/dt.
    private final double pmRaArcsecPerYr;
    private final double pmDecArcsecPerYr;

    // Optional metadata (kept for convenience/debugging)
    private final int hr;
    private final int hd;
    private final String name; // Bayer/Flamsteed (may be blank)
    private final String dm; // Durchmusterung id (may be blank)
    private final String spType; // Spectral type (may be blank)

    YaleBrightStar(
            int hr,
            int hd,
            String name,
            String dm,
            String spType,
            double vmag,
            double ra0Rad,
            double dec0Rad,
            double pmRaArcsecPerYr,
            double pmDecArcsecPerYr) {

        this.hr = hr;
        this.hd = hd;
        this.name = (name == null) ? "" : name;
        this.dm = (dm == null) ? "" : dm;
        this.spType = (spType == null) ? "" : spType;

        this.vmag = vmag;
        this.ra0Rad = ra0Rad;
        this.dec0Rad = dec0Rad;

        this.pmRaArcsecPerYr = pmRaArcsecPerYr;
        this.pmDecArcsecPerYr = pmDecArcsecPerYr;

        this.id = CATALOG_PREFIX + hr;
    }

    // ---------------- Star ----------------

    @Override
    public String getID() {
        return id;
    }

    @Override
    public double getMagnitude() {
        return vmag;
    }

    /**
     * Evaluates the star direction at epoch {@code et} (TDB seconds past J2000).
     *
     * <p>For BSC5, reference epoch is J2000.0. This method applies proper motion linearly:
     *
     * <ul>
     *   <li>dec(t) = dec0 + pmDec * dt
     *   <li>ra(t) = ra0 + (pmRa / cos(dec0)) * dt
     * </ul>
     *
     * where pmRa/pmDec are converted from arcsec/year to rad/year and dt is in Julian years.
     */
    @Override
    public VectorIJK getLocation(double et, VectorIJK buffer) {
        double years = et / YaleBrightStarCatalog.SECONDS_PER_JULIAN_YEAR;

        // Convert to rad/year
        double pmDecRadPerYr = pmDecArcsecPerYr * YaleBrightStarCatalog.ARCSEC_TO_RAD;
        double pmRaRadPerYrProjected = pmRaArcsecPerYr * YaleBrightStarCatalog.ARCSEC_TO_RAD;

        double dec = dec0Rad + pmDecRadPerYr * years;

        // pmRA in the catalog is the projected value cos(dec)*d(ra)/dt.
        double cosDec0 = Math.cos(dec0Rad);
        double dra = (Math.abs(cosDec0) < 1e-12) ? 0.0 : (pmRaRadPerYrProjected / cosDec0) * years;
        double ra = ra0Rad + dra;

        buffer.setTo(CoordConverters.convert(new RaDecVector(1, ra, dec)));
        return buffer;
    }

    // ---------------- Accessors (optional convenience) ----------------

    public int getHr() {
        return hr;
    }

    public int getHd() {
        return hd;
    }

    public String getName() {
        return name;
    }

    public String getDm() {
        return dm;
    }

    public String getSpectralType() {
        return spType;
    }

    public double getRa0Rad() {
        return ra0Rad;
    }

    public double getDec0Rad() {
        return dec0Rad;
    }

    public double getPmRaArcsecPerYr() {
        return pmRaArcsecPerYr;
    }

    public double getPmDecArcsecPerYr() {
        return pmDecArcsecPerYr;
    }

    // ---------------- equality ----------------

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof YaleBrightStar)) return false;
        YaleBrightStar that = (YaleBrightStar) o;
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "YaleBrightStar{" + "id='"
                + id + '\'' + ", vmag="
                + vmag + ", hr="
                + hr + ", hd="
                + hd + ", name='"
                + name + '\'' + '}';
    }
}
