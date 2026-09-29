# Lucky Dragon generator

正式随机生成、独立复核、Controller 与 Redis Loader 共用 `GameRuleCore`。Spin 实时出牌与 2/1400 相同：请求倍数向下落到合法列表，再从倍数→combo 一把取盘，对不上抛错，不循环重抽。列表没有的倍数（例如 1000）落到不超过它的最大档。未传 `odd` 时 Demo 按 28% 抽正倍数否则 0。联合模型仍用于 Loader 配置校验和离线 `nextJoint` 抽样，不作为 Spin 重试生成。任何历史局、fixture 或采集响应都不会在运行时被读取或轮播。

正式 Windows 交付只使用 `dist/lucky-dragon-redis-loader.jar`、`dist/generator.properties` 和 `dist/start-loader.cmd`；Unix 可从工程根目录执行 `./start-loader.sh`，它自定位到同一 dist 三件套。Loader 从 properties 读取全部 Redis/生成参数；每批在同一 MULTI/EXEC 中执行 ZADD、RPUSH、LTRIM。每条 member 经 `MinimalFactCodec` 编解码和不调用 `GameRuleCore.evaluate` 的 `IndependentRoundVerifier` 复核；0 倍 LOSS 与正整数倍 WIN 都写入对应完整 Round 池。

联合权重来自 1130 个原始响应 Round 中的 1030 个训练样本，只用于本地复刻。原厂 reel strips、原厂概率、RTP 与未观测 H4 轴位置均保持 `UNKNOWN`；完整模型与 10000 局检验见 `reports/42-Lucky-Dragon/generation-model-validation.json`。
