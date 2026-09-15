# Lucky Wheel 43 Java Redis Generator

正式入口为 `GameRuleCore` 与 `RedisLoader`。Loader 使用系统 `SecureRandom` 自然生成候选完整局，以 `generator.properties` 的当前游戏权重做拒绝采样，再由独立 `ResultUtil` 反推真实模式和累计倍率。普通未中奖写入 0 倍池；中奖按实际正整数倍率写入。

Redis Key 两套奖池各自再分解锁档。下划线后第一位：`0` 未解锁（bet&lt;5），`1` 特殊1（bet&gt;=5 解锁新玩法）。普通奖 `PerKeyList_%d%08d` / `BetLog:%d%08d:%06d`，玛丽奖 `MaryKeyList_%d%08d` / `MaryLog:%d%08d:%06d`。gid 43 例如：普通未解锁 `PerKeyList_000000043`，普通已解锁 `PerKeyList_100000043`，玛丽已解锁 `MaryKeyList_100000043`。每批在同一 `MULTI/EXEC` 内执行 `ZADD + RPUSH + LTRIM`，每个实际倍率默认保留最新 300 个 member。

`MinimalFactCodec` 只保存当前游戏不可重算事实：下注档案、模式、基础符号、md=1 倍率、md=2 重转符号或 md=3 Lucky Wheel 奖励。`bl<5` 使用两个基础位置且不允许 md=3；`bl>=5` 使用三个基础位置并允许已取证的 md=3。领取后可独立解码并通过 `ResultUtil` 重算结果，不保存 HTTP 响应、余额、派奖或历史 fixture。

配置中的模式与完整 Round 联合状态权重来自两档真实完整局训练分区，均可调整并通过真实留出集校验，但不代表原厂长期概率或 RTP。`md=3` 只在 `bl>=5` 档生成；`ss=0` 仍因证据不足而不生成。

构建：`mvn.cmd clean package`。正式交付只使用 `dist/lucky-wheel-redis-loader.jar`、`dist/generator.properties`、`dist/start-loader.cmd`；自动化运行加 `--no-pause`。
