#!/bin/sh
set -eu
mkdir -p target/classes
javac --release 17 -d target/classes $(find src/main/java -name '*.java')
printf 'Main-Class: com.cpgame.server.Main\n' > target/MANIFEST.MF
jar cfm target/jurassic-jungle-server.jar target/MANIFEST.MF -C target/classes .
cp target/jurassic-jungle-server.jar dist/
