# 2350 Curupira 完整 Round 结果引擎

本 Maven 工程提供试玩 API 与 Loader 共用的唯一一份 Java `GameRuleCore`。工程将随机候选生成、
基于当前游戏证据的 `ResultUtil`、最小事实编码门禁、Redis Loader 与独立复核器分离实现。
Demo 只复用这里产出的 JAR 做解码/规则复核，不在请求现场调用候选生成。

当前正式范围生成三类完整事实：普通付费起点、6 Step 免费扩展 Wild、完整 Hold & Spins 序列。
生成器先按显式场景权重选择场景，再自然生成并独立校验；不接受目标类别、目标奖金或样本局模板。
普通候选中的 3+ Scatter 分类为 `TRIGGER` 并写普通类型 0，为后续二选一功能提供缓存起点。
免费模式写 `Mary type0`，Hold 写 `Mary type1`；购买模式仍不生成。

普通和触发结果写入 `PerKeyList_008002350` / `BetLog:008002350:xxxxxx`，免费模式写
`MaryKeyList_008002350` / `MaryLog:008002350:xxxxxx`，Hold 写
`MaryKeyList_108002350` / `MaryLog:108002350:xxxxxx`（`redis.game-id=8002350`）。
0 倍与正倍都保存完整 `CU1` 事实；禁止 `CU1PL;#`
之类读取时现场物化的标记。生产链路使用 `SecureRandom`；确定性随机仅在测试源码中使用。

构建命令：`mvn.cmd clean package`。交付目录是 `dist`，`target` 仅为构建缓存。


## 权重与批次

基础权重来自现有 10 个连续原站付费局的 150 格计数，仅是有偏的小样本经验值，不代表官方转轴或 RTP。
`generation.count` 是唯一总尝试次数；普通/免费/Hold 默认场景权重为 8/1/1。每批使用同一套符号权重，按“基础 → 逐牌单独 3 倍 → 基础”循环。
无效候选不修改牌面、不重试补量。每列最多一枚 Scatter 是用户确认的当前游戏约束，发牌时直接限制可选集合。
Hold 的空位/Coin 默认临时工程权重为 9/1，Coin 面值 1..10 均匀；这些配置不是原站概率证据。
