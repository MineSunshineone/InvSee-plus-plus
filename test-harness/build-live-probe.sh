#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
TOOLS=${FOLIA_TOOLS:-$(dirname "$ROOT")/folia-tools}
SERVER=${FOLIA_SERVER:-$(dirname "$ROOT")/folia-runtime-26.2}
JAVA_HOME=${JAVA_HOME:-$TOOLS/jdk-25.0.5.0.2+1}
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
CP=$(find "$SERVER/libraries" -name '*.jar' -printf '%p:' )
"$JAVA_HOME/bin/javac" -cp "$CP" -d "$OUT" "$ROOT"/InvSee++_Folia/src/main/java/com/janboerman/invsee/folia/transfer/*.java "$ROOT/test-harness/live/src/LiveOwnerProbe.java"
cp "$ROOT/test-harness/live/plugin.yml" "$OUT/plugin.yml"
"$JAVA_HOME/bin/jar" cf "$ROOT/test-harness/InvSeeLiveOwnerProbe.jar" -C "$OUT" .
echo 'Built only; not installed. Read live README prerequisites before isolated-server use.'
