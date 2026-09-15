# Cyber GO 完整局结果引擎

本工程只实现 `game-capabilities.json` 已确认的 Cyber GO 规则。`GameRuleCore` 是 server-api、试玩与正式 Loader 的唯一规则入口；随机候选、完整局拼装、Ways 结果反推、完整局校验、最小事实转换和 Redis 入口均为独立 Java 类。

正式入口不接受 seed。显式 Java 测试可以注入可复现随机源，但不会进入 `dist` 配置或启动参数。fixtures 与抓包仅供外部 oracle 测试，运行时代码不读取它们。

执行正式链路验证：

```cmd
dist\run-loader.cmd --verify --no-pause
```

直接双击 `dist\run-loader.cmd` 会从脚本所在目录加载唯一正式配置
`generator.properties`，并以 `java -jar` 启动直接 Redis 生成链。正式目标固定为 192.168.10.3:6379 DB15；必要凭据仅填写在该外置配置，不要把凭据写入命令、日志或报告；`--no-pause` 供自动化调用。

正式链路使用内嵌的逐轴联合条件计数模型（左侧连续符号集合、累计 Wild/Scatter 数量及三轴匹配状态）和 `SecureRandom` 自然生成完整 Round，由独立 `ResultUtil` 反推实际模式与倍率；
0 倍结果进入 loss 池，正整数倍率结果进入 win 池。每一批的 `ZADD`、`RPUSH`、`LTRIM` 均在同一个
`MULTI/EXEC` 内提交，每个实际倍率最多保留 `generation.max-members-per-multiplier` 条。

经验模型仅由已保存真实 Round 的训练聚合构建，不包含历史盘面回放；100 个留出样本和 10,000 个新生成局的结果见
`reports/52-Cyber-GO/generation-model-validation.json`。修改生成限制后应先执行上面的 `--verify` 命令，再在隔离 Redis 环境验证写入。

2026-09-08：正式 Loader 实写 LOSS 5000、普通 WIN 5000、免费局 1000，110 批；容量上限仍为每倍率 300。只改写本游戏键，无清库。空池与单 member 消费测试使用本游戏隔离命名空间，测试后没有残留键。见 reports/52-Cyber-GO/loader-refill-20260908.json 和 redis-pool-integration-20260908.json。
