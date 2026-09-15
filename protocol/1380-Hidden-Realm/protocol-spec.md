# Hidden Realm 1380 — protocol

Status: implemented. Demo reads pre-generated complete rounds from Redis; it never deals at runtime.

Rules hash: `a47cbb826fa130d6cf03c208a992f29789aad2a9870202ea7080145935f2d7db` (1380-rules-v2)

## Board

- 5 columns × 5 rows, column-major `props_value[col][row]`, row 0 at the bottom.
- Symbols 1–4 low, 5–8 high, 9 Wild. Wild substitutes for 1–8 and is counted in cluster size.
- Orthogonal clusters of size ≥ 4 pay. Two separate clusters of the same symbol pay separately. Wild-only groups do not pay (prop_id 9 odds 0).
- Payout = Bet Size × Bet Level × paytable odd. Charged gold = Bet Size × Bet Level × 10 (base-bet list `[10]` in the original frontend). Redis integer multiplier = total_win / (betSize × betLevel) = sum of cluster odds.

## Round boundary

- One paid `POST /cp/single_game.Game/gameResult` starts a Round (`type=1`, `type_skill=0`).
- `props` is the ordered list of cascade pages for that delivery.
- Collection `frees.total_num` is cumulative exploded winning cells in the round. Thresholds 10 / 30 / 50 / 70 unlock Grass / Water / Fire / Dragon Lord (`frees.total_skill_type`).
- Frontend continues with another `gameResult` while `type_skill != frees.total_skill_type`. Continuations use `type=2`, `small_game_type=1`, same charged `bet_gold` field, no second debit (`change_gold = total_win`).
- Terminal when `type_skill == total_skill_type`. Then `onGameOver` → total-win animation → `checkAutoTimes` restores IDLE and re-enables Spin.

## Features

- Grass: remove lows, gravity, refill, then cascade.
- Water: write Wild at cells `[1,1],[1,3],[3,1],[3,3]` (0-based col,row), then cascade.
- Fire: selected non-wild symbol fills same-parity cells, existing Wild kept, then cascade.
- Dragon Lord: while lows remain, emit a non-scoring transform page then convert lows to high symbols 5–8 (1932 origin transform cells, 0 Wild). Score only when no lows remain. Continue until no lows and no wins.

## Buy / mali / scatter

- `buy_free_max_bet = -1`. No purchase endpoint observed. No scatter free-spin mode. Dragon features are the special path (MaryLog).

## Ordinary win sample

- True paid starts (`type=1`, `type_skill=0`): 795. Training 695 / holdout 100.
- Training: LOSS 576, SPECIAL 119 (phase 1/2/3/4 = 23/24/24/48), ordinary WIN 0.
- Marked `SAMPLE_INSUFFICIENT` for original ordinary-win captures. Generator still produces legal 4–9 collection wins for the Redis win pool.

## APIs

| Call | Path |
|---|---|
| Init | `/cp/single_game.Game/initRoom` |
| Spin | `/cp/single_game.Game/gameResult` |
| Config | `/cp/config/initialData` |
| User | `/cp/account/getUserInfo` |
| History summary | `/cp/goldgame/single_game_user_gold_history?slot13` |
| History day | `/cp/goldgame/single_game_user_history?slot13` |

Request spin form: `bet_gold` (size), `level`, `gid`, `token`. Min size 0.05, levels 1–10.
