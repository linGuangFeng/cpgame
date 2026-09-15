# 2470 Lucky Night Market — clean Java rebuild

All former server/generator source and compiled output was removed or replaced by verified new artifacts without reading or porting it. Old services on ports 52470, 52472 and 52473 were stopped. The validated new process on port 55170 remains running. The shared Java implementation lives in this src/main/java tree. GameRuleCore, ResultUtil and RoundCodec are shared by both entry points; ResultUtil independently judges paylines. The JARs need Java 17 or later and have no external dependencies.

The host starts `dist/controller.jar --config dist/demo-controller.properties --port <50000-59999> --publish <absolute publish directory>`. No default port or subprocess exists. All spin results and initial board previews read complete ASCII facts from Redis. Lucky Feature responses project eight steps of the same member and end at f.st=0. Demo winner/loser probability is configured separately from formal generation.

Tests and acceptance evidence are in reports/2470-Lucky-Night-Market/clean-rebuild; current-status.json is authoritative. Original evidence is retained under resources, fixtures, captures and protocol, never loaded by production runtime.
