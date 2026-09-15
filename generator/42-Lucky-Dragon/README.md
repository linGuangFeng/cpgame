# Lucky Dragon generator

正式随机生成、独立复核、Controller 与 Redis Loader 共用 `GameRuleCore`。v37 模型以 1030 个训练 Round 的“有序三轴+rpx”完整 INITIAL 状态联合频数抽样，不独立相乘单格边际，也不改写符号拼 LOSS；另留 100 个不重叠 REAL_PROVIDER_HOLDOUT 只用于独立验收。任何历史局、fixture 或采集响应都不会在运行时被读取或轮播。

正式 Windows 交付只使用 `dist/lucky-dragon-redis-loader.jar`、`dist/generator.properties` 和 `dist/start-loader.cmd`；Unix 可从工程根目录执行 `./start-loader.sh`，它自定位到同一 dist 三件套。Loader 从 properties 读取全部 Redis/生成参数；每批在同一 MULTI/EXEC 中执行 ZADD、RPUSH、LTRIM。每条 member 经 `MinimalFactCodec` 编解码和不调用 `GameRuleCore.evaluate` 的 `IndependentRoundVerifier` 复核；0 倍 LOSS 与正整数倍 WIN 都写入对应完整 Round 池。

联合权重来自 1130 个原始响应 Round 中的 1030 个训练样本，只用于本地复刻。原厂 reel strips、原厂概率、RTP 与未观测 H4 轴位置均保持 `UNKNOWN`；完整模型与 10000 局检验见 `reports/42-Lucky-Dragon/generation-model-validation.json`。
