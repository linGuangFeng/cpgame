# Lucky Panda（gid 41）spin 源数据反推

Demo Controller 不读 fixtures、不当场出牌。完整局 member 由结果引擎预写入 Redis `192.168.10.3:6379` db=15；本文件只说明如何把 `lp1` member 投影成原站 HTTP 字段。判奖只有一份：`GameRuleCore` / `LuckyPandaResultUtil`。

rulesHash：`5f8142ce67bb887905edb625ebfb90128fa872cc0de8e9af0f8017debc2dc50c`。

## 领取合同

每次新的付费局：

1. 安全随机决定中或不中。
2. 不中：`LPOP BetLog:000000041:000000`。
3. 中：`ZRANGE PerKeyList_000000041` 与 `MaryKeyList_000000041` 中已有正倍率，随机一个后再 `LPOP` 对应 list。
4. 一次 LPOP 取走整局。后续连消/免费只投影，不再抽倍率、不再换 member。

购买不是玩法，没有 BUY/mali-via-buy 桶。Scatter 免费完整局在 Mary 池。缓存空或连不上 Redis 直接失败，禁止内存出牌。

## Wild

- 不是触发符号，不进 `wskl`。只替代赔付符号，不替代 Scat。
- Help/赔表没有数字上限。抓包 3382 个 original-http 页：整页最多 3 个 RLE block / 6 格，单列最多 2 block / 5 格。
- 付费起点细胞率 185/45322 ≈ 0.408%；连消补牌 475/42806 ≈ 1.110%。权重不代替上限。

## Scatter / 玛丽

- 触发：付费连消终态 `ss=1` 且 `nfsc=0` 且 Scat RLE block ≥ 4。3 个 block 的 9 个反例均未触发。
- 触发页本身 `wa=0`（因为 `ss=1` 表示当前分段无 ways 奖）。**这不是「玛丽触发局必定不中奖」**：38/38 免费完整局终态 `rwa/(bs*bl)` 都是正整数，来自触发前的付费连消和/或 10 次免费。
- 固定 `fsn=10`，随后 `nfsc=1..10`。语料 0 次 retrigger。
- Scat 列上限 3 block / 4 格，总上限 5 block / 9 格；Help 无数字上限，以抓包更严者为准。超限整局丢弃。

## 字段投影

| 字段 | 来源 | 备注 |
|---|---|---|
| `rskl` | member 页 | 非重算事实 |
| `rpx` | member 页 | 服务器权威；0 视为 x1。不要猜 +2/连消 |
| `wmkl`/`wskl`/`wa` | ResultUtil | spin 为 array-of-arrays；History/`config.last` 为 `{sk,wa,wmk}` |
| `ss` | ResultUtil | 有 ways 奖=0，否则=1 |
| `rwa` | 累加 `wa` | 整局一条累加器，免费也进 `rwa`；`frwa` 恒 0 |
| `fsn`/`nfsc` | 触发规则 | 付费段 `nfsc=0`；触发页 `fsn=10,nfsc=0,ss=1` |
| `ba`（spin） | 协议 | 付费起点 `bs*bl*20`；续局 0 |
| `ba`（history 步） | 抓包 | 该页 `wa` 的字符串 |
| `pb` | 余额 | 未终态停在已扣注余额；终态 `ss=1 && (fsn==0 \|\| nfsc==fsn)` 才加 `rwa` |
| `gfl`/`sfl` | 未重建 | 视觉贴金贴银，不参与 `wa`；投影空数组 |
| `gt` | 抓包 | spin=1；history 列表行=41 |

## 续局

客户端在 `ss=0` 或免费未完时用同一 `bl/bs/gid` 再 POST spin。服务器用会话记住当前 member 和下标。刷新后 `config.last` 带回上一页（object 形 `wmkl`），`IsContent` 在 `ss==0 \|\| fsn!=nfsc` 时为真。

## 未决（不得乱实现）

`U-RPX-INCREMENT`、`U-GFL-SFL-ASSIGNMENT`、`U-FREE-RETRIGGER`、`U-BUY-FEATURE`、`U-ORIGINAL-PROBABILITY` 见 `protocol-handoff.json`。
