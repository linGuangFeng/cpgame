# Samba Sensation 2290 完整局结果引擎

唯一规则核心是 `com.cpgame.sambasensation.core.GameRuleCore`，规则版本与哈希分别为
`2290-protocol-v3-template39-cross-round-state-v2`、`46ef48cf0977a2785f257825d1e499b8049d293900e2a10d4a0dec7ad1ba2205`。
服务端工程通过 Maven 坐标 `com.cpgame.sambasensation:samba-sensation-rule-engine:1.0.0` 依赖它，禁止复制第二份判奖。

`dist` 是唯一 Loader 交付目录，只含可执行 JAR、`generator.properties` 和自定位 `.cmd`。
双击脚本会连接 `192.168.10.3:6379 db=15`；命令行自动化可传 `--no-pause`。连接失败只检查网络、密码和配置，程序不会改盘面或退回本地结果。

每个 Redis member 是 `SS2` 开头的当前游戏专用变长 ASCII，包含牌面及收集增量事实。普通池按 `BetLog:0/1/2...` 对应一/二/三层，索引同样分层；Mary 池保持统一。中奖线、倍率、余额、会话、时间戳和 HTTP JSON 壳都不保存；读取时由 `ResultUtil` 重新计算。

`RuntimeSpinGenerator` 在 Demo 中只生成 0 倍页、触发首屏、Scatter 和金币增量，普通正奖必须来自缓存。向缓存牌面增加 Scatter 时只做有限次校验，保持原奖励金额。符号权重、金币出现/位置/增量权重以及重试上限由调用方传入。金币满盘事实保存本局真实增量，因此可从两个空槽在同一付费局补满；旧缓存中增量为 0 的满盘 member 仍保持兼容。`NormalFloorMigration` 可将旧混层普通池的二/三层 member 原子搬到对应前缀，保留原奖励和 member 数量，可重复执行。

构建：`mvn clean test package`。正式 Loader 不接受 seed 或倍率筛选参数。
