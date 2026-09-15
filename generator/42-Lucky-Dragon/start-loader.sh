#!/bin/sh
set -eu

# Unix 启动入口放在工程根目录，保持正式 dist 严格只有 JAR、properties 和 Windows .cmd 三件套。
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
DIST_DIR="$SCRIPT_DIR/dist"
JAR="$DIST_DIR/lucky-dragon-redis-loader.jar"
CONFIG="$DIST_DIR/generator.properties"

if [ ! -f "$JAR" ] || [ ! -f "$CONFIG" ]; then
  printf '%s\n' "[失败] gid42 正式 Loader JAR 或 generator.properties 不存在" >&2
  exit 2
fi

exec java -jar "$JAR" "$CONFIG"
