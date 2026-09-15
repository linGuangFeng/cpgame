# Crazy Gems Redis 结果包

正式入口：`generator/58-Crazy-Gems/dist/start-redis-loader.sh`（或 `.cmd`）。

- Host `192.168.10.3:6379` database `15`，`redis.game-id=58`
- 0 倍不写缓存；Controller 按 `round.loss-probability` 使用独立无奖生成器
- 所有中奖结果统一进 `PerKeyList_0%08d` / `BetLog:0%08d:%06d`；不再区分普通和矿车奖励
- 完整10位结果在内存中去重；追加模式先载入已有统一池结果
- member 10 字符 ASCII，不是 JSON
