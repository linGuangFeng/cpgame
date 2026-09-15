# Rio Carnival Java Redis 结果生成器

本工程是游戏 45 的正式完整 Round 生成链路。`GameRuleCore` 使用 `SecureRandom` 和由 1,306 个训练 Round 聚合出的联合特殊符号形状/条件卷轴窗口模型生成牌面；`CompleteRoundFactory` 从一个付费起点一次生成到所有免费与重触发结束；`ResultUtil`/`RoundVerifier` 独立反推 25 条固定线、Wild、奖表、累计奖金、免费进度和终止边界。运行时不读取 fixtures、抓包或历史响应，也没有强制场景、固定牌面或按次数轮播。

原厂没有公开 RNG 或 RTP 权重，交付不作原厂 RTP 声明。聚合模型固定封装在 JAR 中；`generator.properties` 只含 Redis 与数量参数，没有 seed 或可改写规则的权重。测试代码才可使用确定性随机源复现故障。

Loader 对每个自然生成的 Round 执行以下流程：

1. 使用同一个 `GameRuleCore` 生成完整 Round。
2. 使用独立 `RoundVerifier` 和 `ResultUtil` 复核，经过 `RoundFactsCodec` 往返后再复核。
3. 按 `总派彩/总投注×100` 得到实际整数倍率索引（例如 125 表示 1.25x）；普通 0 倍进入 normal:0；免费 0 倍进入 special:0，运行时均只由 LOSS 分支领取。
4. 普通（含 0 倍）写入 `Rio45:v3:normal:ratios` / `Rio45:v3:normal:<ratio>`；完整免费 Round 写入 `Rio45:v3:special:ratios` / `Rio45:v3:special:<ratio>`。
5. 每批在同一个 Redis `MULTI/EXEC` 中执行每个 member 的 `ZADD + RPUSH + LTRIM`，默认每个实际倍率 LIST 保留最新 3000 局。

构建：

```powershell
mvn.cmd clean test package
```

构建后 `dist` 严格只有三个交付文件：带全部依赖的 `rio-carnival-loader.jar`、正式 `generator.properties` 和自定位 `run-loader.cmd`。双击 CMD 即可运行；自动化环境可执行：

```powershell
dist\run-loader.cmd --no-pause
```

生产默认生成 2,000 个未中奖、2,000 个普通中奖和 150 个特殊完整 Round；Redis 最终容量由每个倍率 Key 的 `LTRIM` 上限控制。
