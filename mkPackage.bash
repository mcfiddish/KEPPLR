#!/bin/bash

# Configure these for the package
packageName=KEPPLR
appPackage="kepplr"
scriptPath=$(
    cd "$(dirname $0)"
    pwd -P
)
srcPath="${scriptPath}/src/main/java"
srcFile="${srcPath}/${appPackage}/util/AppVersion.java"
appSrcDir="${appPackage}/apps"

function build_jar() {
    rev=$1

    cwd=$(pwd)
    cd "${scriptPath}"

    # store the version number in pom.xml
    cp -p pom.xml pom.bak
    sed "s,<version>0.0.1-SNAPSHOT</version>,<version>$rev</version>,g" pom.bak >pom.xml

    # install dependencies to the local maven repository
    if [ -d dependency ]; then
        cd dependency

        for pom in *.pom; do
            base=$(basename "$pom" .pom)
            jar="${base}.jar"
            if [ -e "$jar" ]; then
                mvn -q install:install-file -Dfile="$jar" -DpomFile="$pom"
            else
                mvn -q install:install-file -Dfile="$pom" -DpomFile="$pom"
            fi

            echo "Installed ${base} in local repository"
        done

        # Something weird going on with JAI; copy the jar and pom manually
        cp -p jai-codec-1.1.4.* ${HOME}/.m2/repository/com/oracle/jai-codec/1.1.4/
        cp -p jai-imageio-1.1.4.* ${HOME}/.m2/repository/com/oracle/jai-imageio/1.1.4/
        cp -p jai-1.1.4.* ${HOME}/.m2/repository/com/oracle/jai/1.1.4/

        cd ..
    fi

    mvn clean install

    # restore the old pom file
    mv pom.bak pom.xml

    # install the maven products
    rsync -a ${scriptPath}/target/${packageName}.jar ${libDir}
    rsync -a ${scriptPath}/target/${packageName}_lib ${libDir}

    cd "$cwd"
}

function make_scripts() {
    cwd=$(pwd)

    classes=$(jar tf ${scriptPath}/target/${packageName}.jar | grep $appSrcDir | grep -v '\$' | grep -v "package-info" | grep class)

    for class in $classes; do
        base=$(basename $class ".class")

        if [[ $base == *Builder ]]; then
            continue
        fi

        tool=${scriptDir}/${base}
        path=$(dirname $class | sed 's,/,.,g').${base}

        echo "#!/bin/bash" >${tool}
        echo 'set -euo pipefail' >>${tool}
        echo 'script_dir=$(cd "$(dirname "$0")" && pwd -P)' >>${tool}
        echo 'root=$(dirname "$script_dir")' >>${tool}

        echo 'JVM_OPTS=""' >>${tool}
        echo 'FX_OPTS=""' >>${tool}
        echo 'MEMSIZE=""' >>${tool}

        echo 'os="$(uname -s)"' >>${tool}
        echo 'arch="$(uname -m)"' >>${tool}

        echo 'native_os=""' >>${tool}
        echo 'native_arch=""' >>${tool}

        echo 'if [ "$os" = "Darwin" ]; then' >>${tool}
        echo '    MEMSIZE=$(sysctl -n hw.memsize | awk '\''{print int($1/1024)}'\'')' >>${tool}
        echo '    JVM_OPTS="-XstartOnFirstThread"' >>${tool}
        echo '    native_os="macos"' >>${tool}
        echo '    if [ "$arch" = "arm64" ]; then native_arch="arm64"; else native_arch=""; fi' >>${tool}
        echo 'elif [ "$os" = "Linux" ]; then' >>${tool}
        echo '    MEMSIZE=$(awk '\''/MemTotal/ {print $2}'\'' /proc/meminfo)' >>${tool}
        echo '    export GDK_BACKEND=x11' >>${tool}
        echo '    export XDG_SESSION_TYPE=x11' >>${tool}
        echo '    export _JAVA_AWT_WM_NONREPARENTING=1' >>${tool}
        echo '    native_os="linux"' >>${tool}
        echo '    case "$arch" in' >>${tool}
        echo '      x86_64) native_arch="";;' >>${tool}
        echo '      aarch64|arm64) native_arch="arm64";;' >>${tool}
        echo '      armv7l|armv6l) native_arch="arm32";;' >>${tool}
        echo '      *) native_arch="";;' >>${tool}
        echo '    esac' >>${tool}
        echo 'else' >>${tool}
        echo '    echo "Unsupported OS: $os"' >>${tool}
        echo '    exit 1' >>${tool}
        echo 'fi' >>${tool}

        echo "LIB_DIR=\"\${root}/lib/${packageName}_lib\"" >>${tool}
        echo 'if ls "${LIB_DIR}"/javafx/javafx-*.jar >/dev/null 2>&1; then' >>${tool}
        echo '    FX_OPTS="--module-path ${LIB_DIR}/javafx --add-modules javafx.controls,javafx.graphics,javafx.base"' >>${tool}
        echo 'fi' >>${tool}

        echo 'java=$(command -v java || true)' >>${tool}
        echo 'if [ -z "$java" ]; then' >>${tool}
        echo '    echo "Java executable not found in your PATH"' >>${tool}
        echo '    exit 1' >>${tool}
        echo 'fi' >>${tool}

        echo 'fullVersion=$($java -version 2>&1 | head -1 | awk -F\" '\''{print $2}'\'')' >>${tool}
        echo 'version=$(echo "$fullVersion" | awk -F\. '\''{print $1}'\'')' >>${tool}
        echo 'if [ "$version" -lt "'$REQUIRED_JAVA_VERSION'" ]; then' >>${tool}
        echo '    echo "minimum Java version required is '$REQUIRED_JAVA_VERSION'. Version found is $fullVersion."' >>${tool}
        echo '    exit 1' >>${tool}
        echo 'fi' >>${tool}

        # Build classpath:
        # - Always include root/lib/* (your app + third-party jars)
        # - Include non-native jars from ${packageName}_lib
        # - Include only the matching native jars for this OS/arch
        echo 'CP="${root}/lib/*"' >>${tool}

        echo 'if ls "${LIB_DIR}"/*.jar >/dev/null 2>&1; then' >>${tool}
        echo '    for j in "${LIB_DIR}"/*.jar; do' >>${tool}
        echo '        case "$j" in' >>${tool}
        echo '            *-natives-*) ;;' >>${tool}
        echo '            *) CP="${CP}:$j" ;;' >>${tool}
        echo '        esac' >>${tool}
        echo '    done' >>${tool}
        echo 'fi' >>${tool}

        echo 'if [ -n "$native_os" ]; then' >>${tool}
        echo '    if [ -z "$native_arch" ]; then' >>${tool}
        echo '        for j in "${LIB_DIR}"/*-natives-${native_os}.jar; do [ -e "$j" ] && CP="${CP}:$j"; done' >>${tool}
        echo '    else' >>${tool}
        echo '        for j in "${LIB_DIR}"/*-natives-${native_os}-${native_arch}.jar; do [ -e "$j" ] && CP="${CP}:$j"; done' >>${tool}
        echo '    fi' >>${tool}
        echo 'fi' >>${tool}

        echo 'exec "$java" ${JVM_OPTS} ${FX_OPTS} -Xmx${MEMSIZE}K -cp "$CP" '"$path"' "$@"' >>${tool}

        chmod +x ${tool}

    done

    rsync -a ${scriptPath}/src/main/python/ ${pythonDir}

    cd "$cwd"
}

function make_doc() {
    cwd=$(pwd)

    # build javadoc
    javadocPath=${scriptPath}/doc/_static/javadoc
    javadoc -quiet -Xdoclint:none --module-path "${libDir}/${packageName}_lib/javafx" --add-modules javafx.base,javafx.graphics,javafx.controls -cp ${libDir}/*:${libDir}/${packageName}_lib/* -d ${javadocPath} -sourcepath ${srcPath} -subpackages ${appPackage} -overview ${docDir}/src/overview.html
    /bin/rm -fr "${docDir}"/src

    # sphinx
    cd ${scriptPath}/doc

    python3 -m venv ${scriptPath}/venv
    source ${scriptPath}/venv/bin/activate
#    python3 -m pip --default-timeout=1000 install -r ${scriptPath}/src/main/python/requirements.txt
    site_package_path=$(python3 -c 'import sysconfig; print(sysconfig.get_paths()["purelib"])')
    if [ -z "$PYTHONPATH" ]; then
        export PYTHONPATH=$site_package_path
    else
        export PYTHONPATH=$site_package_path:$PYTHONPATH
    fi

    python3 -m pip --default-timeout=1000 install wheel
    python3 -m pip --default-timeout=1000 install -U sphinx
    python3 -m pip --default-timeout=1000 install sphinx-theme-pd
    python3 -m pip --default-timeout=1000 install Pillow # Python Imaging Library

    ./make_doc.bash ${scriptDir}
    sphinx-build -b html . _build
    rsync -a _build/ ${docDir}
    /bin/rm -fr _build tools toolDescriptions ${javadocPath}

    cd "$cwd"
}

# update maven-compiler-plugin block in pom if this version changes
REQUIRED_JAVA_VERSION=21

java=$(which java)
if [ -z $java ]; then
    echo "Java executable not found in your PATH"
    exit 0
fi
fullVersion=$(java -version 2>&1 | head -1 | awk -F\" '{print $2}')
version=$(echo $fullVersion | awk -F\. '{print $1}')
if [ "${version}" -lt "$REQUIRED_JAVA_VERSION" ]; then
    echo "minimum Java version required is $REQUIRED_JAVA_VERSION.  Version found is $fullVersion."
    exit 0
fi

if [ -d .git ]; then

    date=$(git log -1 --format=%cd --date=format:%Y.%m.%d)
    rev=$(git rev-parse --verify --short HEAD)

    if [[ $(git diff --stat) != '' ]]; then
        echo 'WARNING: the following files have not been checked in:'
        git status --short
        echo "waiting for 5 seconds ..."
        sleep 5
        rev=${rev}M
    fi

else
    date=$(date -u +"%Y.%m.%d")
    rev="UNVERSIONED"
fi

pkgBase=${packageName}-${date}

scriptDir=${pkgBase}/scripts
scriptDir=$(
    mkdir -p "${scriptDir}"
    cd "${scriptDir}"
    pwd -P
)
libDir=${pkgBase}/lib
libDir=$(
    mkdir -p "${libDir}"
    cd "${libDir}"
    pwd -P
)
docDir=${pkgBase}/doc
docDir=$(
    mkdir -p "${docDir}"
    cd "${docDir}"
    pwd -P
)
pythonDir=${pkgBase}/python
pythonDir=$(
    mkdir -p "${pythonDir}"
    cd "${pythonDir}"
    pwd -P
)

# Build the jar file
build_jar ${rev}

# create the executable scripts
make_scripts

# create documentation
make_doc

mkdir -p dist
rsync -a README.md CHANGELOG.md ${pkgBase}/
tar cfz ./dist/${pkgBase}-${rev}.tar.gz ./${pkgBase}

mvn -q -Dmdep.copyPom=true dependency:copy-dependencies
rsync -a README.md CHANGELOG.md mkPackage.bash pom.xml doc src target/dependency ${pkgBase}-src/
tar cfz ./dist/${pkgBase}-${rev}-src.tar.gz ./${pkgBase}-src

echo -e "\nCreated ./dist/${pkgBase}-${rev}.tar.gz ./dist/${pkgBase}-${rev}-src.tar.gz"

/bin/rm -fr ./${pkgBase} ./${pkgBase}-src

if [ -d .git ]; then
    git restore $srcFile doc/conf.py
fi
