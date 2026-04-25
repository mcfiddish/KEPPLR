package kepplr.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Color;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BodyBlock")
class BodyBlockTest {

    @Test
    @DisplayName("primaryIDasInt returns configured primary when present")
    void primaryIDasInt_configuredPrimary() {
        BodyBlock block = block(401, "499");

        assertEquals(499, block.primaryIDasInt());
    }

    @Test
    @DisplayName("primaryIDasInt trims configured primary")
    void primaryIDasInt_trimsConfiguredPrimary() {
        BodyBlock block = block(401, " 4 ");

        assertEquals(4, block.primaryIDasInt());
    }

    @Test
    @DisplayName("primaryIDasInt infers primary when blank")
    void primaryIDasInt_infersBlankPrimary() {
        BodyBlock block = block(401, "");

        assertEquals(4, block.primaryIDasInt());
    }

    @Test
    @DisplayName("inferPrimaryId returns solar system barycenter for root and planetary barycenters")
    void inferPrimaryId_rootAndPlanetaryBarycenters() {
        assertEquals(0, BodyBlock.inferPrimaryId(0));
        assertEquals(0, BodyBlock.inferPrimaryId(1));
        assertEquals(0, BodyBlock.inferPrimaryId(10));
    }

    @Test
    @DisplayName("inferPrimaryId uses integer division for regular body IDs through 1000")
    void inferPrimaryId_regularBodyIds() {
        assertEquals(1, BodyBlock.inferPrimaryId(100));
        assertEquals(3, BodyBlock.inferPrimaryId(301));
        assertEquals(4, BodyBlock.inferPrimaryId(499));
        assertEquals(9, BodyBlock.inferPrimaryId(999));
        assertEquals(10, BodyBlock.inferPrimaryId(1000));
    }

    @Test
    @DisplayName("inferPrimaryId returns solar system barycenter for spacecraft and small-body IDs")
    void inferPrimaryId_outOfPlanetaryRange() {
        assertEquals(0, BodyBlock.inferPrimaryId(-999));
        assertEquals(0, BodyBlock.inferPrimaryId(1001));
        assertEquals(0, BodyBlock.inferPrimaryId(2_000_0433));
    }

    private static BodyBlock block(int naifId, String primaryId) {
        return new BodyBlock() {
            @Override
            public int naifID() {
                return naifId;
            }

            @Override
            public String primaryID() {
                return primaryId;
            }

            @Override
            public String name() {
                return "";
            }

            @Override
            public String hexColor() {
                return "#FFFFFF";
            }

            @Override
            public Color color() {
                return BodyBlock.super.color();
            }

            @Override
            public String textureMap() {
                return "";
            }

            @Override
            public double centerLonDeg() {
                return 0.0;
            }

            @Override
            public double centerLon() {
                return BodyBlock.super.centerLon();
            }

            @Override
            public String shapeModel() {
                return "";
            }
        };
    }
}
