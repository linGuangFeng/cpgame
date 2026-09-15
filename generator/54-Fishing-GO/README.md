# Fishing GO Java 结果生成器

本工程以 `protocol/54-Fishing-GO/game-capabilities.json` 与同哈希的 `protocol-handoff.json` 为规则门禁。`game-rule-core` 是 Controller 和正式 Loader 唯一共同依赖的规则实现；`redis-loader` 只负责配置、最小事实 Codec、独立复核和 Redis 原子写入。

正式运行使用 `dist/start-loader.cmd`。双击脚本会显示中文结果并保留窗口；自动化可传 `--no-pause`。正式配置不接受 seed，Loader 内部使用 `SecureRandom`。只有 Java 测试通过 `GameRuleCore.forTesting(...)` 显式传入 seed。

Redis member 每条恰好表示一个完整付费 Round。普通 0 倍局跳过，正倍率按独立 `ResultUtil` 反推的实际倍率分桶；每批 `ZADD + RPUSH + LTRIM` 位于同一 `MULTI/EXEC`。免费重触发虽有规则文本，但缺少真实相邻字段证据，正式生成链路保持禁用且不会猜测 `fsn` 更新时间。

`generator.properties` 使用 `redis.socket-timeout-ms`、普通/特殊完整局累计中奖倍数上限，以及 `generation.symbol.<当前游戏符号>.weight` 正数权重。全部参数均由 Java Loader 实际读取；这些 Fishing GO 本地复刻权重不代表原厂 RTP。
