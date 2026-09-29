# 2470 Java generator and Redis Loader

Run dist/start-loader.cmd on Windows; `--no-pause` suppresses the final prompt. Other platforms run `java -jar dist/loader.jar --config dist/generator.properties`. A normal run generates and writes complete rounds with SecureRandom, ZADD, RPUSH and LTRIM.

All implementation source is shared with ../../server-api/2470-Lucky-Night-Market/src. The Loader does not package or load captured boards, captured columns, captured winning combinations, or captured multiplier triples. Capture statistics provide only the default per-symbol/per-multiplier weights and scenario-frequency reference; Demo selection settings are absent from this configuration.

For validation without writing Redis: add `--validate --count 10000 --facts generated-facts.jsonl`. Validation and normal loading use the same one-candidate-per-attempt algorithm and the same configured complete-round payout ranges; validation only disables Redis writes.

## 批次牌面权重

`generation.batch-size` 是生成批次大小，也是牌面权重轮换边界。第一批使用全部基础权重，后续批次依次只强化百搭牌、高牌一、高牌二、低牌一、低牌二、低牌三、低牌四；全部牌轮询完成后，下一批重新使用全部基础权重，然后继续循环。

配置中的七个 `symbol.weight.*` 基础权重由采集模型的 3,705 个步骤、33,345 个牌位汇总得到，但只作为每个牌位的随机概率。普通、玛丽和转盘的九个牌位都按当前逐牌权重随机，随后由 Java 核心结算并自然分类；不会从样本整列、整盘、中奖组合或倍率三元组中抽取结果，也不会把样本极值当作 WILD、列、中奖步数或倍率的规则上限。每张牌还有独立的 `symbol.weight.*_boost_mul`；默认值 `3` 表示当前强化牌使用“该牌基础权重 × 3”，不是把权重直接设为 3。批次结束时，本批已经生成的有效结果立即写入各自倍率缓存；无效候选占用一次尝试且不会补抽，单个缓存不需要凑满 `generation.batch-size` 条，最后不足一个完整批次的结果也会写入。

管理页缓存分组使用标准键族：普通为 `PerKeyList_008002470`，幸运特色为 `MaryKeyList_008002470`，转盘为 `PerKeyList_108002470`。对应转盘结果列表为 `BetLog:108002470:%06d`，不使用管理页无法识别的自造键族。


## 独立零奖标记（2026-09-15）

普通独立零奖完整局编码为 `LNM1|L|#`。Lucky Feature 保留 F 模式头、全部 8 个步骤及原有分号分隔，只将第 2..8 步中实际派奖为零、没有 Wheel 奖励的独立步骤替换为 `#`。第 1 步承担开启特色的状态，始终保留完整编码；Lucky Wheel 全部保留。数字 `0`、`#1`、特色首步标记或不足/超过 8 步的特色局均拒绝。

特色横向三个倍率的和只作用于当前步，不累加到后一步，不需要 `#N`。标记解码所需的零奖占位步骤由独立零奖支持生成，只用于还原等价的零奖事实；正式 Loader 不调用它来指定输赢、补结果或构造入库牌面。

压缩前验证完整局；回读验证模式、步数、每步真实派奖及所有保留步骤不变。因此特色 `st=7..0`、`tt=8`、`f.twa`、首步扣款和后续免费均保持。消费端领取后复用同一份解码事实；幂等请求、恢复房间和 History 保留同一盘面。Redis 为空仍失败。

`RoundCodec.encode` 默认输出标记，`encodeFull` 保留旧完整格式；新解码器同时兼容旧格式和标记格式。GeneratorMain 与 PoolInstaller 的回读检查改为语义一致性，不再要求被标记的零奖盘面完全相同。先更新消费端 `dist/controller.jar`，再用 `dist/loader.jar` 写入新 member。

保持现有奖池筛选配置：当前正式 `range.normal-min=1` 不生成普通零奖整局；需要写入普通 `LNM1|L|#` 时，普通范围须包含 0（例如 `range.normal-min=0`）。特色按完整局总奖筛选，内部零奖步骤不受普通池最小倍率影响。本次没有改动正式配置值、Redis 数据或运行中服务。

两份 JAR 共用 server-api 下的源码，Maven package 会输出到各自 dist。验证结果见 `reports/2470-Lucky-Night-Market/independent-loss-marker/validation.json`。
