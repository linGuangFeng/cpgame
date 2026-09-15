#!/bin/sh
set -eu
GEN_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$GEN_DIR/../.." && pwd)
API_DIR="$ROOT/server-api/1780-Glacier-Treasure"
if [ -x /Users/a1/Library/Java/JavaVirtualMachines/jdk-21.0.12.1.jdk/Contents/Home/bin/javac ]; then
  JAVA_HOME=/Users/a1/Library/Java/JavaVirtualMachines/jdk-21.0.12.1.jdk/Contents/Home
elif [ -n "${JAVA_HOME:-}" ]; then
  :
elif [ -x /usr/libexec/java_home ]; then
  JAVA_HOME=$(/usr/libexec/java_home -v 21)
else
  echo "JDK 21 is required" >&2
  exit 2
fi
JAVAC="$JAVA_HOME/bin/javac"
JAR="$JAVA_HOME/bin/jar"
JAVA="$JAVA_HOME/bin/java"
CLASSES="$GEN_DIR/target/classes"
rm -rf "$CLASSES"
mkdir -p "$CLASSES" "$GEN_DIR/dist" "$API_DIR/dist"
"$JAVAC" --release 21 -encoding UTF-8 -d "$CLASSES" \
  "$GEN_DIR"/src/main/java/com/cpgame/glacier/*.java \
  "$API_DIR"/src/main/java/com/cpgame/glacier/GlacierTreasureController.java
"$JAR" --create --file "$GEN_DIR/dist/glacier-treasure-loader.jar" \
  --main-class com.cpgame.glacier.RedisDirectLoader -C "$CLASSES" .
"$JAR" --create --file "$API_DIR/dist/controller.jar" \
  --main-class com.cpgame.glacier.GlacierTreasureController -C "$CLASSES" .
cp "$API_DIR/demo-controller.properties" "$API_DIR/dist/demo-controller.properties"
echo "BUILD_OK loader=$GEN_DIR/dist/glacier-treasure-loader.jar controller=$API_DIR/dist/controller.jar"
