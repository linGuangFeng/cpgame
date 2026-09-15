# 2001007 Magic Scroll 2 Redis 结果包说明

Redis 是平台内部下游合同，不是原厂 HTTP 协议。正式 Java Loader 位于
`generator/2001007-Magic-Scroll-2/dist`，固定使用 `SecureRandom` 自然产生完整 Round；
`generator.properties` 不允许 seed、0 倍开关、倍率追逐、JSONL 或禁写开关。

普通/特殊索引分别为 `PerKeyList_002001007`、`MaryKeyList_002001007`，结果列表按
`BetLog:002001007:<实际倍率 token>`、`MaryLog:002001007:<实际倍率 token>` 分桶。
每个 member 是一个 r2 完整 Round，包含 schema/rules/seed/倍率事实和从 0 连续编号的全部
Delivery。每批在同一个 `MULTI/EXEC` 内执行 `ZADD + RPUSH + LTRIM`；自然 0 倍由代码跳过，
每个实际倍率 LIST 默认从左侧裁剪到最新 300 局。

本目录不保存 HTTP 响应、余额、Session、时间戳或 fixture。线上项目只需按奖励档位选择
倍率 LIST 并一次领取一个完整 Round member；后续请求使用同一 member 的 deliveryIndex 投影。
