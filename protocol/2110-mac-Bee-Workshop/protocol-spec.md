# 2110 Bee Workshop — Mac remake protocol

Rules hash: `bw2110-lines20-paytable-v2-a7afb966-deal-3d5f4e28`
Rules version: `bee-workshop-2110-mac-v1`
Canonical core: `generator/2110-mac-Bee-Workshop/src/main/java/com/cpgame/replica/beeworkshop/GameRuleCore.java`

Original `{ID}-{Name}` Java and frontend were not modified. This directory is the isolated remake.

## Board and pay

- 5 reels × 3 rows, column-major bottom-to-top. Visible 15 cells in `props.prop`.
- Paying symbols 1–7, Wild 8, Scatter 9. Wild legal only on reels 2, 3, 4 (Game2110_9).
- 20 paylines, left-to-right, highest award per line. Evidence: `assets/Game2110/index.1cbca.js` and help `0671ab352.10e6d.json`.
- Paytable 3/4/5: 1=100/300/1000; 2=30/60/300; 3=10/30/100; 4=8/20/80; 5=6/10/60; 6=5/8/50; 7=5/8/40.
- Integer multiplier = sum of line odds. Money = units × bet_gold × level. Total stake = bet_gold × level × 20.

## Round kinds

| Kind | Paid start | Complete round | Redis |
|---|---|---|---|
| ORDINARY_LOSS | type=1, tw=0, spe_pos=[] | 1 delivery | BetLog:000002110:000000 |
| ORDINARY_WIN | type=1, tw>0, spe_pos=[] | 1 delivery | BetLog:000002110:%06d |
| MYSTERY_BOX | type=1, spe_pos 5–10, same reveal symbol | 1 delivery | MaryLog |
| FREE_STICKY_SYMBOLS | 3/4 Scatter awards 8/10 free (help also 5→15; 5 never observed) | 1+award deliveries, frees.st → 0 | MaryLog |

Buy: captured `buy_free_max_bet=-1`. Not a purchase route. Mystery is not buy.

A complete round is one paid `gameResult` plus every continuation until the round is legally over. Mystery and ordinary are one step. Free is the paid trigger plus every type=2 step until `frees.st=0`.

Origin issues a **new `oid` on every delivery**, including each free step (see `origin-gap-fill` round-0027: trigger oid `…240` then free oids `…393`…`…400`, `st` 8→0). Frontend `InitData` treats identical oid as `game2110ResultIsSame` and retries `gameResult`. Demo therefore increments oid per delivery and does not send `forder_id` (absent in this game’s captures).

## APIs (captured, not invented)

- POST `/cp/config/initialData`
- POST `/cp/account/getUserInfo`
- POST `/cp/single_game.Game/initRoom`
- POST `/cp/single_game.Game/gameResult`
- POST `/cp/goldgame/single_game_user_gold_history`
- POST `/cp/goldgame/single_game_user_history`
- POST `/cp/activity/getActivity`
- GET `/api/balance`, `/api/session`, `/health`

Envelope `{code,data,msg,time}`. Spin fields: token, bet_gold, level, gid, language, ai.

## Deal model

Whole-column tuples, never independent cells. Packaged `deal-model.json` SHA-256 `3d5f4e2805cd49bda59a53a06232f40385e8a0de1f7db1c4263e8e9adf39fa85`.
Denominators: ordinary 1210, mystery 80, free trigger 38, free initial 1, free continuation 7.

Special entry (every 1000 draws): only FREE_STICKY_SYMBOLS, which is the Scatter-boosted opening.

Independent LOSS blocks payline reel 3 and keeps Scatter < 3. First-attempt ≥ 90% on 100000 boards.

## Redis

192.168.10.3:6379 DB15. Member `BW1|kind|board.hexMask~...` printable ASCII, not JSON.
Demo: random WIN/LOSS (1485/444 of 1929 origin paid starts), then an existing integer multiplier, then RPOP that list. Continuations project the same member.
Empty cache: HTTP 503 PREGENERATED_CACHE_EMPTY. No runtime deal, no fixtures.

## Origin sample (collect-20260908, no recapture)

Paid starts 1929. ORDINARY_LOSS 1485, ORDINARY_WIN 120, MYSTERY 192, FREE_TRIGGER 132.
Complete free chains reconstructed by oid in the gap-fill folders are incomplete (mixed oid files). History retains one full 8→0 chain. FREE complete rounds: SAMPLE_INSUFFICIENT; implementation continues with generated sticky chains bounded by observed 3/4 Scatter.
