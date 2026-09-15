# Electro Fiesta 协议与规则规格

- 规则版本：`2410-rules-v1`
- 规则哈希：`0c99b115e7f083bea9aea3d198ae64bb6b3f1685c31fccdaf84aa159cf1726ac`
- 牌面：3 列 × 3 行，协议 `res.ps` 使用列主序，共 9 格。
- 中奖模型：5 条固定线；位置依次为 `[1,4,7]`、`[2,5,8]`、`[0,3,6]`、`[0,4,8]`、`[2,4,6]`。
- 符号及三连赔率：`2,3,5,10,20,50`；无 Wild、Scatter、Ways、Cluster。Redis 内部整数倍率使用 `sum(wa.o)`（倍率模式再乘粘性倍率和），因此正奖始终为正整数，0 仅代表无中奖线。
- 总押注：`bg = b × l × 5`。每条中奖 `win = b × l × wa.o`；`tw` 为完整 Round 最终总赢分。
- 付费起点：`gameResult type=1`。特殊后续步骤：`type=2`。
- 普通局：`f=[]`，首响应即完整局；`tw=0` 为普通未中奖，`tw>0` 为普通中奖。
- 重转直到中奖：`f.t=1`。`f.pr` 是 1 起算重转列；其他列保留，后续每次只发该列 3 个新符号；直到 `f.cf=0`。
- 粘性倍率：`f.t=2`。起始牌面为同一符号，后续 `pcn` 是本步新增倍率位置，`pcp` 是 9 格累计倍率；每步只增加新位置，终步 `cf=0`，最终赢分是牌面基础赢分乘累计倍率和。
- 完整 Round：一个 `type=1` 及其全部相邻 `type=2`，以 `f` 为数组/缺失或对象且 `f.cf=0` 结束。Round 激活期间不得更换结果。
- Buy 菜单入口经双遍 UI 和源码证据确认可达但不执行动作，不是独立玩法或采样桶。

## 协议字段

`b/l/bg/cg/sg/eg/o/t/tw/u/rid/oid/small_game_type/res/f` 均来自原响应。`res.ps` 为牌面，`res.ds` 为双卷轴标志（520 局未出现 1），`res.tws` 为页面展示赢分，`res.wa[]` 的 `l/c/o/s` 分别是线号、命中列数、赔率、符号。`f` 在协议边界必须显式解码为 NORMAL、RESPIN_UNTIL_WIN、MULTIPLIER_STICKY 三态，再在响应边界编码回原表示。

## 主要证据

- 原前端：`publish/2410-Electro-Fiesta/assets/main/index.8c4b0.js`（GameConstant、PlayerMgr、SlotData、RecordMgr）。
- 原始响应：`captures/2410-Electro-Fiesta/protocol-sampling/*.jsonl`。
- 完整局索引：`captures/2410-Electro-Fiesta/round-index.jsonl`。
- 统计：`captures/2410-Electro-Fiesta/analysis/sample-analysis.json`。


## 独立无奖标记（2026-09-14）

本次只接普通 NORMAL、单步终止、无奖且不触发特殊结构的完整局，保留 EF1 版本头，编码为 `EF1:#`。解析器调用共享 CandidateFactory 的独立零奖入口，校验真实中奖为零且没有两列相同满列触发结构。

RESPIN_UNTIL_WIN（锁列重转）和 MULTIPLIER_STICKY（粘性倍率）全程保留原编码，包括未派奖的中间步骤。`EF1:#1`、混合特殊步骤与 `#`、尾部空步骤均拒绝。消费端同次解码后复核实际倍率与 Redis 所选倍率桶、普通/特殊池一致。

新解析器兼容已有完整编码。每次领取 Redis member 后只物化一次，校验、后续交付、重试与历史共用该事实；Redis 为空时仍失败。零奖生成最多尝试 5 次，并有 10 个已校验默认盘（41 按 PAN 数量分别保存）。先更新消费端 JAR，再使用新 Loader 写入带标记的数据。Loader JAR 通过 Maven package 交付到本游戏 dist 目录。
