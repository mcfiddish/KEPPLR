#!/bin/bash

# This script is run from maven.  See the exec-maven-plugin block in the pom.xml file.

# these should be consistent with the root-level mkPackage.bash script
cd $(dirname $0)
srcFile="../java/kepplr/util/AppVersion.java"

date=$(date -u +"%Y-%b-%d %H:%M:%S %Z")

rev=$(git rev-parse --verify --short HEAD)
if [ $? -gt 0 ]; then
    lastCommit=$(date -u +"%Y.%m.%d")
    rev="UNVERSIONED"
else
    lastCommit=$(git log -1 --format=%cd --date=format:%Y.%m.%d)
    rev=$(git rev-parse --verify --short HEAD)

    if [[ $(git diff --stat) != '' ]]; then
        if [[ $(git status -s --untracked=no | grep -v pom.xml | grep -v pom.bak | grep -v .m2 | grep -v $srcFile) != '' ]]; then
            rev=${rev}M
        fi
    fi
fi

mkdir -p $(dirname $srcFile)

touch $srcFile

cat <<EOF >$srcFile

package kepplr.util;

public class AppVersion {
    public static final String lastCommit = "$lastCommit";
    // an M at the end of gitRevision means this was built from a "dirty" git repository
    public static final String gitRevision = "$rev";
    public static final String applicationName = "KEPPLR";
    public static final String dateString = "$date";

    private AppVersion() {}

    /** KEPPLR version $lastCommit-$rev built $date */
    public static String getFullString() {
        return String.format("%s version %s-%s built %s", applicationName, lastCommit, gitRevision, dateString);
    }

    /** KEPPLR version $lastCommit-$rev */
    public static String getVersionString() {
        return String.format("%s version %s-%s", applicationName, lastCommit, gitRevision);
    }
}
EOF
