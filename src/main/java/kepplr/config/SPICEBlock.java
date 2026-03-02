package kepplr.config;

import jackfruit.annotations.Comment;
import jackfruit.annotations.DefaultValue;
import jackfruit.annotations.Jackfruit;
import java.util.List;

@Jackfruit(prefix = "spice")
public interface SPICEBlock {
    String introLines = """
###############################################################################
# SPICE PARAMETERS
###############################################################################
""";

    @Comment(introLines + "SPICE metakernel to read.  This may be specified more than once for multiple metakernels.")
    @DefaultValue("resources/spice/kepplr.tm")
    List<String> metakernel();
}
