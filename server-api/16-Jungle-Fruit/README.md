# Jungle Fruit Controller v3

JDK 21+. Build with generator/16-Jungle-Fruit/build-jars.py. The standalone dist/controller.jar contains the exact shared GameRuleCore and Redis member decoder.

Launch with absolute paths:
java -jar controller.jar --config controller.properties --port 50116 --publish /Volumes/hd/cpgame/publish/16-Jungle-Fruit

PORT is supported when --port is absent; only 50000..59999 is accepted. The Controller listens on exactly that port and serves the supplied publish directory. Health: /health.

Original browser URL:
http://127.0.0.1:50116/?t=demo-jf16&l=en&gid=16&sip=127.0.0.1:50116

For an independent static server, change the page host/port and retain sip pointing to the Controller. sip is the archived frontend's existing configuration input; do not inject GameUrl or replace the frontend.

Every paid outcome including LOSS claims one prewritten complete Redis Round. Continuations consume that in-memory Round without more claims. Runtime generation, fixture loading and scripted replay are absent. Formal Redis namespace and host/db are in dist/controller.properties and redis-pack/16-Jungle-Fruit/redis-contract.json.

Spin uses original bet_level/bet_size, game-id/web-token fields. History uses string monetary fields and object win matches, as required by the original frontend's typed copier. Spin retains numeric amounts and position arrays. Ledger debits once and credits each terminal segment once.

This local replica keeps sessions, active continuations and History in memory, initialized to the configured demonstration balance; process restart clears them. Original remote account persistence and Redis consumer protocol are not claimed. Current acceptance reports and remaining limits are in reports/16-Jungle-Fruit/current-status.json.
