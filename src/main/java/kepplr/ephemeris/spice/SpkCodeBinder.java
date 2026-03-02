package kepplr.ephemeris.spice;

import com.google.common.base.Charsets;
import com.google.common.io.ByteSource;
import java.io.IOException;
import picante.designpatterns.Blueprint;
import picante.mechanics.EphemerisID;
import picante.spice.SpiceEnvironmentBuilder;
import picante.spice.kernel.KernelInstantiationException;

public class SpkCodeBinder implements Blueprint<SpiceEnvironmentBuilder> {

    private final int spkIdCode;
    private final String spkName;
    private final EphemerisID id;

    public SpkCodeBinder(int spkIdCode, String spkName, EphemerisID id) {
        super();
        this.spkIdCode = spkIdCode;
        this.spkName = spkName;
        this.id = id;
    }

    private static ByteSource sourceString(String name, int code) {

        String linesBuilder =
                "\\begindata \n NAIF_BODY_NAME += ( '" + name + "' ) \n NAIF_BODY_CODE += ( " + code + " ) \n";

        return ByteSource.wrap(linesBuilder.getBytes(Charsets.ISO_8859_1));
    }

    @Override
    public SpiceEnvironmentBuilder configure(SpiceEnvironmentBuilder builder) {

        ByteSource sourceStr = sourceString(spkName, spkIdCode);

        try {
            builder.load(Long.valueOf(System.nanoTime()).toString(), sourceStr);
        } catch (IOException | KernelInstantiationException e) {
            throw new RuntimeException(e);
        }

        builder.bindEphemerisID(spkName, id);

        return builder;
    }
}
