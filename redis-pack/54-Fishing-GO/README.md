# Fishing GO Redis 结果包

本目录记录游戏 54 的正式 Redis 完整局交付合同。可执行 Loader、无 seed 正式配置和双击脚本位于 `generator/54-Fishing-GO/dist`；Controller 位于 `server-api/54-Fishing-GO/dist`。

Loader 使用 Java `GameRuleCore` 和安全随机生成完整 Round，经独立 `ResultUtil/RoundVerifier` 复核后，以一个 member 对应一个完整 Round 的方式原子写入 Redis。Controller 仅在没有激活 Round 的付费起点领取一次 member；后续免费 Step、幂等重试、config 续局和 History 只消费或投影该 Round。Redis 缺少正倍率 member 时，只允许实时生成并独立复核普通单步 LOSS。

本目录不包含 fixture、历史响应、JSONL 结果或预置牌面。
