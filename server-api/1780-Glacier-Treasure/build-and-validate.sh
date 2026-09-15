#!/bin/sh
set -eu
GAME_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
CPGAME_ROOT=$(CDPATH= cd -- "$GAME_DIR/../.." && pwd)
if [ -n "${JAVA_HOME:-}" ]; then
  GLACIER_JAVA_HOME=$JAVA_HOME
elif [ -x /usr/libexec/java_home ]; then
  GLACIER_JAVA_HOME=$(/usr/libexec/java_home -v 21)
else
  echo "Set JAVA_HOME to a JDK 21 installation." >&2
  exit 2
fi
mkdir -p "$GAME_DIR/target/classes"
"$GLACIER_JAVA_HOME/bin/javac" --release 21 -d "$GAME_DIR/target/classes" \
  "$GAME_DIR/src/main/java/com/cpgame/glacier/Json.java" \
  "$GAME_DIR/src/main/java/com/cpgame/glacier/GameRuleCore.java" \
  "$GAME_DIR/src/main/java/com/cpgame/glacier/ResultUtil.java" \
  "$GAME_DIR/src/test/java/com/cpgame/glacier/HistoryRegression.java"
"$GLACIER_JAVA_HOME/bin/jar" --create --file "$GAME_DIR/target/glacier-validation.jar" \
  --main-class com.cpgame.glacier.HistoryRegression -C "$GAME_DIR/target/classes" .
"$GLACIER_JAVA_HOME/bin/java" -jar "$GAME_DIR/target/glacier-validation.jar" \
  "$CPGAME_ROOT" "$CPGAME_ROOT/reports/1780-Glacier-Treasure/java-history-validation.json"
