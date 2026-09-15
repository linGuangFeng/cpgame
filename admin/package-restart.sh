#!/bin/sh
set -eu

ADMIN_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$ADMIN_DIR"

if ! command -v mvn >/dev/null 2>&1; then
  echo "[ERROR] mvn was not found in PATH." >&2
  exit 1
fi

echo "[1/2] Packaging CPGame admin..."
mvn -q -DskipTests package

TARGET_JAR="$ADMIN_DIR/target/cpgame-admin-0.1.0-SNAPSHOT.jar"
if [ ! -f "$TARGET_JAR" ]; then
  echo "[ERROR] Packaged jar was not found." >&2
  exit 1
fi

mkdir -p "$ADMIN_DIR/var"
cp "$TARGET_JAR" "$ADMIN_DIR/var/cpgame-admin.jar.next"

echo "[2/2] Restarting CPGame admin..."
exec sh "$ADMIN_DIR/restart.sh" --deploy
