# 1830 Hotpot protocol specification

`rulesHash`: `01123dc898992e5e38efb1f58b816c844093dfcf07939e2cefa3153022b61c01`

All names below are from game 1830. Do not default to 1809 Freedom Day or 1407 Coin Master GO fields or mode names.

## Identity

- gid `1830`, directory `1830-Hotpot`, display name Hotpot
- lobby iframe: `static.cpgame.io/fixed/?gid=1830&l=pt&language=pt-br&ai=luck_single_10229&sip=api.omgapibra.com`
- asset language for that alias is `pt-pt`

## Languages

PolyglotGame1830 columns: `en-us, hi-in, th-th, zh-cn, zh-hk, vi-vn, id-id, in-telugu, in-marathi, pt-pt, es-es, fr-fr, bn-bd, ko-ko, tr-tr`.

## Endpoints (Game1830HttpAPI)

| Constant | Path |
| --- | --- |
| GameDates | `/single_game.Game/initRoom` |
| BetResult | `/single_game.Game/gameResult` |
| BetHistory | `/goldgame/single_game_user_gold_history` |
| BetOneDayHistory | `/goldgame/single_game_user_history` |

Also observed on first-load: `/cp/config/initialData`, `/cp/account/getUserInfo`.

## Board and win model

- 6×6, `prop` length 36, column-major, index 0 in a column is visual top
- Win: **8 or more matching symbols anywhere** (Help Game1830_4). Not paylines, not ways. Adjacency is not required by Help.
- Client eliminates every cell whose `symbolId` equals `win_arr[i].p`
- `betLinesConfig = [20]` is **Base Bet**, not payline count
- Symbols 1–10 pay; 11 Scatter; 12–23 multipliers via `MultipleTypeList = [2,3,4,5,7,10,15,20,25,30,40,50]`
- No substituting Wild

## Cascade

One `gameResult` contains all pages in `data.props[]`. Client walks `PageIndex` locally. Fill only empty cells from the next page. Last page (`PageIndex >= props.length-1`) ends the spin.

## Bet

- request `bet_gold` = Bet Size (`BaseBetNum`)
- request `level` = Bet Level `10..1`
- History tip: Bet Size × Bet Level × Symbol Payout
- Dirty quarantine check only: `0.02 * 10 * 20 = 4` as response `bet_gold`. Not a counted Round.

## Round boundary

See `round-end.md`.

- **ORDINARY_LOSS**: paid complete Round, `frees.st==0`, `total_win==0`
- **ORDINARY_WIN**: paid complete Round, positive win, `frees.st==0`
- **SCATTER_FREE_SPINS**: paid trigger (3+ Scatter → 10+2 extra) plus every free `gameResult` until `frees.st==0`
- **BUY**: not applicable, not a bucket

Promo `FreeBetTimes` / `act_id` is not a special mode.

## History (lightweight)

Day list → one-day list → BetDetails (`data.results[]` / `result[]`). Capture once per confirmed outcome. Do not use history to fill 200/200/30.

## Implementation gate

`implementationAllowed=false` until clean complete Rounds exist. Collection may start using these classification rules. Dirty `fixtures/1830-Hotpot/spin-dirty-20260903` stays quarantined.

## Rates that stay 样本不够

- start / cascade-fill / free symbol weights (do not copy start weights onto fill)
- empty-field rates (`supported=true` forbidden)
- whether every win is an integer multiple of charged stake


## 独立无奖标记（2026-09-14）

普通及免费模式中，只有一页的完整独立零奖 Spin 可替换为 `#`，如 `原编码|#|原编码`，普通独立零奖整局可直接为 `#`。免费 Spin 仍占原位置并消耗一次免费次数。

按模式判定：普通盘须少于 3 个 Scatter；免费盘须少于 2 个 Scatter，避免漏掉追加免费次数。生成器同样按当前模式构造零奖盘面，并用 ResultUtil 复核。连消整段和其中无奖收尾页均保留；不能因末页未中奖就替换，因为末页还可能携带本 Spin 的结算倍率。

新解析器兼容已有完整编码。每次领取 Redis member 后只物化一次，校验、后续交付、重试与历史共用该事实；Redis 为空时仍失败。零奖生成最多尝试 5 次，并有 10 个已校验默认盘（41 按 PAN 数量分别保存）。先更新消费端 JAR，再使用新 Loader 写入带标记的数据。Loader JAR 通过 Maven package 交付到本游戏 dist 目录。
