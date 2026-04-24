package kepplr.config;

import jackfruit.annotations.Comment;
import jackfruit.annotations.DefaultValue;
import jackfruit.annotations.Jackfruit;
import java.awt.*;
import kepplr.util.KepplrConstants;

// remember to update KEPPLRConfig.getTemplate() when adding anything here

@Jackfruit
public interface BodyBlock {

    /**
     * Infer the NAIF ID of the body's primary from the NAIF integer code.
     *
     * <p>The solar system barycenter is its own root. Planetary system barycenters, including the Sun, are treated as
     * orbiting the solar system barycenter. Other body IDs from 11 through 1000 use integer division by 100, yielding
     * the planetary system barycenter.
     *
     * <p>NAIF IDs &lt; 0 (spacecraft) and &gt; 1000 (comets/asteroids) use the solar system barycenter as the primary.
     *
     * @param naifId NAIF integer body ID
     * @return inferred primary NAIF ID
     */
    static int inferPrimaryId(int naifId) {

        final int SOLAR_SYSTEM_BARYCENTER = 0;
        if (naifId < SOLAR_SYSTEM_BARYCENTER || naifId > 1000) {
            return SOLAR_SYSTEM_BARYCENTER;
        }
        if (naifId <= KepplrConstants.SUN_NAIF_ID) {
            return SOLAR_SYSTEM_BARYCENTER;
        }
        return naifId / 100;
    }

    @Comment("NAIF ID")
    @DefaultValue("-999")
    int naifID();

    @Comment("Name.  If blank, NAIF_BODY_NAME will be used.")
    @DefaultValue("")
    String name();

    @Comment("""
    NAIF ID of this body's primary. If blank, infer primary from the body's NAIF ID.
    """)
    @DefaultValue("")
    String primaryID();

    default int primaryIDasInt() {
        String primary = primaryID();
        if (primary == null || primary.isBlank()) {
            return inferPrimaryId(naifID());
        }
        return Integer.parseInt(primary.trim());
    }

    @Comment("""
    Body color as a hexadecimal number.  Ignored if textureMap is specified but it will be applied to a shape model
    without a built-in color.""")
    @DefaultValue("#FFFFFF")
    String hexColor();

    default Color color() {
        return Color.decode(hexColor());
    }

    @Comment("""
Path under resourcesFolder() for this body's texture map.  These should be in
simple cylindrical projection, with +90 at the top and -90 at the bottom.
If blank or can't be loaded, specified color will be used.  The texture map
is ignored if a shapeModel exists and can be loaded.""")
    @DefaultValue("")
    String textureMap();

    @Comment("Center longitude (east) of texture map in degrees.")
    @DefaultValue("0")
    double centerLonDeg();

    default double centerLon() {
        return Math.toRadians(centerLonDeg());
    }

    @Comment("""
Path under resourcesFolder() for this body's shape model.  If blank an
ellipsoid model will be used""")
    @DefaultValue("")
    String shapeModel();
}
