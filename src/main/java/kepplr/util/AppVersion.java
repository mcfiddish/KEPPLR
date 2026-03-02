
package kepplr.util;

public class AppVersion {
    public static final String lastCommit = "2026.02.28";
    // an M at the end of gitRevision means this was built from a "dirty" git repository
    public static final String gitRevision = "eb04408";
    public static final String applicationName = "KEPPLR";
    public static final String dateString = "2026-Mar-01 23:34:00 UTC";

    private AppVersion() {}

    /** KEPPLR version 2026.02.28-eb04408 built 2026-Mar-01 23:34:00 UTC */
    public static String getFullString() {
        return String.format("%s version %s-%s built %s", applicationName, lastCommit, gitRevision, dateString);
    }

    /** KEPPLR version 2026.02.28-eb04408 */
    public static String getVersionString() {
        return String.format("%s version %s-%s", applicationName, lastCommit, gitRevision);
    }
}
