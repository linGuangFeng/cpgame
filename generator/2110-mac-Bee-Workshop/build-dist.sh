#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
GEN="$ROOT/generator/2110-mac-Bee-Workshop"
API="$ROOT/server-api/2110-mac-Bee-Workshop"
JAVA_HOME="${JAVA_HOME:-/Users/a1/Library/Java/JavaVirtualMachines/jdk-21.0.12.1.jdk/Contents/Home}"
JAVAC="$JAVA_HOME/bin/javac"
JAR="$JAVA_HOME/bin/jar"
JAVA="$JAVA_HOME/bin/java"
M2="/Users/a1/.m2/repository/com/fasterxml/jackson/core"
JACKSON="$M2/jackson-databind/2.21.4/jackson-databind-2.21.4.jar:$M2/jackson-core/2.21.4/jackson-core-2.21.4.jar:$M2/jackson-annotations/2.21/jackson-annotations-2.21.jar"
OUT="$GEN/target/classes"
mkdir -p "$OUT" "$GEN/dist" "$API/dist" "$GEN/target/test-classes"
find "$GEN/src/main/java" "$API/src/main/java" -name '*.java' > "$GEN/target/sources.txt"
"$JAVAC" --release 17 -encoding UTF-8 -cp "$JACKSON" -d "$OUT" @"$GEN/target/sources.txt"
cp "$GEN/src/main/resources/com/cpgame/replica/beeworkshop/deal-model.json" "$OUT/com/cpgame/replica/beeworkshop/"
STAGE="$GEN/target/stage"
rm -rf "$STAGE"
mkdir -p "$STAGE"
cp -R "$OUT/." "$STAGE/"
for j in "$M2/jackson-databind/2.21.4/jackson-databind-2.21.4.jar" "$M2/jackson-core/2.21.4/jackson-core-2.21.4.jar" "$M2/jackson-annotations/2.21/jackson-annotations-2.21.jar"; do
  (cd "$STAGE" && "$JAR" xf "$j")
done
rm -rf "$STAGE/META-INF/maven" "$STAGE/META-INF/MANIFEST.MF"
printf 'Main-Class: com.cpgame.replica.beeworkshop.RedisDirectLoader\n' > "$GEN/target/loader.mf"
printf 'Main-Class: com.cpgame.replica.beeworkshop.BeeWorkshopController\n' > "$GEN/target/controller.mf"
"$JAR" cfm "$GEN/dist/bee-workshop-redis-loader.jar" "$GEN/target/loader.mf" -C "$STAGE" .
"$JAR" cfm "$API/dist/controller.jar" "$GEN/target/controller.mf" -C "$STAGE" .
cp "$GEN/generator.properties" "$GEN/dist/generator.properties"
cp "$API/demo-controller.properties" "$API/dist/demo-controller.properties"
test -f "$API/dist/controller.properties"
echo "BUILT $GEN/dist/bee-workshop-redis-loader.jar"
echo "BUILT $API/dist/controller.jar"
