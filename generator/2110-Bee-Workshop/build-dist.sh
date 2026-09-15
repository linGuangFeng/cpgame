#!/bin/sh
set -eu
bee_project=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
bee_controller="$bee_project/../../server-api/2110-Bee-Workshop/dist/controller.jar"
bee_classes="$bee_project/target/offline-v3/classes"
bee_javac=javac
bee_jar=jar
if [ -n "${JAVA_HOME:-}" ]; then
  bee_javac="$JAVA_HOME/bin/javac"
  bee_jar="$JAVA_HOME/bin/jar"
fi
mkdir -p "$bee_classes"
"$bee_javac" --release 17 -cp "$bee_controller" -d "$bee_classes" \
  "$bee_project/src/main/java/com/cpgame/g2110/generator/RedisLoader.java"
cp "$bee_controller" "$bee_project/target/offline-v3/redis-loader.jar"
"$bee_jar" --update --file "$bee_project/target/offline-v3/redis-loader.jar" \
  --main-class com.cpgame.g2110.generator.RedisLoader -C "$bee_classes" .
cp "$bee_project/target/offline-v3/redis-loader.jar" "$bee_project/dist/redis-loader.jar"
printf '%s\n' "$bee_project/dist/redis-loader.jar"
