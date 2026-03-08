package kepplr.ephemeris;

import org.immutables.value.Value;
import picante.mechanics.EphemerisID;
import picante.mechanics.FrameID;

@Value.Immutable
public interface Spacecraft extends Comparable<Spacecraft> {

    /** @return this object's id */
    EphemerisID id();

    /** @return NAIF id code */
    int code();

    /** @return spacecraft frame */
    FrameID frameID();

    String shapeModel();

    @Override
    default int compareTo(Spacecraft other) {
        return Integer.compare(code(), other.code());
    }
}
