# EDM Mania (gid 2010) protocol specification

Status: IMPLEMENTABLE. Rules are taken from original `gameResult` fixtures, `initRoom.prop_odds`, and the Game2010 6×5+4 ways client. ResultUtil is an independent oracle; Redis members store boards only.

## Identity

- Directory / management ID: `2010`
- Runtime `gid`: `2010`
- Bundle: `Game2010` version `f36db`
- Common client: `settings.47d04.js`, `main.79fcc.js`, `cocos2d-js-min.d3d27.js`

## APIs

| Path | Method | Role |
|---|---|---|
| `/cp/config/initialData` | POST form | language, bet list, buy_free_max_bet=0 |
| `/cp/account/getUserInfo` | POST form | gold / token |
| `/cp/single_game.Game/initRoom` | POST form | last board + `prop_odds` |
| `/cp/single_game.Game/gameResult` | POST form | paid / free continuation |
| `/cp/goldgame/single_game_user_gold_history` | POST form | daily summary |
| `/cp/goldgame/single_game_user_history` | POST form | per-round detail |

Request fields: `token`, `gid=2010`, `ai`, `language`, `bet_gold`, `level`. Feature buy adds `bet_type=3`. `buy_free_max_bet=0` does **not** hide buy (`checkBuyFreeMax` treats 0 as unlimited).

`bet_gold` is either the size (`0.02`/`0.1`/`0.2`) or the 20-ways charge (`size × level × 20`). Default capture: size `0.02`, level `10`, charge `4`.

## Board

- 6 reels × 5 rows, column-major `prop[30]`
- `trl[4]`: extra cells above reels 2–5
- `grids`: stacked height 2–4 on inner reels only
- `sl` silver frames, `gf` gold frames (gold never on opening; gold comes from silver-win transform)
- Ways left-to-right; stacked group counts as one visible symbol
- Wild `13` substitutes 1–11; not on outer reels
- Scatter `12` awards free spins from **visible prop+trl** (a stacked group counts as 1): 4→10, 5→12. Paid and free use the same count. abc223: 3 main + 1 trl → 10; two stacked scatters count as 1.
- Ball `1` adds ×2 per **new visible main-board ball** (stacked group = 1), including no-win pages. Surviving balls do not re-add. trl balls are not in the multiplier model (one abc223 page added +2 on a trl ball; SAMPLE).
- Free feature starts at `max(2, paid ending m)` and persists across free spins; paid `m` carries in if the trigger already collected balls.
- `frees.tt` is the **running** total (trigger 10, retrigger 10→20 on that spin). `frees.st = tt - freeIndex` (paid index 0 does not consume).
- Trigger paid spin uses `type=3`; free continuation `type=2` and `small_game_type=2`
- Feature buy: main-UI `btn_buy` → confirm `Game2010BuyFreeTimeView`. Request `bet_type=3`, `bet_gold` is size (`0.02`), charge is **75 × 20-ways stake** (min R$4 × 75 = R$300). Response `type=3`, `bet_gold=300`, `frees.ba=300`. Same free chain as natural trigger. abc222 captured 30 complete buy Rounds.

## Round

One paid `gameResult` plus every cascade page in `props[]` plus every free spin until `frees.st=0`. Redis member is that whole Round.

## Evidence

- `fixtures/2010-EDM-Mania/spin` 400 HTTP results (317 ordinary loss, 61 ordinary win, 11 type=3 triggers, 11 type=2 terminals). Unchanged; do not overwrite.
- `fixtures/2010-EDM-Mania/spin-free-complete` 3 complete free Rounds from hms-paddle abc223 (21 / 11 / 21 steps), including all middle frees and two retriggers (10→20).
- `fixtures/2010-EDM-Mania/enter` initRoom / initialData / getUserInfo
- `fixtures/2010-EDM-Mania/history-list` + `history-view` (daily list + 30-row detail)
- `fixtures/2010-EDM-Mania/history-by-type` ordinary-loss / ordinary-win / free-trigger / buy
- History list item is one paid `gameResult`. Ordinary: `result` copies `props`, `results` length 1. Free/buy: top row is the type=3 trigger; `results` is trigger + every type=2 until `st=0`. Nested frees use `forder_id` and `bet_gold=0`.
- `fixtures/2010-EDM-Mania/spin-buy` origin `bet_type=3` complete Rounds (abc222)
