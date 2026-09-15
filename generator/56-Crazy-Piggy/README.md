# Crazy Piggy Java Redis 完整局 Loader

本工程只实现 `rulesHash=7edd2945da54923875932aa9feb1bfd657c9c67cca832637060f8419573480ab` 已确认的 3×3、五条固定赔付线、普通输赢和 Booster Wheel。

生成模型从训练区间 1–1292 的真实完整局拟合联合 kernel，一次抽取整个牌面及整个轮盘序列，只做保持五条赔付线集合不变的上下镜像；不逐格拼牌、不读取运行时 fixture。`GameRuleCore → RoundFactory → RoundVerifier/ResultUtil → MinimalRoundFactCodec → RedisLoader` 是唯一正式链路。

Redis 使用 `192.168.10.3:6379` DB 15。普通索引为 `PerKeyList_%09d`，特殊索引为 `MaryKeyList_%09d`；普通列表为 `BetLog:0%08d:%06d`，特殊列表为 `MaryLog:%09d:%06d`。每个 member 是一个以 `CP56A1` 开头的极简 US-ASCII 完整 Round；0 倍未中奖和正整数倍都按实际倍率分桶，每倍率最多保留最新 300 局。

正式配置生成 3,000 个未中奖、6,000 个普通中奖和 1,000 个特殊局，共 10,000 局，无 seed 属性。交付目录仅含：

- `crazy-piggy-loader.jar`：包含全部依赖；
- `generator.properties`：正式 Redis 与生成数量配置；
- `run-loader.cmd`：可双击运行，自动化可传 `--no-pause`。

构建与测试：`mvn clean package`。运行：`dist\run-loader.cmd`。
