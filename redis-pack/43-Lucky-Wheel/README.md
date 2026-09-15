# Lucky Wheel 43 Redis 结果交付状态

状态：`DIRECT_REDIS_DELIVERY_VALIDATED`。

正式 Java Loader 从 `generator/43-Lucky-Wheel/dist` 使用 `SecureRandom + GameRuleCore` 生成完整 Round，经独立 `ResultUtil` 反推实际模式和倍率。普通未中奖写入 0 倍池，中奖按实际正整数倍率 Key 写入；同一 `MULTI/EXEC` 内执行 `ZADD + RPUSH + LTRIM`，每倍率默认保留最新 300 个 member。

极简 member 只保存当前游戏事实。低档沿用 `md=0 -> 0BB`、`md=1 -> 1MBB`、`md=2 -> 2BBRR`；高档增加前缀 `5` 和三个基础位置，并支持已取证的 `md=3 -> 53BBBWWW`（三位 Lucky Wheel 奖励）。普通奖与玛丽奖都按解锁档分 key：下划线后第一位 `0` 为 bet&lt;5，`1` 为 bet&gt;=5 解锁新玩法。例如 `PerKeyList_000000043` / `PerKeyList_100000043`，`MaryKeyList_000000043` / `MaryKeyList_100000043`。低档拒绝 md=3。

此目录不保存或导入 JSONL/历史响应；正式结果直接进入 Redis。连接凭据不写入报告或 Redis 包。

机器证据：`reports/43-Lucky-Wheel/redis-delivery-validation.json`。
