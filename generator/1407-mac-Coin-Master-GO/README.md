# 1407 Coin Master GO Redis Loader

本工程只编译 `server-api/1407-mac-Coin-Master-GO` 中的唯一 `GameRuleCore`、`CoinMasterResultUtil` 与 Round 状态模型。正式入口使用系统 `SecureRandom` 和配置中的十种当前游戏本地复刻权重自然生成；不读取 fixtures、抓包、历史响应或 JSONL，也不接受 seed、强制场景和关闭 Redis 的参数。`redis.game-id` 默认保持 raw gameId 1407；`mac` 只用于目录隔离。

规则版本为 `1407-mac-evidence-v18.4`，rulesHash 为 `08727f7e5a890dcd1b3cf5ae9559474ef9ea98c6e9a09ac34b7d53598ba1662f`。`generator.properties` 的符号权重来自 501 个真实付费起点的 12525 格逐格计数；起始牌银/金材质权重来自第2-4轴 7310 张合资格牌的计数（银5737、金1573）。银牌在规则模型中是显式状态，不是缺省分支；679 组真实相邻级联中的 2429 张合资格补牌全部为银牌，所以补牌规则按源数据固定为银牌，不套用起始牌材质概率。配置逐项写明样本分母和大致百分比；这些是本地复刻的样本估计，不宣称等于原厂长期概率或 RTP。

每个 member 是一局从付费开始到所有连消、免费 Spin、重触发合法结束的最小事实包。`RoundResultUtil` 反推实际模式和倍率；0 倍由规则引擎自然算出，按实际倍率 0 写入 Redis，独立零倍 Spin 用 `#` 表示，配置文件不提供0倍概率或写入开关。普通索引使用 `PerKeyList`、特殊索引使用 `MaryKeyList`，倍率 LIST 使用 `BetLog`/`MaryLog`。每批的 `ZADD+RPUSH+LTRIM` 在同一 `MULTI/EXEC` 中执行，每个实际倍率默认保留最新 300 条。

构建与定向测试：

```powershell
mvn.cmd test
mvn.cmd package
```

正式交付目录 `dist` 必须且只能包含：

- `coin-master-go-redis-loader.jar`：含全部依赖的 Java Loader JAR
- `generator.properties`：无 seed 的正式可调配置
- `start-redis-loader.cmd`：自定位双击脚本，自动化可传 `--no-pause`

## 独立无奖标记（2026-09-14）

ASCII `|` 分隔完整 Spin（Delivery）。整个片段 `#` 表示调用共享零倍生成器实时生成真实无奖盘；普通独立零倍整局可仅为 `#`。只压缩单步、不中奖、无免费触发或重触发、与前后 Spin 无牌面继承和状态增量的片段。多步连消完整保留，含无奖结束步、金牌转 WILD 及金银材质继承；免费触发/重触发盘不得压缩。免费计数、逐 Spin 倍率及累计奖金仍按原流程重建。

MinimalFactCodec 保持旧 40 字符每步及旧 JSON 编码兼容；数字 `0` 不作为特殊标记。`decode` 物化一次后可用 `rebuild(facts)` 复用，后续投影、幂等和 History 固定同一局牌面，不重复随机解码。`encodeFull` 用于精确原盘回归，Redis 正式写入使用 `encode`。写入前先复核原始 Round，再验证压缩后的真实模式和倍率，零倍不受正中奖最小倍率筛选影响。先升级实际消费端解析器再使用新 Loader；旧 Redis 数据不强制迁移。
