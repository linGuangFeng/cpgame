#!/bin/sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
core_dir="$project_dir/../../server-api/1910-Churrasco"
classes="$project_dir/target/classes"
dist="$project_dir/dist"
mkdir -p "$classes" "$dist"
find "$core_dir/src/main/java/com/cpgame/g1910/core" -name '*.java' -print > "$project_dir/target/core-sources.txt"
find "$project_dir/src/main/java" -name '*.java' -print > "$project_dir/target/loader-sources.txt"
javac --release 21 -d "$classes" @"$project_dir/target/core-sources.txt" @"$project_dir/target/loader-sources.txt"
jar --create --file "$dist/game-1910-evidence-gated-loader.jar" \
  --main-class com.cpgame.g1910.generator.RedisLoader -C "$classes" .
java -jar "$dist/game-1910-evidence-gated-loader.jar" --config "$dist/loader.properties"
printf '%s\n' "$dist/game-1910-evidence-gated-loader.jar"
