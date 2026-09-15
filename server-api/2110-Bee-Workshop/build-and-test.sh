#!/bin/sh
set -eu
bee_project=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
bee_root=$(CDPATH= cd -- "$bee_project/../.." && pwd)
bee_classes="$bee_project/target/offline-v3/classes"
bee_tests="$bee_project/target/offline-v3/tests"
bee_dependency="$bee_project/dist/controller.jar"
bee_javac=javac
bee_java=java
bee_jar=jar
if [ -n "${JAVA_HOME:-}" ]; then
  bee_javac="$JAVA_HOME/bin/javac"
  bee_java="$JAVA_HOME/bin/java"
  bee_jar="$JAVA_HOME/bin/jar"
fi
mkdir -p "$bee_classes" "$bee_tests"
if command -v rg >/dev/null 2>&1; then
  rg --files "$bee_project/src/main/java" -g '*.java' > "$bee_project/target/offline-v3/sources.txt"
else
  find "$bee_project/src/main/java" -name '*.java' -print > "$bee_project/target/offline-v3/sources.txt"
fi
"$bee_javac" --release 17 -cp "$bee_dependency" -d "$bee_classes" @"$bee_project/target/offline-v3/sources.txt"
"$bee_javac" --release 17 -cp "$bee_classes:$bee_dependency" -d "$bee_tests" \
  "$bee_project/src/test/java/com/cpgame/g2110/core/ResultUtilEvidenceCheck.java" \
  "$bee_project/src/test/java/com/cpgame/g2110/core/DealModelEvidenceCheck.java" \
  "$bee_project/src/test/java/com/cpgame/g2110/server/ControllerContractCheck.java"
bee_cp="$bee_classes:$bee_tests:$bee_project/src/main/resources:$bee_dependency"
"$bee_java" -cp "$bee_cp" com.cpgame.g2110.core.ResultUtilEvidenceCheck "$bee_root/fixtures/2110-Bee-Workshop/spin"
"$bee_java" -cp "$bee_cp" com.cpgame.g2110.core.DealModelEvidenceCheck "$bee_root" "$bee_root/reports/2110-Bee-Workshop/generation-model-validation.json"
"$bee_java" -cp "$bee_cp" com.cpgame.g2110.server.ControllerContractCheck "$bee_project/dist/controller.properties" "$bee_root/publish/2110-Bee-Workshop"
cp "$bee_dependency" "$bee_project/target/offline-v3/controller.jar"
"$bee_jar" --update --file "$bee_project/target/offline-v3/controller.jar" \
  -C "$bee_classes" . -C "$bee_project/src/main/resources" .
cp "$bee_project/target/offline-v3/controller.jar" "$bee_dependency"
printf '%s\n' "$bee_dependency"
