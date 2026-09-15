# 1830 Hotpot — spin 源数据反推

`rulesHash`: `01123dc898992e5e38efb1f58b816c844093dfcf07939e2cefa3153022b61c01`

判奖只使用 `GameRuleCore` / `HotpotResultUtil`。Demo 不在运行时出牌。

## 符号

| ID | 角色 | 备注 |
| --- | --- | --- |
| 1–10 | 赔付符号 | 任意位置满 8 个相同即中；不是 payline / ways |
| 11 | Scatter | 无 Wild。`GamePropID` 只有 Scatter=11。`label_wild` 节点是 Scatter/倍率文案拷贝 |
| 12–23 | 倍率符 | `MultipleTypeList=[2,3,4,5,7,10,15,20,25,30,40,50]` |

起始牌经验频率（PAID_START，1391 盘 / 50076 格，不代表原厂 RNG）：Scatter 约 1.11%；倍率 12–15 合计约 0.86%；16–23 在起始页样本为 0。补牌权重不得套用起始牌。特殊入口可以把 Scatter 权重放大 10 倍以便抽到触发局，但个数必须卡在抓包已出现的上限：同列最多 1 个 Scatter，付费页最多 4 个（52 次触发里 46 次 3 个、6 次 `st=12` 即 4 个），免费起始页最多 3 个。Help 只写 3+ 每多 1 个 +2，没有数字上限；1392 局从未出现同列 2 个或一盘 5+ 个，所以 6 个 Scatter 的盘抓包不支持。

本游戏没有代入 Wild，因此不存在 “WILD 出现概率 / 最多几个” 的实现开关。

## 连消

一次 `POST /cp/single_game.Game/gameResult` 的 `data.props[]` 就是该次旋转全部页。客户端本地走 `PageIndex`，不为下一页再请求。

中奖页：`win_arr[].p/n/odd/wm`，`wm = betSize × level × odd`。幸存者按列下落，空位只从下一页填。

末页必须是空 `win_arr`。若 `props.length>=2` 且末页有 id≥12，把末页倍率求和后乘该次旋转 odd 合计。History 普通中奖末页 `mult` 下标 4/9/29 值为 2+3+3=8，`total_win=27.52`（1376×0.02）。

## Scatter 免费

付费盘 3 个 Scatter → 10 次，每多 1 个 +2。免费中 2 个 → 5 次，每多 1 个 +2。

触发局**可以同时有 8 连奖**，不是 “玛丽触发局必定不中奖”。`twa` 只累计免费 step 的 `total_win`，付费触发赢金不进 `twa`。

完整 Round = 付费起点 + 全部后续 `gameResult` 直到 `frees.st==0`。Redis member 用 `|` 连接各次旋转，Demo 只领一次 member，后续只投影。

## 押注

请求 `bet_gold` 是 Bet Size。`level` 为 1..10。`betLinesConfig=[20]` 是 Base Bet，**不是** paylineCount。应答 `bet_gold` = Size × Level × 20。最小样本：0.02 × 1 × 20 = 0.4。

## 购买

不存在。源码无 buy API/按钮，Help 无购买。活动 `act_id` 不是买免费。生成器和 Demo 都没有 BUY 桶。Scatter 进 `MaryKeyList_000001830`（平台特殊索引名）。

## Redis

- 普通不中：`BetLog:000001830:000000`
- 普通正倍：`PerKeyList_000001830` + `BetLog:000001830:%06d`
- Scatter：`MaryKeyList_000001830` + `MaryLog:000001830:%06d`

member：每页 36 个 radix-24 字符，页拼接，spin 用 `|`。禁止 JSON。
