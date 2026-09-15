# Bee Workshop Redis pack

Run `java -jar redis-loader.jar loader.properties`. The checked-in configuration targets `192.168.10.3:6379`, database 15, and clears only `cpgame:v3:2110:*` before loading. It never flushes database 15.

Every pool member is printable ASCII `BW1` and contains a complete round. Members are indexed by win/loss, gameplay kind, and integer multiplier. The loader has no input path for fixtures, captured responses, fixed results, or a user-supplied seed.
