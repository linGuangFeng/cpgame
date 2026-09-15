# Cyber GO Redis 交付说明

本目录记录平台内部下游 Redis 合同。它不属于原厂游戏接口，也不会把 Redis 字段写回 `game-capabilities.json` 的上游玩法协议。

正式入口位于 `generator/52-Cyber-GO/dist`，该目录只包含一个可执行 Loader JAR、一个 `generator.properties` 和一个 `run-loader.cmd`。Loader 使用 `SecureRandom` 自然生成完整 Round；由独立 `ResultUtil` 反推真实模式和整数倍率。0 倍写入未中奖列表，正整数倍率写入对应列表并登记到中奖倍率索引。

每个 LIST member 只保存完整 Round 各 Delivery 的15格盘面，不保存类型、中奖金额、倍率或终态。每格使用一位 ASCII 符号码，Delivery 之间用 `|` 分隔；同一局所有 Step 都从同一个 member 投影，读取后必须由 `GameRuleCore` 重建并再次独立复核。

每批结果在同一个 `MULTI/EXEC` 内执行 `ZADD`、`RPUSH` 和 `LTRIM`。中奖倍率索引为 `CyberGo:52:v3:win`，完整局列表为 `CyberGo:52:v3:round:<整数倍率>`，其中倍率 `0` 是未中奖池；默认每个倍率列表只保留最新300个 member。Controller 只执行池抽取，池空时返回显式 503，绝不在运行时生成或回放 fixture。

Redis 用户名和密码只允许通过部署环境中的 `generator.properties` 提供。启动脚本、控制台日志、测试报告和交接文本均不得出现真实凭据。
