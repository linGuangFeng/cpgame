#!/bin/sh
set -e
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
LOADER_JAR="$SCRIPT_DIR/crazygems-redis-loader.jar"
for candidate in "$SCRIPT_DIR"/crazygems-redis-loader*.jar; do
  if [ -f "$candidate" ] && [ "$candidate" -nt "$LOADER_JAR" ]; then LOADER_JAR="$candidate"; fi
done
LOADER_CONFIG="$SCRIPT_DIR/generator.properties"
if [ ! -f "$LOADER_JAR" ]; then
  echo "[ERROR] JAR not found: $LOADER_JAR" >&2
  exit 1
fi
if [ ! -f "$LOADER_CONFIG" ]; then
  echo "[ERROR] Config not found: $LOADER_CONFIG" >&2
  exit 1
fi
echo "Starting Crazy Gems Redis round generator..."
java -jar "$LOADER_JAR" "$LOADER_CONFIG"
echo "[OK] Generation and Redis loading completed."
