package kepplr.stars.catalogs.gaia;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple lat/lon rectangular tiling for the unit sphere.
 *
 * <p>This is intentionally conservative and easy to implement without external dependencies. It provides:
 *
 * <ul>
 *   <li>Fast mapping of RA/Dec to tile id
 *   <li>Fast, conservative tile covering for a cone (may return extra tiles)
 * </ul>
 *
 * <p>Tile IDs are laid out in row-major order: {@code tileId = latIndex * nLon + lonIndex}, with:
 *
 * <ul>
 *   <li>{@code latIndex in [0, nLat-1]} for declination bands from -pi/2..+pi/2
 *   <li>{@code lonIndex in [0, nLon-1]} for right ascension bins from 0..2pi
 * </ul>
 */
public final class LatLonTiling {

    private static final double TWO_PI = 2.0 * Math.PI;

    public final int nLat;
    public final int nLon;
    public final int tileCount;

    final double latStep; // radians (declination)
    final double lonStep; // radians (right ascension)

    public LatLonTiling(int nLat, int nLon) {
        if (nLat <= 0 || nLon <= 0) throw new IllegalArgumentException("nLat/nLon must be positive");
        this.nLat = nLat;
        this.nLon = nLon;
        this.tileCount = nLat * nLon;
        this.latStep = Math.PI / nLat;
        this.lonStep = TWO_PI / nLon;
    }

    public int tileIdForRaDec(double raRad, double decRad) {
        double ra = normalizeRa(raRad);
        double dec = clamp(decRad, -Math.PI / 2.0, Math.PI / 2.0);

        int latIndex = (int) Math.floor((dec + Math.PI / 2.0) / latStep);
        if (latIndex < 0) latIndex = 0;
        if (latIndex >= nLat) latIndex = nLat - 1;

        int lonIndex = (int) Math.floor(ra / lonStep);
        if (lonIndex < 0) lonIndex = 0;
        if (lonIndex >= nLon) lonIndex = nLon - 1;

        return latIndex * nLon + lonIndex;
    }

    /**
     * Conservative covering for a cone centered at (ra0, dec0) with half-angle r. Returns a list of tile IDs; may
     * include tiles that don't actually intersect.
     */
    public List<Integer> tilesIntersectingCone(double ra0, double dec0, double r) {
        if (r >= Math.PI) {
            // whole sky
            ArrayList<Integer> all = new ArrayList<>(tileCount);
            for (int t = 0; t < tileCount; t++) all.add(t);
            return all;
        }

        double decMin = clamp(dec0 - r, -Math.PI / 2.0, Math.PI / 2.0);
        double decMax = clamp(dec0 + r, -Math.PI / 2.0, Math.PI / 2.0);

        int latMin = (int) Math.floor((decMin + Math.PI / 2.0) / latStep);
        int latMax = (int) Math.floor((decMax + Math.PI / 2.0) / latStep);
        if (latMin < 0) latMin = 0;
        if (latMax >= nLat) latMax = nLat - 1;

        ArrayList<Integer> ids = new ArrayList<>(Math.max(16, (latMax - latMin + 1) * 8));

        double sinDec0 = Math.sin(dec0);
        double cosDec0 = Math.cos(dec0);
        double cosR = Math.cos(r);

        for (int lat = latMin; lat <= latMax; lat++) {
            // band bounds
            double bandDec1 = -Math.PI / 2.0 + lat * latStep;
            double bandDec2 = bandDec1 + latStep;

            // Evaluate required deltaLon at a few declinations to be conservative.
            double[] sampleDec = new double[] {bandDec1, bandDec2, clamp(dec0, bandDec1, bandDec2)};

            double maxDeltaLon = 0.0;
            for (double dec : sampleDec) {
                double sinDec = Math.sin(dec);
                double cosDec = Math.cos(dec);
                double denom = cosDec0 * cosDec;

                // Near poles or if denom is tiny, any longitude could be within r for some point in band.
                if (Math.abs(denom) < 1e-12) {
                    maxDeltaLon = Math.PI;
                    break;
                }

                // From spherical law of cosines:
                // cos(d) = sin(dec0)sin(dec) + cos(dec0)cos(dec)cos(deltaLon)
                // Solve for cos(deltaLon) >= (cos(r) - sin(dec0)sin(dec)) / (cos(dec0)cos(dec))
                double rhs = (cosR - sinDec0 * sinDec) / denom;

                // If rhs <= -1, deltaLon could be pi; if rhs >= 1, deltaLon could be 0.
                rhs = clamp(rhs, -1.0, 1.0);
                double delta = Math.acos(rhs);
                if (delta > maxDeltaLon) maxDeltaLon = delta;
            }

            // Convert lon range to indices
            double lo = normalizeRa(ra0 - maxDeltaLon);
            double hi = normalizeRa(ra0 + maxDeltaLon);

            if (maxDeltaLon >= Math.PI - 1e-12) {
                // all longitudes
                for (int lon = 0; lon < nLon; lon++) {
                    ids.add(lat * nLon + lon);
                }
                continue;
            }

            // Range might wrap around 0.
            if (lo <= hi) {
                int lonMin = (int) Math.floor(lo / lonStep);
                int lonMax = (int) Math.floor(hi / lonStep);
                lonMin = clampInt(lonMin, 0, nLon - 1);
                lonMax = clampInt(lonMax, 0, nLon - 1);

                // Expand by one bin on each side to be conservative with discretization
                lonMin = clampInt(lonMin - 1, 0, nLon - 1);
                lonMax = clampInt(lonMax + 1, 0, nLon - 1);

                for (int lon = lonMin; lon <= lonMax; lon++) {
                    ids.add(lat * nLon + lon);
                }
            } else {
                // Wrapped: [lo..2pi) U [0..hi]
                int lonMin1 = (int) Math.floor(lo / lonStep);
                lonMin1 = clampInt(lonMin1 - 1, 0, nLon - 1);
                for (int lon = lonMin1; lon < nLon; lon++) ids.add(lat * nLon + lon);

                int lonMax2 = (int) Math.floor(hi / lonStep);
                lonMax2 = clampInt(lonMax2 + 1, 0, nLon - 1);
                for (int lon = 0; lon <= lonMax2; lon++) ids.add(lat * nLon + lon);
            }
        }

        return ids;
    }

    static double normalizeRa(double ra) {
        double r = ra % TWO_PI;
        if (r < 0) r += TWO_PI;
        return r;
    }

    static double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }

    static int clampInt(int x, int lo, int hi) {
        return Math.max(lo, Math.min(hi, x));
    }
}
