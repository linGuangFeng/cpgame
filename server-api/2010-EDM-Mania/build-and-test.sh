#!/bin/sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
classes="$project_dir/target/classes"; test_classes="$project_dir/target/test-classes"; dist="$project_dir/dist"
mkdir -p "$classes" "$test_classes" "$dist"
find "$project_dir/src/main/java" -name '*.java' -print > "$project_dir/target/main-sources.txt"
javac --release 21 --add-modules jdk.httpserver -d "$classes" @"$project_dir/target/main-sources.txt"
find "$project_dir/src/test/java" -name '*.java' -print > "$project_dir/target/test-sources.txt"
javac --release 21 -cp "$classes" -d "$test_classes" @"$project_dir/target/test-sources.txt"
java -cp "$classes:$test_classes" com.cpgame.g2010.core.GameRuleCoreTest
jar --create --file "$dist/game-2010-evidence-gated-server.jar" --main-class com.cpgame.g2010.server.EvidenceGatedServer -C "$classes" .
printf '%s\n' "$dist/game-2010-evidence-gated-server.jar"
