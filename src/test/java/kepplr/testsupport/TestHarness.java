package kepplr.testsupport;

import java.awt.*;
import java.lang.reflect.Field;
import java.util.*;
import kepplr.config.KEPPLRConfiguration;
import org.apache.logging.log4j.LogManager;

public class TestHarness {

    /** @return 2015 Jul 14 07:59:00 */
    public static double getTestEpoch() {
        // KEPPLRConfiguration.getTemplate() should have been called in the calling unit test
        KEPPLRConfiguration config = KEPPLRConfiguration.getInstance();
        return config.getTimeConversion().utcStringToTDB("2015 Jul 14 07:59:00");
    }

    public static void resetSingleton() {
        /*-
         * Use reflection to reset the singleton before each test.
         * <p>
         * See <a href= "https://stackoverflow.com/questions/8256989/singleton-and-unit-testing">
         * https://stackoverflow.com/questions/8256989/singleton-and-unit-testing</a>
         *
         */
        Field instance;
        try {
            instance = KEPPLRConfiguration.class.getDeclaredField("instance");
            instance.setAccessible(true);
            instance.set(null, null);
        } catch (NoSuchFieldException | SecurityException | IllegalArgumentException | IllegalAccessException e) {
            LogManager.getLogger().error(e.getLocalizedMessage(), e);
        }
    }
}
