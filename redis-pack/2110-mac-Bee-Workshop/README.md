# 2110 Bee Workshop Redis

Host `192.168.10.3` port `6379` database `15`.

- 普通索引 `PerKeyList_000002110`
- 特殊索引 `MaryKeyList_000002110`
- 普通列表 `BetLog:000002110:%06d`（未中奖 `000000`）
- 特殊列表 `MaryLog:000002110:%06d`

Member 为 `BW1|kind|board.hexMask~...` 极简 ASCII，不是 JSON。

Loader：`generator/2110-mac-Bee-Workshop/dist/bee-workshop-redis-loader.jar` + `generator.properties` + `Windows.cmd`。
Demo 只 RPOP 预生成完整局，缓存空返回 503。
