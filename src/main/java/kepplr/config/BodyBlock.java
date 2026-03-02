package kepplr.config;

import jackfruit.annotations.Comment;
import jackfruit.annotations.DefaultValue;
import jackfruit.annotations.Jackfruit;
import java.awt.*;

// remember to update KEPPLRConfig.getTemplate() when adding anything here

@Jackfruit
public interface BodyBlock {

    @Comment("NAIF ID")
    @DefaultValue("-999")
    int naifID();

    @Comment("Name.  If blank, NAIF_BODY_NAME will be used.")
    @DefaultValue("")
    String name();

    @Comment("Body color as a hexadecimal number.  Ignored if textureMap is specified.")
    @DefaultValue("#FFFFFF")
    String hexColor();

    default Color color() {
        return Color.decode(hexColor());
    }

    @Comment("Scale dayside color by this fraction when drawing night side")
    @DefaultValue("0.01")
    double nightShade();

    @Comment("""
Path under resourcesFolder() for this body's texture map.  These should be in
simple cylindrical projection, with +90 at the top and -90 at the bottom.
If blank or can't be loaded, specified color will be used.""")
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
