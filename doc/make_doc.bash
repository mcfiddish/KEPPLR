#!/bin/bash

# This program generates a file containing documentation of all
# programs in this software system by running all programs with no
# arguments and piping the usage output to a file.

if [ -z "$1" ]; then
    echo "usage: $0 scriptDir pythonDir"
    echo "e.g.   $0 ../KEPPLR-2025.01.28/scripts"
    exit 0
fi

wrap_lines() {
    local width="${1:-80}" # Default to 80 if no width is provided
    awk -v maxwidth="$width" '{
    if (length($0) <= maxwidth) {
      print
    } else {
      line = $0
      while (length(line) > maxwidth) {
        for (i = maxwidth; i > 0; i--) {
          if (substr(line, i, 1) == " ") {
            print substr(line, 1, i)
            line = substr(line, i + 1)
            break
          }
        }
        if (i==0) {
        break
        }
      }
      if (length(line) > 0) print line
    }
  }'
}

rootDir=$(
    cd "$(dirname "$0")"
    pwd -P
)
scriptDir=$1

rm -fr "${rootDir}/tools"
mkdir -p "${rootDir}/tools"
indexfile=${rootDir}/tools/index.rst

rm -f "$indexfile"
cat >>"$indexfile" <<EOF
==========
Java Tools
==========

EOF

programsToSkip=()
toctree=()

rsync -a tools-src/ tools
mkdir -p toolDescriptions
for f in $(find "${scriptDir}" -maxdepth 1 -type f | sort -f); do
    f=$(basename "$f")
    flink=$(echo "$f" | awk '{print tolower($0)}' | sed -e 's/[^[:alnum:]|-]/\-/g')

    skip=0
    # Ignore programs that begin with lowercase letter or the string "Immutable"
    if [[ "$f" =~ ^([a-z].*|Immutable.*) ]]; then
        skip=1
    fi

    for program in "${programsToSkip[@]}"; do
        if [[ "$f" == "$program" ]]; then
            skip=1
            break
        fi
    done

    if [ $skip -eq 1 ]; then
        echo "Skipping $f"
        continue
    fi

    echo "Generating documentation for $f"

    shortDescription=$("${scriptDir}"/"$f" -shortDescription)

    "${scriptDir}"/"$f" | wrap_lines 80 >toolDescriptions/"${f}".txt

    cat >>"$indexfile" <<EOF
:doc:\`$f\`: $shortDescription

EOF

    if [ ! -e tools-src/"${f}".rst ]; then
        cat >>tools/"${f}".rst <<EOF
$(printf '=%.0s' {1..100})
$f
$(printf '=%.0s' {1..100})

*****
Usage
*****

.. include:: ../toolDescriptions/${f}.txt
    :literal:

EOF
    fi

    toctree+=("$f")
done

echo ".. toctree::" >>$indexfile
echo -e "   :hidden:\n" >>$indexfile
for f in "${toctree[@]}"; do
    echo "   $f" >>$indexfile
done

