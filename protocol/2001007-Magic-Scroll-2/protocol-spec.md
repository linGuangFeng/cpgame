# 2001007 — Magic Scroll 2 协议与状态机规格

本文件供后续 Java 实现与独立验收核对。权威机器能力清单为 `game-capabilities.json`，逐行为契约为 `protocol-handoff.json`；两者使用同一 `rulesHash`：

`sha256:ab8f86b20f45615af560b928c2f5bd860ea6abf641d461d2e200a3e4aaed3fcd`

UNKNOWN 行为不得实现或推测。fixtures 仅是协议 oracle，禁止成为运行时结果源。

## 游戏与能力边界

- ID-name：`2001007-Magic-Scroll-2`
- 游戏名：`Magic Scroll 2`
- 语言：`en`、`pt`
- 中奖模型：`WAYS`，最高 46,656 Ways；固定中奖线不适用
- 牌面：6列×6个编码格，列优先；初始活动3行，最多显示6行
- 合法 UI 最低总押注：BRL 0.40，最低倍率1
- 已确认特殊行为：xSplit、xBomb Wild
- 已确认原站 History 当前日期记录与本地持久化投影；`HistoryDate` 原站实测为 HTTP 404
- 本次范围仍禁止新增：Magic Mining/Lucky Wagon 运行时生成、购买 gameType 2–6、xBET gameType 7、Top。Demo 只从 Redis 领取预生成完整局，0 倍写入未中奖池。

## HTTP 与请求顺序

请求使用浏览器 `FormData`，响应为 JSON envelope `{dt,err}`。当前 bundle 和可读抓包未发现应用层 AES/CryptoJS 加解密；HTTPS 仅属于传输层。

1. `POST /web-api/auth/session/v3/verifySession`
   - 请求：`gi=2001007`、`otk`、`l=en|pt`
   - 响应关键字段：`dt.atk`、`dt.gdr`、`dt.cc`、`dt.cs`、`dt.player`、`dt.user_id`
2. `POST {dt.gdr}v2/GameInfo/Get`
   - 请求：上一步 `dt.atk`
   - 响应关键字段：`bl`、`betList`、`multList`、`formation`、`round`
3. `POST {dt.gdr}v2/Spin`
   - 付费起点：`atk`、`bet>0`、`mult>0`、`gameType=1`
   - 同局延续：同一 `atk`，`bet=0`、`mult=0`、`gameType=1`
4. `POST {dt.gdr}v2/History`
   - 活动页面请求：`atk`、`page`、`size`、`time_begin`、`time_end`
   - 当前日期实测：`all_num=2042`，当前页20条；字段为 `psid/created_at/bet/mult/transfer/balance/gameType/formation`
   - `psid` 对应一个付费完整 Round；`formation[]` 依次包含该 Round 全部 Step；`transfer=payout-bet`，LOSS 为 `-0.40`

`HistoryDate` 当前/前一日期均实测 HTTP 404；活动 `CPHistoryTY2` 使用 `History` 的时间范围。`Top` 仍为 UNKNOWN。

## 金额归一化

- `GameInfo.betList`：线值除以1000，观测 `[20,120,800] -> [0.02,0.12,0.8]`
- `GameInfo.multList`：线值除以10000，观测 `10000..100000 -> 1..10`
- 总押注：`normalizedBet × normalizedMult × 20`
- 常量20是基础押注到总押注的转换因子，不是 paylineCount

## Formation 编码

- 基础模式必须是36个逗号分隔十进制整数
- 排列为列优先的6×6
- `symbolId = raw % 100`
- `count = 2 ^ floor(raw / 1000)`
- `blockOrDirt = floor((raw % 1000) / 100)`
- 已知符号：0=xBomb Wild，1=Bonus，2=xSplit，3–12=普通付费符号，99=Empty，999=前端内部 Changing
- 派生 `vaildRow=7` 是第六活动行后的终局计数，不是第七个编码/可见行

## 原始字段与 Delivery 派生字段

已确认的 Spin 原始响应字段只有：

- `bl`
- `formation`
- `free`

以下字段由前端根据原始 formation 和局内状态派生，不得伪装为已确认的原始 wire 字段：

- `end`、`ways`、`vaildRow`
- `winGridLines`、`wildGrids`、`SplitGrids`、`ScatterGrids`
- `gloablMulti`
- `finaltotalwinamount`、`finaltotalwinWays`

## WAYS 计算

- 普通符号3–12从左到右连续匹配，至少3列
- Wild 0 可替代 Bonus 以外的符号
- Ways 数为各匹配列中有效符号数量的乘积
- `baseWin = baseBet × paytableMultiplier × ways`
- `stepWin = baseWin × globalMultiplier`
- 中间断列后停止该组合；每种下注方式只支付最高赢奖
- 最大 Ways 为46,656，`paylineCount=NOT_APPLICABLE`

奖表数值以 `game-capabilities.json.winModelDetails.paytableBySymbolId` 和规则页/前端 `lineMulti` 交叉证据为准。

## Paid Round / Delivery / Step 状态机

状态：`IDLE -> PAID_STEP_PENDING -> DELIVERY_ACTIVE -> CONTINUATION_PENDING -> ... -> TERMINAL -> IDLE`

状态字段至少包含：`atk`、`paidBet`、`mult`、`gameType`、有序原始 Step、有序 Delivery、活动行计数、全局倍率、累计赢额、终局余额和派生终止标志。

转换规则：

1. 仅在没有活动 Round 时，`bet>0 && mult>0` 创建一个付费 Round。
2. 收到 Spin `dt` 后追加原始 Step，解码 formation 并生成 Delivery。
3. 已确认的 WAYS、xSplit 或 xBomb 条件需要延续时，发送一次 `bet=0,mult=0,gameType=1` 请求。
4. 延续响应必须按顺序附着在原付费 Round，不能计为新 Spin 或领取新 member。
5. 派生 `end=true` 后保存终局余额和累计赢额；禁止再延续。

可解析的相邻响应 oracle：

- Round 29，请求562–567：一笔BRL 0.40付费起点加五个延续 Step；六个 formation 与 `end=[false,false,false,false,false,true]` 均保存在 fixture 04。
- 请求562→563：xSplit 激活到双倍计数符号牌面。
- 请求564→566：xBomb Wild、倍率1→2及累计赢额0.96→1.28→1.48。
- 补抓 Round 18，Delivery 0–3：三次普通中奖消除均逐格验证；中奖格删除，保留 raw 值和列内顺序，向更高行号压实，仅补有效行空位，外部 dirt/block 不变。
- 补抓 Round 48：xSplit 同行逐格转换；补抓 Round 9：xBomb 删除6格、保留移动13格、补位11格。

补抓 Round 7 已保存 Magic Mining 的相邻 formation、删除位置、保留移动和补位；Round 24 保存 Lucky Wagon 23个有序 Delivery。它们解决源证据缺口，但按本次增量范围仍不新增对应本地生成模式。

## 实现门控

允许的运行相关行为：Session Init、传输 codec、押注归一化、牌面 codec、WAYS 奖表、基础完整 Round、普通独立单步 LOSS、xSplit、xBomb Wild、持久化 History，以及与原站一致的 HistoryDate 404。

普通实时 LOSS 仅限 `gameType=1`、无活动 Round 的基础付费单步局；独立 ResultUtil 必须确认没有 WAYS、xSplit、xBomb、Magic Mining、Bonus/free 延续触发且派生 `end=true`。

所有 `protocol-handoff.json` 中 `status=UNKNOWN` 的行为均不得生成、模拟或从其他游戏继承。Magic Mining 的规则/代码描述可用于 LOSS 排除检查，但不得用于生成该模式或声称其运行时转换已确认。

## 终止与证据原则

- `spin-index.jsonl` 每行对应一个真实付费 Round 起点；29行对应29局，141个 Spin API 请求包含所有延续 Step。
- 本次合法最低押注覆盖为4局；25局低于 UI 最低额，仅作审计，不作为合法玩法确认。
- 已停止 Spin，禁止为寻找稀有模式追加局数。
- 原始响应只能保存在 captures/fixtures；fixtures 不能进入运行时生成路径。
