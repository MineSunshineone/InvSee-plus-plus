#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
TOOLS=${FOLIA_TOOLS:-$(dirname "$ROOT")/folia-tools}
SERVER=${FOLIA_SERVER:-$(dirname "$ROOT")/folia-runtime-26.2}
JAVA_HOME=${JAVA_HOME:-$TOOLS/jdk-25.0.5.0.2+1}
OUT=$(mktemp -d); trap 'rm -rf "$OUT"' EXIT
CP=$(find "$SERVER/libraries" -name '*.jar' -printf '%p:')$TOOLS/junit-console.jar
"$JAVA_HOME/bin/javac" -cp "$CP" -d "$OUT" "$ROOT/InvSee++_Folia/src/main/java/com/janboerman/invsee/folia/PlayerTasks.java" "$ROOT/InvSee++_Folia/src/test/java/com/janboerman/invsee/folia/PlayerTasksLifecycleTest.java"
"$JAVA_HOME/bin/java" -cp "$OUT:$CP" org.junit.platform.console.ConsoleLauncher execute --select-class com.janboerman.invsee.folia.PlayerTasksLifecycleTest
