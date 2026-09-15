# 1400 Blessing of Ice and Fire protocol

`rulesHash` is computed from `BlessingRulesMetadata` (`blessing-1400-realtime-multiplier-r1`).

## Identity

- gid `1400`, directory `1400-Blessing-of-Ice-and-Fire`
- iframe: `static.cpgame.io/v2/1400/?gid=1400&l=pt&language=pt-br&sip=api.omgapibra.com`
- No Wild, Scatter, Mali, Buy, or game free-spins. Promo `FreeBetTimes` is hall activity, not a game mode.

## Endpoints

| Path | Role |
| --- | --- |
| `/cp/config/initialData` | language, bet sizes `[0.5,5,15,50]`, `buy_free_max_bet=-1` |
| `/cp/account/getUserInfo` | gold, token, gid |
| `/cp/single_game.Game/initRoom` | last board + `prop_odds` |
| `/cp/single_game.Game/gameResult` | one paid Round |
| `/cp/goldgame/single_game_user_gold_history` | day list (`day`, `bet_gold`, `change_gold`) |
| `/cp/goldgame/single_game_user_history` | day detail. Each row needs unix-second `time`/`created_at`, `extend.act_id`, `props.pr.1/2.p+tw+odd+m`, `end_gold`. Client `new Date(1000*time)` and `extend.act_id`; missing either blanks the board. |

Envelope `{code:0,data,msg}`. Success code is `0`.

## Bet

- request `bet_gold` = bet size, `level` = 1..10, `bet_type` = 1 fire / 2 ice / 3 both
- charged `bet_gold` = size × level × (type 3 ? 2 : 1)
- origin min size 0.5

## Board

- 3×3 column-major `p[9]`. `0` empty. Symbols `1,2,3`.
- `w` = middle row `p[1],p[4],p[7]`
- Fire page `props.pr.1`, ice page `props.pr.2`
- Client maps fire symbols as-is and ice as `id+3` for display only

## Win

Middle row, three non-zero cells:

| middle | `prop_odds` | odd |
| --- | --- | --- |
| 1,1,1 | `"1"` | 100 |
| 2,2,2 | `"2"` | 50 |
| 3,3,3 | `"3"` | 25 |
| mixed | `"4"` | 5 |
| any 0 | — | 0 |

Page `tw` = odd × (size × level). If both pages win, `m=2` and `total_win = 2 × (tw1+tw2)`.

## Round

One `gameResult` is the complete Round. No cascade, no extra step.

## Generation

One entry `BlessingRoundFactory.generate(random, betType, size, level, requestedOdd)`.

- Single-open list (fire or ice): `0,5,25,50,100` size 5
- Both-open list: sums of two page odds, `0,5,10,25,30,50,55,75,100,105,125,150,200` size 13
- Requested odd floors to the greatest list value `<= requested` (30 single → 25, 30 both → 30)
- Combo map then yields `(fireOdd,iceOdd)` pairs. Single-fire map 5 keys / 5 combos, single-ice 5/5, both 13 keys / 25 combos
- Only the combo is looked up. Middle patterns and fillers stay realtime. Demo does not read Redis.

Empty `0` is only legal in a losing middle row (`p[1]`,`p[4]`,`p[7]`). Top and bottom fillers are always `1|2|3`. Winning pages are a full aligned 3×3.
