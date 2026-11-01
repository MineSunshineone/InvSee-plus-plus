#!/usr/bin/env bash
# Builds only this plugin; never copies server/dependency classes into its jar.
set -euo pipefail
if [[ $# != 3 ]]; then
  echo "Usage: JAVA_HOME=/jdk25 $0 /bootstrapped-folia-runtime /junit-platform-console-standalone.jar /jetbrains-annotations.jar" >&2
  exit 2
fi
MODULE="$(cd "$(dirname "$0")" && pwd)"
RUNTIME="$(cd "$1" && pwd)"
JUNIT="$(realpath "$2")"
ANNOTATIONS="$(realpath "$3")"
JAVA="${JAVA_HOME:?Set JAVA_HOME to JDK 25}/bin"
OUT="$MODULE/target/runtime-build"
mkdir -p "$OUT/classes" "$OUT/test-classes"
# Clean generated outputs only, never server files or sources.
find "$OUT/classes" "$OUT/test-classes" -type f -delete
mapfile -t SERVER < <(find "$RUNTIME/versions" -name 'folia-*.jar' -type f | sort)
[[ ${#SERVER[@]} == 1 ]] || { echo "Expected exactly one Folia server jar" >&2; exit 2; }
CP="${SERVER[0]}:$ANNOTATIONS"
while IFS= read -r -d '' LIB; do CP="$CP:$LIB"; done < <(find "$RUNTIME/libraries" -name '*.jar' -type f -print0 | sort -z)
"$JAVA/java" -version 2> "$OUT/java-version.txt"
sha256sum "${SERVER[0]}" "$JUNIT" "$ANNOTATIONS" > "$OUT/input-sha256.txt"
find "$RUNTIME/libraries" -name '*.jar' -type f -print0 | sort -z | xargs -0 sha256sum >> "$OUT/input-sha256.txt"
find "$MODULE/src" -type f -print0 | sort -z | xargs -0 sha256sum > "$OUT/source-sha256.txt"
sha256sum "$MODULE/pom.xml" "$0" >> "$OUT/source-sha256.txt"
find "$MODULE/src/main/java" -name '*.java' -type f | sort > "$OUT/main-sources.txt"
"$JAVA/javac" --release 25 -encoding UTF-8 -cp "$CP" -d "$OUT/classes" @"$OUT/main-sources.txt"
python3 - "$MODULE" "$OUT/classes" <<'PY'
import pathlib,sys,xml.etree.ElementTree as ET
module,out=map(pathlib.Path,sys.argv[1:])
root=ET.parse(module/'pom.xml').getroot(); ns={'m':'http://maven.apache.org/POM/4.0.0'}
version=root.findtext('m:version',namespaces=ns) or root.findtext('m:parent/m:version',namespaces=ns)
description=root.findtext('m:description',namespaces=ns)
for src in (module/'src/main/resources').rglob('*'):
    if src.is_file():
        target=out/src.relative_to(module/'src/main/resources');target.parent.mkdir(parents=True,exist_ok=True)
        target.write_text(src.read_text().replace('${project.version}',version).replace('${project.description}',description))
PY
find "$MODULE/src/test/java" -name '*.java' -type f | sort > "$OUT/test-sources.txt"
if [[ -s "$OUT/test-sources.txt" ]]; then
  "$JAVA/javac" --release 25 -encoding UTF-8 -cp "$OUT/classes:$CP:$JUNIT" -d "$OUT/test-classes" @"$OUT/test-sources.txt"
  "$JAVA/java" -jar "$JUNIT" execute --class-path "$OUT/classes:$OUT/test-classes:$CP" --scan-class-path="$OUT/test-classes" --fail-if-no-tests --disable-ansi-colors | tee "$OUT/tests.log"
fi
"$JAVA/jar" --create --file "$MODULE/target/InvSee++-Folia.jar" -C "$OUT/classes" .
echo "Built $MODULE/target/InvSee++-Folia.jar"
