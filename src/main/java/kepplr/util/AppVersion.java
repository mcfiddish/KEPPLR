
package kepplr.util;

public class AppVersion {
    public static final String lastCommit = "2026.04.26";
    // an M at the end of gitRevision means this was built from a "dirty" git repository
    public static final String gitRevision = "a3083d9M";
    public static final String applicationName = "KEPPLR";
    public static final String dateString = "2026-Apr-26 21:39:57 UTC";

    private AppVersion() {}

    /** KEPPLR version 2026.04.26-a3083d9M built 2026-Apr-26 21:39:57 UTC */
    public static String getFullString() {
        return String.format("%s version %s-%s built %s", applicationName, lastCommit, gitRevision, dateString);
    }

    /** KEPPLR version 2026.04.26-a3083d9M */
    public static String getVersionString() {
        return String.format("%s version %s-%s", applicationName, lastCommit, gitRevision);
    }
}
