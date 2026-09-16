# Lucky Panda（gid 41）协议规范

本规范只描述当前游戏抓包、原包前端和赔表截图已经建立的行为。`protocol/41-Lucky-Panda/_legacy-old-replica` 只作历史参考，不是本次已验收交接。

rulesHash：`5f8142ce67bb887905edb625ebfb90128fa872cc0de8e9af0f8017debc2dc50c`（SHA-256 of `rules-core-canonical.json`）。

## 怎么结束一局

一次付费 `POST /cp/api/v1/lucky-panda/spin` 开始一局。客户端在 `ss=0` 时用同一 `bl/bs/gid` 继续请求，服务器用会话记住当前 Round。

**完整局终止谓词**：`ss==1 AND (fsn==0 OR nfsc==fsn)`。前端 `isRealEnd = (ss==1 && fsn==nfsc)`。1333 个完整局全部满足。一个付费起点及其全部连消、免费 Spin 只计一局。

## 未中奖 / 中奖 / 特殊 / 购买

| 类别 | 本游戏定义 | 样本 | 目标 | 状态 |
|---|---|---|---|---|
| 普通不中奖 | 付费局 `rwa=0` 且 `fsn=0`，通常一步 `ss=1` | 1095 | 200 | COVERED |
| 普通中奖 | `rwa>0` 且从未进入免费（`fsn` 保持 0）。本游戏中奖后必连消，单步中奖=0 | 200 | 200 | COVERED |
| 特殊 / mali | Scatter 免费：付费连消终态至少 4 个 Scat RLE 块，`fsn=10`，随后 `nfsc=1..10` | 38 | 30 | COVERED |
| 购买 | 十语菜单无购买；前端无 Buy 模块；1333 次 spin 只有 `bl/bs/gid` | 0 | 不适用 | NOT_A_PLAY_MODE |

购买不是 mali 的入口，也不是独立互斥玩法。连消不是独立 mali，它是普通中奖（以及免费 Spin）的继续方式；Redis 普通池写入含全部连消页的完整局，免费完整局写入特殊池。

旧 round-index 把 `classification=WIN` 中的 Scatter 免费算进普通中奖。本节点按互斥分桶拆出免费，并在 165/200 时用账号 abc223 定向补抓普通中奖，追加到现有 jsonl，没有清空 1093 局语料。

## 传输

- Origin：`https://api.omgapibra.com`
- `POST application/x-www-form-urlencoded`；`encodeURIComponent` 后 `%20`→`+`
- 逻辑头：`web-token`→`t`，`game-id`→`gid`
- Auth：`/cp/api/v1/auth/verify`
- Config：`/cp/api/v1/lucky-panda/config`
- Spin：`/cp/api/v1/lucky-panda/spin` 字段 `bl,bs,t,gid`（活动免费时前端可能加 `ec`，语料未出现）
- History 列表：`/cp/api/v1/lucky-panda/log-list`
- History 详情：`/cp/api/v1/lucky-panda/log-view` 字段 `transfer_id,t,gid`
- Ping：`/cp/api/v1/ping`
- 成功包：`{code:200, info, data}` 明文 JSON，无业务加密

## 盘面与赔付

- 6 轴行数 `[5,6,6,6,6,5]`，`rskl` 为 `<高度><符号>` RLE，位置 `reel*10+row`
- 前端 `ColumnCount:5` 是最后一轴下标；`payline_count=20` 只用于 `ba=bs*bl*20`，**不是中奖线数**
- 中奖模型 ALL WAYS，从左连续 3–6 轴；Wild 代替赔付符号，不代替 Scat
- 赔表见 `rules-core-canonical.json`；2883 个 delivery 的 `wa` 独立复算零差异
- `rpx=0` 视为 x1；正值为 2–26 的偶数，由服务器给出，不要猜 +2/连消

## 连消与免费

- 中奖格消除、上方落下、空位补牌；`rwa` 累加 `wa`；继续请求直到当前分段 `ss=1`
- Scatter 在**付费连消终态**（`ss=1,nfsc=0`）用 **≥4 个 Scat 块** 触发
- 固定 10 次免费；`frwa` 在 3382 个 original-http delivery 上恒为 0，免费赢分进入 `rwa`
- 语料未建立免费 retrigger

## History

每种已确认结果各有列表+详情即可。现有 `history-1to1.jsonl` 已 1333 条 1:1，足够对字段。`gm=null`、`fe=0` 是常态。`fsl` 只出现在 38 条免费局，普通局缺 `fsl` 是常态。不要为每局 spin 再拉全量 history，也不要用 his 去凑 200/200/30。

## Redis（平台下游，不是原厂字段）

- `192.168.10.3:6379` db=15
- 普通：`PerKeyList_000000041` + `BetLog:000000041:{六位整数倍率}`
- 特殊：`MaryKeyList_000000041` + `MaryLog:000000041:{六位整数倍率}`
- 整数倍率 = 终态 `rwa / (bs * bl)`；未中奖写 `000000`，不要关 0 倍写入
- member：无头紧凑 ASCII 完整局（仅新格式），禁止整份 JSON；只允许已缓存的独立无奖标记按规则物化

## 明确未知

原厂轴带/概率/RTP、`rpx` 递推闭式、`gfl/sfl` 贴金贴银规则、少于 3 轴上的 4 个 Scat 块是否触发。


## 独立无奖标记（2026-09-14）

普通及免费模式中，整段只有一页、真实无奖且不触发/追加免费的 Spin，可写成 `#N`。N 表示 PAN 的 RLE 块数，不是中奖倍数，也不是最终累计倍率。例如 `压缩牌面|#0|#1|#2`（这里只展示局部；完整 member 仍需包含所有免费 Spin）。

解析器按 N 生成真实零奖盘面，沿用 LuckyPandaRpxTracker 从前一页状态计算 rpx：普通局取 max；首次免费且 rpx=0 时为 2+2N，其余新免费 Spin 累加 2N。编码前核对该步原 rpx 是否可精确重建，不一致则保留原编码。`#0` 不等于强制 rpx=0。数字范围 0..29；`#`、`#01`、`#30` 等不接受。

仅整个普通/免费 Spin 段允许标记，连消链（含无奖收尾）、免费触发和追加次数盘均保留。原页内的 `#G` / `#S` 框标记仍按原格式解释。

解析器只接受新的无头紧凑编码。每次领取 Redis member 后只物化一次，校验、后续交付、重试与历史共用该事实；Redis 为空时仍失败。零奖生成最多尝试 5 次，并有 10 个已校验默认盘（41 按 PAN 数量分别保存）。先更新消费端 JAR，再使用新 Loader 写入带标记的数据。Loader JAR 通过 Maven package 交付到本游戏 dist 目录。

## 缓存紧凑编码（2026-09-15）

新 Loader 不再写 `lp1|bs=...|bl=...|P=` 和 `F=`。完整局第一段为普通 Spin，其后按顺序为免费 Spin；`|` 分隔 Spin，`;` 分隔同一 Spin 的连消页。普通独立无奖整局可仅为 `#N`。实际派奖使用请求的 bs/bl；无头缓存解析时内部使用 bs=1、bl=1 校验整数倍率，不存下注参数。

符号映射是生成器与消费端共享代码 `CompactBoardCodec` 中的固定 Map，不加入任何配置表：

| 协议符号 | Pan | H1 | H2 | H3 | H4 | H5 | A | K | Q | J | T | Wild | Scat |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 缓存字符 | P | F | G | H | I | L | A | K | Q | J | T | W | S |

高度 1 省略，高度 2～4 保留数字前缀，牌块之间没有逗号。例如 `1H3,2H3,1Scat,3K` → `H2HS3K`。相邻同符号牌块不合并，`HH` 是两个高度 1 的牌块，`2H` 是一个高度 2 的牌块，必须区分。解析后完整还原前端 rskl；`@rpx`、`#G`/`#S` 金银框仍保留。独立无奖 `#N` 的范围、倍率跟踪以及禁止替换连消页的限制不变。

已移除旧格式编解码，只接受固定 Map 的无头紧凑牌面及 `#N`；`lp1` 头、`P=`/`F=` 和逗号分隔的旧牌面均拒绝。更新消费端后须使用新版 Loader 重建缓存；打包不会自动清理或转换现有 Redis 数据。

## Demo Redis 查询（2026-09-15）

不再全量遍历倍率桶或批量查全部 LLEN。未中奖直接读取普通零倍桶。中奖先随机选择普通/特殊池，用 ZREVRANGE index 0 0 读取该池最大倍率，以此为上限在代码中随机目标 1..max；再执行 ZREVRANGEBYSCORE index target 0 LIMIT 0 1。相当于 reverseRangeByScore(key, 0, target, 0, 1)。若选中桶为空，用排他上界 (ratio 继续向下，始终不超过目标；首选池无可用项时尝试另一池。零倍回退和空缓存明确失败规则保留。

桶内使用 LLEN + 随机下标 LINDEX 读取完整局，不再 LPOP 消耗 demo 缓存。只物化一次，后续连消、免费、重试和历史共用该结果。正常命中仅查选中桶，与桶总数无关。此前 pipeline 修复只是中间版本，本流程替代它。
