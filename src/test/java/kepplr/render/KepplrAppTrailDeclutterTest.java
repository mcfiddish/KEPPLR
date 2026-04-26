package kepplr.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import kepplr.config.KEPPLRConfiguration;
import kepplr.testsupport.TestHarness;
import org.apache.commons.configuration2.PropertiesConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("KepplrApp trail decluttering")
class KepplrAppTrailDeclutterTest {

    @BeforeEach
    void setup() {
        TestHarness.resetSingleton();
        KEPPLRConfiguration.getTestTemplate();
    }

    @Test
    @DisplayName("configured BodyBlock.primaryID is the decluttering primary")
    void configuredPrimaryIdIsDeclutteringPrimary() {
        PropertiesConfiguration pc = KEPPLRConfiguration.getInstance().toPropertiesConfiguration();
        pc.setProperty("body.didymos.naifID", "920065802");
        pc.setProperty("body.didymos.name", "DIDYMOS");
        pc.setProperty("body.didymos.primaryID", "");
        pc.setProperty("body.didymos.hexColor", "#FFFFFF");
        pc.setProperty("body.didymos.textureMap", "");
        pc.setProperty("body.didymos.centerLonDeg", "0");
        pc.setProperty("body.didymos.shapeModel", "");
        pc.setProperty("body.dimorphos.naifID", "120065803");
        pc.setProperty("body.dimorphos.name", "DIMORPHOS");
        pc.setProperty("body.dimorphos.primaryID", "920065802");
        pc.setProperty("body.dimorphos.hexColor", "#FFFFFF");
        pc.setProperty("body.dimorphos.textureMap", "");
        pc.setProperty("body.dimorphos.centerLonDeg", "0");
        pc.setProperty("body.dimorphos.shapeModel", "");
        KEPPLRConfiguration.reload(pc);

        assertEquals(920065802, KepplrApp.resolveTrailDeclutterPrimaryId(120065803));
    }

    @Test
    @DisplayName("natural satellites keep legacy planet-primary decluttering fallback")
    void naturalSatelliteFallback() {
        assertEquals(399, KepplrApp.resolveTrailDeclutterPrimaryId(301));
        assertEquals(499, KepplrApp.resolveTrailDeclutterPrimaryId(401));
    }

    @Test
    @DisplayName("self-primary and non-satellite bodies do not declutter")
    void noDeclutteringPrimary() {
        assertEquals(-1, KepplrApp.resolveTrailDeclutterPrimaryId(999));
        assertEquals(-1, KepplrApp.resolveTrailDeclutterPrimaryId(2_000_001));
    }
}
