package kepplr.camera;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link CameraFrame}. */
@DisplayName("CameraFrame")
class CameraFrameTest {

    @Test
    @DisplayName("INERTIAL, BODY_FIXED, and SYNODIC enum values exist")
    void allValuesExist() {
        CameraFrame[] values = CameraFrame.values();
        assertEquals(3, values.length, "CameraFrame should have exactly 3 values");
        assertNotNull(CameraFrame.valueOf("INERTIAL"));
        assertNotNull(CameraFrame.valueOf("BODY_FIXED"));
        assertNotNull(CameraFrame.valueOf("SYNODIC"));
    }

    @Test
    @DisplayName("Enum ordinal order: INERTIAL < BODY_FIXED < SYNODIC")
    void ordinalOrder() {
        assertTrue(CameraFrame.INERTIAL.ordinal() < CameraFrame.BODY_FIXED.ordinal());
        assertTrue(CameraFrame.BODY_FIXED.ordinal() < CameraFrame.SYNODIC.ordinal());
    }
}
