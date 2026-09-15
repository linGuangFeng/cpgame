# 2060 Club Goddess Java Redis 结果生成器

本工程交付当前游戏的正式 Java Redis Loader。生成链路复用唯一 `GameRuleCore`、`RoundFactory`、独立 `ResultUtil` 与 `RoundVerifier`，规则哈希为 `sha256:dab637854236c54a8d269e6f6ae42c9aa7e14c2586a19e4e96d67bc254ee093e`。运行时不读取 fixtures、抓包、历史响应或 JSONL，不存在固定牌面或按次数轮播。

正式 Loader 从 `generator.properties` 逐项读取当前游戏符号权重并使用系统 `SecureRandom` 自然生成。原厂权重未知，本地权重不代表原厂 RTP。首尾列禁止 Wild，中间三列允许 Wild；三枚及以上 Scatter 生成 12/15/20 次完整免费局，Wild 每累计 3 枚使倍率增加 2，最高 20。

每个 member 是一局完整 Round 的紧凑 ASCII 最小事实，包含规则哈希、`roundKey` 和所有 5×3 牌面。同一 Round 的所有步骤从同一 member 投影。编码前后均由独立 `ResultUtil` 重算；0 倍写入普通未中奖桶，正整数倍率写入对应 `BetLog` 或 `MaryLog`。每批在同一个 `MULTI/EXEC` 内执行 `ZADD + RPUSH + LTRIM`。

## 构建与启动

```powershell
mvn -o clean test package
```

`dist` 必须且只包含：

- `club-goddess-redis-loader.jar`：带全部依赖的可执行 Java JAR
- `generator.properties`：无 seed 的正式配置
- `start-redis-loader.cmd`：自定位双击脚本

双击脚本即可启动；自动化调用可在任意工作目录执行：

```bat
start-redis-loader.cmd --no-pause
```

`--no-pause` 只关闭脚本结束时的窗口暂停，不会传入 Java Loader。

## 特殊模式证据

保存归档中有 21 个完整免费 Round，确认 type=1 触发、相邻 type=2、st 倒数、twa 累计和 Wild 倍率进阶；目标 30 未达到，因此报告保留 `SAMPLE_INSUFFICIENT`。
