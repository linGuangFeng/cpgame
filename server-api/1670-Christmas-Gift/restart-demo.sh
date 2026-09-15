#!/bin/sh
set -eu
BASE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT=$(CDPATH= cd -- "$BASE/../.." && pwd)
"$BASE/build.sh"
PIDFILE="$BASE/dist/demo.pid"
if [ -f "$PIDFILE" ]; then kill "$(cat "$PIDFILE")" 2>/dev/null || true; fi
java -jar "$BASE/dist/christmas-gift-server.jar" --port 18170 --publish "$ROOT/publish/1670-Christmas-Gift" >"$BASE/dist/demo.log" 2>&1 &
echo $! > "$PIDFILE"
echo "http://127.0.0.1:18170/index.html" > "$BASE/demo-url.txt"
echo "Christmas Gift started: http://127.0.0.1:18170/index.html"
