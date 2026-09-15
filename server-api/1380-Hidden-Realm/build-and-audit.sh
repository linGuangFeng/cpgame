#!/bin/sh
set -eu
GAME_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
mkdir -p "$GAME_DIR/build/classes"
javac --release 21 -d "$GAME_DIR/build/classes" "$GAME_DIR"/src/main/java/com/cpgame/hiddenrealm/*.java
java -cp "$GAME_DIR/build/classes" com.cpgame.hiddenrealm.EvidenceAudit
