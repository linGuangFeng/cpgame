# 2470 Java generator and Redis Loader

Run dist/start-loader.cmd on Windows; `--no-pause` suppresses the final prompt. Other platforms run `java -jar dist/loader.jar --config dist/generator.properties`. A normal run generates and writes complete rounds with SecureRandom, ZADD, RPUSH and LTRIM.

All implementation source is shared with ../../server-api/2470-Lucky-Night-Market/src. The embedded model contains aggregate three-symbol reel-vector and adjacent-reel transition counts, not full captured boards or complete-round fixtures. Original responses are used only for offline modeling and independent regression. Formal mode weights follow the original sample; Demo selection settings are absent from this configuration.

For validation without writing Redis: add `--validate --count 10000 --facts generated-facts.jsonl`. This evaluates the unfiltered fitted model. Normal loading additionally enforces the documented integer bucket payout ranges on complete rounds.


## 独立零奖标记（2026-09-15）

普通独立零奖完整局编码为 `LNM1|L|#`。Lucky Feature 保留 F 模式头、全部 8 个步骤及原有分号分隔，只将第 2..8 步中实际派奖为零、没有 Wheel 奖励的独立步骤替换为 `#`。第 1 步承担开启特色的状态，始终保留完整编码；Lucky Wheel 全部保留。数字 `0`、`#1`、特色首步标记或不足/超过 8 步的特色局均拒绝。

特色横向三个倍率的和只作用于当前步，不累加到后一步，不需要 `#N`。解析器按普通/featureLater 入口生成真实零奖步骤；特色入口沿用内嵌拟合模型的条件列向量和联合倍率三元组，遵守位置符号、Wild 和票券上限。生成器初始化时准备 10 个验证通过的备用步骤，运行时最多 5 个新候选，不读取历史响应、不强改符号或奖金字段。

压缩前验证完整局；回读验证模式、步数、每步真实派奖及所有保留步骤不变。因此特色 `st=7..0`、`tt=8`、`f.twa`、首步扣款和后续免费均保持。消费端领取后复用同一份解码事实；幂等请求、恢复房间和 History 保留同一盘面。Redis 为空仍失败。

`RoundCodec.encode` 默认输出标记，`encodeFull` 保留旧完整格式；新解码器同时兼容旧格式和标记格式。GeneratorMain 与 PoolInstaller 的回读检查改为语义一致性，不再要求被标记的零奖盘面完全相同。先更新消费端 `dist/controller.jar`，再用 `dist/loader.jar` 写入新 member。

保持现有奖池筛选配置：当前正式 `range.normal-min=1` 不生成普通零奖整局；需要写入普通 `LNM1|L|#` 时，普通范围须包含 0（例如 `range.normal-min=0`）。特色按完整局总奖筛选，内部零奖步骤不受普通池最小倍率影响。本次没有改动正式配置值、Redis 数据或运行中服务。

两份 JAR 共用 server-api 下的源码，Maven package 会输出到各自 dist。验证结果见 `reports/2470-Lucky-Night-Market/independent-loss-marker/validation.json`。
