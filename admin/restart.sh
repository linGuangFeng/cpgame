#!/bin/sh
set -eu

ADMIN_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT_DIR=$(CDPATH= cd -- "$ADMIN_DIR/.." && pwd)
VAR_DIR=${CPGAME_ADMIN_RUNTIME:-"$ADMIN_DIR/var"}
PORT=${CPGAME_ADMIN_PORT:-8000}
ADDRESS=${CPGAME_ADMIN_ADDRESS:-0.0.0.0}
ARTIFACT_ROOT=${CPGAME_ARTIFACT_ROOT:-"$ROOT_DIR"}
PID_FILE="$ADMIN_DIR/var/admin.pid"
RUNTIME_JAR="$ADMIN_DIR/var/cpgame-admin.jar"
TARGET_JAR="$ADMIN_DIR/target/cpgame-admin-0.1.0-SNAPSHOT.jar"

mkdir -p "$ADMIN_DIR/var" "$VAR_DIR"

if [ -f "$PID_FILE" ]; then
  OLD_PID=$(cat "$PID_FILE" 2>/dev/null || true)
  case "$OLD_PID" in
    ''|*[!0-9]*) ;;
    *)
      OLD_COMMAND=$(ps -p "$OLD_PID" -o command= 2>/dev/null || true)
      case "$OLD_COMMAND" in
        *cpgame-admin.jar*|*cpgame-admin-0.1.0-SNAPSHOT.jar*)
          kill "$OLD_PID" 2>/dev/null || true
          WAIT_COUNT=0
          while kill -0 "$OLD_PID" 2>/dev/null && [ "$WAIT_COUNT" -lt 20 ]; do
            sleep 0.5
            WAIT_COUNT=$((WAIT_COUNT + 1))
          done
          if kill -0 "$OLD_PID" 2>/dev/null; then
            kill -9 "$OLD_PID" 2>/dev/null || true
          fi
          ;;
      esac
      ;;
  esac
  rm -f "$PID_FILE"
fi

mkdir -p "$ADMIN_DIR/var/run"
STAGED_JAR="$ADMIN_DIR/var/cpgame-admin.jar.next"
SOURCE_JAR=""
if [ "${1:-}" = "--deploy" ]; then
  if [ -f "$STAGED_JAR" ]; then
    SOURCE_JAR=$STAGED_JAR
  elif [ -f "$TARGET_JAR" ]; then
    SOURCE_JAR=$TARGET_JAR
  else
    echo "[ERROR] Staged jar was not found." >&2
    exit 1
  fi
else
  for CANDIDATE in "$TARGET_JAR" "$STAGED_JAR" "$RUNTIME_JAR"; do
    if [ -f "$CANDIDATE" ]; then
      if [ -z "$SOURCE_JAR" ] || [ "$CANDIDATE" -nt "$SOURCE_JAR" ]; then
        SOURCE_JAR=$CANDIDATE
      fi
    fi
  done
  for CANDIDATE in "$ADMIN_DIR"/var/run/cpgame-admin-*.jar; do
    [ -f "$CANDIDATE" ] || continue
    if [ -z "$SOURCE_JAR" ] || [ "$CANDIDATE" -nt "$SOURCE_JAR" ]; then
      SOURCE_JAR=$CANDIDATE
    fi
  done
fi
if [ -z "$SOURCE_JAR" ] || [ ! -f "$SOURCE_JAR" ]; then
  echo "[ERROR] No packaged jar found. Run package-restart.sh first." >&2
  exit 1
fi

STAMP=$(date +%Y%m%d-%H%M%S)
ADMIN_JAR="$ADMIN_DIR/var/run/cpgame-admin-$STAMP.jar"
cp "$SOURCE_JAR" "$ADMIN_JAR"
cp "$SOURCE_JAR" "$RUNTIME_JAR" 2>/dev/null || true
echo "Using jar: $ADMIN_JAR"

nohup java --add-exports=jdk.httpserver/sun.net.httpserver=ALL-UNNAMED \
  -jar "$ADMIN_JAR" \
  --port="$PORT" --address="$ADDRESS" --root="$ARTIFACT_ROOT" --runtime="$VAR_DIR" \
  >"$ADMIN_DIR/var/admin.stdout.log" 2>"$ADMIN_DIR/var/admin.stderr.log" </dev/null &
ADMIN_PID=$!
printf '%s' "$ADMIN_PID" >"$PID_FILE"

HEALTH_URL="http://127.0.0.1:$PORT/health"
WAIT_COUNT=0
while [ "$WAIT_COUNT" -lt 30 ]; do
  if ! kill -0 "$ADMIN_PID" 2>/dev/null; then
    break
  fi
  if curl -fsS --max-time 1 "$HEALTH_URL" 2>/dev/null | grep -qx 'ok'; then
    echo "CPGame admin is running at http://127.0.0.1:$PORT"
    exit 0
  fi
  sleep 0.5
  WAIT_COUNT=$((WAIT_COUNT + 1))
done

echo "[ERROR] CPGame admin failed to become healthy. See var/admin.stderr.log." >&2
exit 1
