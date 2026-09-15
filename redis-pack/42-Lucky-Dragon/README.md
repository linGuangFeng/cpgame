# Lucky Dragon Redis Loader contract

Loader 主类：`com.cpgame.luckydragon.loader.LoaderMain`，随 `generator/42-Lucky-Dragon/dist/lucky-dragon-redis-loader.jar` 交付。

数据键为 `PerKeyList_000000042`、`MaryKeyList_000000042` 及 `BetLog:000000042:%06d`、`MaryLog:000000042:%06d`。每个 member 只保存可独立解码的最小完整 Round 事实；0 倍未中奖进入普通 `000000` 池，正整数倍率进入对应普通或特殊池。每个 member 的 `ZADD + RPUSH + LTRIM` 在同一 `MULTI/EXEC` 中提交，容量默认 300。Loader 是 Redis 客户端，不开放任何监听端口。

本地隔离验收由 `RedisLoaderEndToEndTestMain` 对正式 `RedisLoader.run` 执行，写入 10 条未中奖、10 条普通正倍与 10 条特殊完整 Round。最终验收还实际连接 `192.168.10.3:6379 db=15` 写入 30/30/30，并测得 Controller 的 5 个付费开局恰好减少 5 个 member，幂等重放不额外领取。
