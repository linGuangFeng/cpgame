# Magic Scroll 2 Java Redis 结果生成器

本工程只增量交付游戏 2001007 的正式 Java Redis Loader。Loader 复用当前游戏已经验收的
GameRuleCore、ResultUtil、RoundVerifier 和完整 Round 编码链，不读取 fixtures、captures、
历史响应或 JSONL，也不提供场景强制、局号轮播、固定牌面或正式 seed。

Redis 下游合同采用 1809 的通用结构：普通索引 PerKeyList_%09d、特殊索引
MaryKeyList_%09d，普通列表 BetLog:0%08d:<实际倍率>、特殊列表
MaryLog:%09d:<实际倍率>。每个 r2 member 带 schemaVersion、rulesVersion、rulesHash、
由 SecureRandom 产生并可重现该局的 seed、sourceGameId、redisGameId、唯一 roundKey、
首领 deliveryIndex=0、实际倍率事实和全部有序 Delivery。每批在同一个 MULTI/EXEC 中执行
ZADD + RPUSH + LTRIM；0 倍写入未中奖池 `BetLog:...:000000`，正倍数按
ResultUtil 反推的整数百分倍率写入，每倍率默认保留最新 300 局。

正式配置的模式、符号与 EMPTY 材质权重均来自 3885 个真实完整 Round 起点逐格统计并被
Java 代码实际读取；注释保留计数、分母和百分比。它们只是样本估计，不代表原厂长期概率
或 RTP。XSPLIT 与 XBOMB_WILD
一次生成完整 Round；protocol-handoff 明确禁止实现的 Magic Mining、Lucky Wagon、
Feature Buy 和 xBET 不在本次生成范围。

执行 mvn clean package 会运行规则、分布、配置和隔离 Redis 合同测试，并将 dist
重建为且仅为：

- magic-scroll2-loader.jar（带全部运行依赖）
- generator.properties（无 seed 的正式配置）
- start-loader.cmd（自定位，可双击；自动化可传 --no-pause）

target 仅为构建缓存，不属于交付目录。
