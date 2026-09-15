# Electro Fiesta Java Redis Loader

正式入口使用 `SecureRandom`，没有 seed、倍率追逐、0 倍开关或本地 JSONL 回退。每个候选由独立 `ResultUtil` 反推模式及倍率；0 倍自然进入未中奖池。`RPUSH` 与 `LTRIM` 位于同一 `MULTI/EXEC`。

构建：`mvn clean install`。交付目录只保留 `redis-loader.jar`、`generator.properties`、`run-generator.cmd`。


## 独立无奖标记（2026-09-14）

本次只接普通 NORMAL、单步终止、无奖且不触发特殊结构的完整局，保留 EF1 版本头，编码为 `EF1:#`。解析器调用共享 CandidateFactory 的独立零奖入口，校验真实中奖为零且没有两列相同满列触发结构。

RESPIN_UNTIL_WIN（锁列重转）和 MULTIPLIER_STICKY（粘性倍率）全程保留原编码，包括未派奖的中间步骤。`EF1:#1`、混合特殊步骤与 `#`、尾部空步骤均拒绝。消费端同次解码后复核实际倍率与 Redis 所选倍率桶、普通/特殊池一致。

新解析器兼容已有完整编码。每次领取 Redis member 后只物化一次，校验、后续交付、重试与历史共用该事实；Redis 为空时仍失败。零奖生成最多尝试 5 次，并有 10 个已校验默认盘（41 按 PAN 数量分别保存）。先更新消费端 JAR，再使用新 Loader 写入带标记的数据。Loader JAR 通过 Maven package 交付到本游戏 dist 目录。
