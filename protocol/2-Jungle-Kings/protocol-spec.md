# Jungle Kings raw gid 2 协议与状态机

规则版本：`jk2-reelstrip-dualboard-20260909`  
规则哈希：`3c1e8d7a9b4f2c6e0a5d8b1f7e4c9a2d6b0e3f8c1a7d4b9e2f5c8a0d3b6e1f4`

## 证据边界

权威 HTTP 集是 `captures/2-Jungle-Kings` 的 1152 个付费完整 Round（952 LOSS、200 WIN，History 1152/1152）以及原前端 `spin-v2` / `config-v2` 抓包。Mac 补采 `paid=9612 L=7922 W=1690 S=0` 确认无免费/购买/Scatter。

原前端始终调用：

- `POST /cp/api/v1/auth/verify`
- `POST /cp/api/v1/jungle-kings/config-v2`
- `POST /cp/api/v1/jungle-kings/spin-v2`（form `bet_level,bet_size,ckl,t,gid`，头 `web-token,game-id`）
- `POST /cp/api/v1/jungle-kings/log-list` / `log-view`

1152 条 API 探针使用了 v1 `/spin` + `bl,bs`（单盘 9 格、`PL0014`）。复刻以原前端 v2 为准，v1 单盘结算仍用 History 作 ResultUtil oracle。

## 盘面

两套棋盘：`CB0002`（上轴）与 `CB0003`（下轴）。每套 3 轴 × 3 行窗口，响应 `rand_symbol_key_list` 按激活轴顺序给出若干个长度 9 的数组（列优先，每轴三格为同一停轴符号）。

`ckl`：`getRollStatus(0)` → `CB0002`，`getRollStatus(1)` → `CB0003`，逗号拼接。至少一轴。两轴同时激活时投注加倍，且允许 x2。

赔付线：`PL0011` 上轴、`PL0012` 下轴（v1 单盘历史使用 `PL0014`，前端把它映射到下轴）。三连相同支付符号中奖。赔付 = 配置倍率 × bet_size × bet_level；两轴都中则总和再 ×2。

配置 `spl`：S00011=100、S00012=50、S00013=25、S00014=5。S00014 不在任何 30 停轴带上，禁止生成。S00013 三连从未在 1152 局中奖，生成器拒绝该 WIN。

发牌入口只有付费停轴：从原前端 `RID0011`–`RID0016` 轴带均匀抽一个停点，再复制为三行窗口。无连消、无重转、无免费补牌。

## 完整 Round

一次 `spin-v2` 即完整付费局（一步 Delivery）。

## Generation

One entry `CompleteRoundFactory.generate(random, ckl, size, level, requestedOdd)`.

- Single-open list (上 or 下): `0,50,100` size 3
- Both-open list: displayed vs charged stake `0,25,50,100,150,200` size 6
- Requested odd floors to the greatest list value `<= requested` (30 single → 0, 30 both → 25)
- Combo map then yields page-odd pairs. Single map 3 keys / 3 combos, both 6 keys / 9 combos
- Page-odd `50` is `S00012` triple, `100` is `S00011` triple. Loss is any legal non-confirmed triple (61 reel triples; `S00013` triple is rejected)
- Dual displayed: one line win is pageOdd/2; both-win x2 makes displayed = top + bottom
- Only the combo and reel triple are looked up. Demo does not read Redis.
- Loader JAR prints the odd lists and combo map (`generation.cache=false`). It does not write BetLog.

## History

列表用 `row.tis` 字符串作详情键。`transfer_id` 是不安全 JSON number。详情 `bid` 为 `2-{tis}`，并带 `chessboard_key` 与对应 `rand_symbol_key_list`。
