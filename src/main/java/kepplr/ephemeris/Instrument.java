package kepplr.ephemeris;

import org.immutables.value.Value;
import picante.math.cones.Cone;
import picante.mechanics.EphemerisID;
import picante.mechanics.FrameID;
import picante.spice.fov.FOV;

@Value.Immutable
public interface Instrument extends Comparable<Instrument> {

    /** @return this object's id */
    EphemerisID id();

    /** @return NAIF id code */
    int code();

    /** @return instrument field of view */
    FOV fov();

    /** @return {@link FOV#getCone()} */
    default Cone fovCone() {
        return fov().getCone();
    }

    /** @return {@link FOV#getFrameID()} */
    default FrameID FrameID() {
        return fov().getFrameID();
    }

    /** @return instrument center body */
    EphemerisID center();

    @Override
    default int compareTo(Instrument instrument) {
        return Integer.compare(code(), instrument.code());
    }
}
