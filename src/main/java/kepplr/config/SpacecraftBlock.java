package kepplr.config;

import jackfruit.annotations.Comment;
import jackfruit.annotations.DefaultValue;
import jackfruit.annotations.Jackfruit;

// remember to update KEPPLRConfig.getTemplate() when adding anything here

@Jackfruit
public interface SpacecraftBlock {
    String introLines = """
###############################################################################
# SPACECRAFT PARAMETERS
###############################################################################
""";

    @Comment(introLines + "NAIF_BODY_CODE")
    @DefaultValue("-999")
    int naifID();

    @Comment("Display name.  If blank, NAIF_BODY_NAME will be used.")
    @DefaultValue("")
    String name();

    @Comment("Spacecraft frame.  If blank, will be taken from OBJECT_<name or spk_id>_FRAME in kernel pool.")
    @DefaultValue("")
    String frame();

    @Comment("""
Path under resourcesFolder() for this body's shape model.  If blank or missing
body will not be drawn.  Units are assumed to be meters.""")
    @DefaultValue("")
    String shapeModel();

    @Comment("Scale factor for spacecraft shape model.  1.0 keeps the size in meters.")
    @DefaultValue("1.0")
    double scale();
}
