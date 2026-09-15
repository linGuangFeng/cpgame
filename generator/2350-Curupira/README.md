# 2350 Curupira 完整 Round 结果引擎

本 Maven 工程提供试玩 API 与 Loader 共用的唯一一份 Java `GameRuleCore`。工程将随机候选生成、
基于当前游戏证据的 `ResultUtil`、完整局 `RoundFactory`、最小事实编码门禁、Redis Loader 与独立
复核器分离实现。

普通付费局从 Redis 领取。Scatter 触发局实时生成不中奖 3 Scatter 盘，不写入缓存。
前端选关后，从对应 Mary 前缀领取后续完整结果，与触发步拼成同一局 History：

- 普通 LOSS/WIN/Expanding Wild：`PerKeyList_000002350` / `BetLog:000002350:xxxxxx`
- 选 Expanding Wild（type=2/3, game_type=2）：`MaryKeyList_000002350` / `MaryLog:000002350:xxxxxx`
- 选 Hold & Spins（type=2/3, game_type=3）：`MaryKeyList_100002350` / `MaryLog:100002350:xxxxxx`

Demo 20 局付费轮询覆盖 LOSS、WIN、Expanding Wild 与实时 Scatter 选关；选关后 type=2 按 game_type
领取 Mary 0 或 Mary 1；购买走 type=3，领同一套选关结果。生产链路使用 `SecureRandom`；可复现的确定性随机仅存在于测试源码。

构建命令：`mvn.cmd clean package`。交付目录是 `dist`，`target` 仅为构建缓存。


## 独立无奖标记（2026-09-14）

本次只接普通付费单步 LOSS，保留 CU1 版本和入口/种类头，编码为 `CU1PL;#`。解析器调用共享 ConstructiveLossGenerator 物化真实零奖盘面，禁止带入免费触发或整列扩展 Wild。

FREE_EW / BUY_FE（免费扩展 Wild）、HOLD / BUY_HS（锁币）、TRIGGER 及其他种类仍完整保存。本次未接免费扩展 Wild 的专用零奖生成逻辑；锁币中即使当前没有新增奖金，也不得替换前后继承的格子与剩余次数。只有 `CU1PL;#` 接受标记，其他模式的 `#` 明确拒绝。

新解析器兼容已有完整编码。每次领取 Redis member 后只物化一次，校验、后续交付、重试与历史共用该事实；Redis 为空时仍失败。零奖生成最多尝试 5 次，并有 10 个已校验默认盘（41 按 PAN 数量分别保存）。先更新消费端 JAR，再使用新 Loader 写入带标记的数据。Loader JAR 通过 Maven package 交付到本游戏 dist 目录。
