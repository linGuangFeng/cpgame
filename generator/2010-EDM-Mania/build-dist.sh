#!/bin/sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
core_dir="$project_dir/../../server-api/2010-EDM-Mania"; classes="$project_dir/target/classes"; dist="$project_dir/dist"
mkdir -p "$classes" "$dist"
find "$core_dir/src/main/java/com/cpgame/g2010/core" -name '*.java' -print > "$project_dir/target/core-sources.txt"
find "$project_dir/src/main/java" -name '*.java' -print > "$project_dir/target/loader-sources.txt"
javac --release 21 -d "$classes" @"$project_dir/target/core-sources.txt" @"$project_dir/target/loader-sources.txt"
jar --create --file "$dist/game-2010-evidence-gated-loader.jar" --main-class com.cpgame.g2010.generator.RedisLoader -C "$classes" .
java -jar "$dist/game-2010-evidence-gated-loader.jar" --config "$dist/loader.properties"
printf '%s\n' "$dist/game-2010-evidence-gated-loader.jar"
