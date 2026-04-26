
package kepplr.util;

public class AppVersion {
    public static final String lastCommit = "2026.04.26";
    // an M at the end of gitRevision means this was built from a "dirty" git repository
    public static final String gitRevision = "134bc49M";
    public static final String applicationName = "KEPPLR";
    public static final String dateString = "2026-Apr-26 22:18:34 UTC";

    private AppVersion() {}

    /** KEPPLR version 2026.04.26-134bc49M built 2026-Apr-26 22:18:34 UTC */
    public static String getFullString() {
        return String.format("%s version %s-%s built %s", applicationName, lastCommit, gitRevision, dateString);
    }

    /** KEPPLR version 2026.04.26-134bc49M */
    public static String getVersionString() {
        return String.format("%s version %s-%s", applicationName, lastCommit, gitRevision);
    }

    /** Returns platform description: "os/arch (java version)". */
    public static String getPlatform() {
        String os = System.getProperty("os.name", "unknown");
        String arch = System.getProperty("os.arch", "unknown");
        String javaVersion = System.getProperty("java.version", "unknown");
        return String.format("%s/%s (Java %s)", os, arch, javaVersion);
    }
}
