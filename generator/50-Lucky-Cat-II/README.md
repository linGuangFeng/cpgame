# Lucky Cat II Java Redis 结果生成器

本工程只实现 `rulesHash=4fcb4da38b457ee89e699b66ae9e258af2c73a622fc324202f20b7d7957c95f6` 对应的 Lucky Cat II 规则。

正式 Loader、Controller restore 与测试共用同一套 `GameRuleCore`。完整局从训练抓包的联合状态 kernel 抽样（可左右轴对换），不逐格独立随机，也不拼接未中奖盘。`ResultUtil` 独立反推牌线、Lucky Respin、Wheel、奖金和整数倍率。

Redis member 是极简 US-ASCII，不是 JSON。0 倍未中奖写入 `BetLog`。Lucky Respin 与 Multiplier Wheel 写入 `MaryLog`。

发行目录 `dist` 只保留：`lucky-cat-ii-loader.jar`、`generator.properties`、`run-loader.cmd`。双击 CMD 启动；自动化加 `--no-pause`。
