# Crazy Piggy Java Redis 完整局 Loader

本工程只实现 `rulesHash=7edd2945da54923875932aa9feb1bfd657c9c67cca832637060f8419573480ab` 已确认的 3×3、五条固定赔付线、普通输赢和 Booster Wheel。

正式 Loader 按倍率桶穷举覆盖：直出枚举全部 8^9 中奖牌面（满屏 H2–H7 走轮盘），轮盘枚举可达倍率结构，全部写入普通 `PerKeyList` / `BetLog`。`GameRuleCore → RoundFactory → RoundVerifier/ResultUtil → MinimalRoundFactCodec → RedisLoader` 是唯一正式链路。0 倍走独立 LOSS 构造器。生成次数配置不参与穷举。

Redis 连接见 `dist/generator.properties`。普通索引为 `PerKeyList_%09d`，普通列表为 `BetLog:0%08d:%06d`。每个 member 是一个以 `CP2` 开头的极简 US-ASCII 完整 Round；0 倍未中奖和正整数倍都按实际倍率分桶，每倍率最多保留最新 300 局。最小／最大倍率与每档条数仍然生效。

交付目录仅含：

- `crazy-piggy-loader.jar`：包含全部依赖；
- `generator.properties`：正式 Redis 与穷举范围配置；
- `run-loader.cmd`：可双击运行，自动化可传 `--no-pause`。

构建与测试：`mvn clean package`。运行：`dist\run-loader.cmd`。
