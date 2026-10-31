#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
TOOLS=${FOLIA_TOOLS:-$(dirname "$ROOT")/folia-tools}
JAVA_HOME=${JAVA_HOME:-$TOOLS/jdk-25.0.5.0.2+1}
OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT
"$JAVA_HOME/bin/javac" -cp "$TOOLS/junit-console.jar" -d "$OUT" \
 "$ROOT"/InvSee++_Folia/src/main/java/com/janboerman/invsee/folia/transfer/*.java \
 "$ROOT"/InvSee++_Folia/src/main/java/com/janboerman/invsee/folia/offline/OfflineAccessCoordinator.java \
 "$ROOT"/InvSee++_Folia/src/test/java/com/janboerman/invsee/folia/transfer/TransferServiceTest.java \
 "$ROOT"/InvSee++_Folia/src/test/java/com/janboerman/invsee/folia/transfer/AdversarialOwnerTest.java
"$JAVA_HOME/bin/java" -jar "$TOOLS/junit-console.jar" execute --class-path "$OUT" --select-class com.janboerman.invsee.folia.transfer.AdversarialOwnerTest --select-class com.janboerman.invsee.folia.transfer.TransferServiceTest
