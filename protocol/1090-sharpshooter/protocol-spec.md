# Sharpshooter 1090 protocol specification (contract v3)

Rules hash: `e2d48bc36060cfa82c86468e67d20617b0f137bae5ed9986541823c558c7daff`.

## Evidence and boundaries

- The original frontend defines `GameResult=/single_game.Shooter/gameResult`, `InitRoom`, both shooter history endpoints, a 5×4 board, Scatter 9, Wild 10, normal tumble multipliers `[1,2,3,5]`, and free multipliers `[2,4,6,10]` in `resources/1090-Sharpshooter/*/static.cpgame.io/v2/1090/assets/Game1090/index.8f1fd.js`.
- `initRoom` supplies the eight-symbol paytable and both multiplier arrays. Spin fixtures prove `props[]` is the ordered tumble sequence and `win_array` uses symbol, contiguous reel end, ways, odds, multiplier and award.
- A complete Round is one paid `type=1` start and, when `small_game_type=2`, the entire counter-contiguous series of `type=2` free continuations through `frees.surplus_times=0`.
- 1485 paid starts were audited. Ordinary loss=1235, ordinary win=200. Of 50 feature triggers, exactly 30 sequences are counter/OID-contiguous and complete; 20 incomplete evidence sequences are excluded.

## State model

The board is column-major: five `props_value` columns, four cells each. Symbols 1–8 pay on all ways across 3–5 consecutive reels starting from the left; Wild substitutes. On observed paid triggers, Scatter 9 awards 12 free spins for 3 and 14 for 4. Free retrigger remains unimplemented and SAMPLE_INSUFFICIENT. Each winning tumble is multiplied by the mode's multiplier at the same `props[]` index, capped at the last value.

State transitions are `IDLE -> PAID -> FREE* -> TERMINAL`. A single Redis member contains every state. The Controller claims it once, retains it in session, advances only the member's spin index, and returns it to no pool.

## HTTP projection

POST endpoints: `/cp/config/initialData`, `/cp/account/getUserInfo`, `/cp/single_game.Shooter/initRoom`, `/cp/single_game.Shooter/gameResult`, `/cp/goldgame/shooter_user_gold_history`, and `/cp/goldgame/shooter_user_history`. Spin fields preserve the provider names including `props[].total_amout` and `win_amout`. History is read-only and never supplies gameplay.

Redis loss/win ZSET indexes select integer centi-multipliers. Buckets contain printable `SS1` ASCII structural members only—no HTTP envelopes, balance, token, timestamp, or fixture JSON. Empty/missing selected buckets return HTTP 503 `PREGENERATED_CACHE_EMPTY`; there is no fallback.

## Recovery audit and acceptance boundary (2026-09-09)

Status is READY_FOR_ACCEPTANCE. Original help says free spins can be retriggered; none of 583 raw free responses or 42 nested History states shows a positive retrigger. Current code constrains free Scatter to at most 2 (never generate an unobserved quantity) and implements only observed non-retrigger features. That gap is SAMPLE_INSUFFICIENT and non-blocking. See reports/1090-Sharpshooter/evidence-gaps.json.

The board is column-major bottom-to-top. Winning ordinary symbols leave; winning gold symbols retain IDs, become Wilds and lose gold. Survivors retain order as column prefixes, with fresh symbols in the suffix. No new Scatter, gold or Wild was observed in refill cells. Caps in rules-contract.json are empirical model constraints, not proven universal limits.

The offline generator samples training-derived joint special-count shapes, weighted column tuples and refill blocks. Joint cascade-count/payout-bin targets condition legal rejection sampling. It never changes survivors to force outcomes and does not replay complete fixture rounds. ResultUtil independently enumerates pay paths without calling Core payout code.

Training uses 1365 complete rounds. The preserved disjoint holdout contains 100 rounds (84 losses, 16 ordinary wins, zero free features). All 1465 original complete rounds, 1827 states and 3368 pages pass independent rule checks. Latest 10000 generated rounds pass declared approximation tests for observed branches; exact provider probabilities are not established. The failed first distribution attempt is retained. API tests cover each of three modes twice, but subsequent browser checks verify each observed mode at least twice, 15 native startup/main locale entries, and generic static full-play. Free retrigger remains unverified and unimplemented.

Free continuations lock the initial wager. initRoom restores the last delivered state. The timing route /api/report/timing is available. ANY pool selection includes FREE_SPINS in winning results; selection uses random available integer multipliers and has no runtime generation fallback.

Rules version `1090-rules-v2`. Redis indexes `PerKeyList_000001090` / `MaryKeyList_000001090`. Behavior IDs: B-NORMAL-LOSS, B-NORMAL-WIN, B-TUMBLE, B-FREE-SPINS, B-ROUND-BOUNDARY, B-HISTORY, B-REDIS-EMPTY.

The root server-api/1090-sharpshooter/demo-controller.properties is the v3 descriptor and references dist/controller.jar plus dist/controller.properties. Runtime supports --port 50000–59999 and --publish, resolving relative publish paths against its config. Loader has a JAR, seedless properties and self-locating Windows cmd. Original frontend files were not changed. Native locale codes differ from directory aliases; see game-capabilities.json. Original native text sometimes falls back to English.

Redis keys follow the platform PerKeyList/BetLog/MaryLog layout (same family as 1380). Demo claims one SS1 member per paid start and projects every later tumble/free state from that member.

## Independent acceptance (2026-09-09)

Controller and loader were rebuilt from one GameRuleCore so Redis keys match. Redis DB15 holds 1540 unique ASCII members (500 loss / 800 win / 240 free). Static first-load paths: 113/113 HTTP 200 on a plain python http.server. API: two loss, two win, two free sequences to surplus_times=0, then an immediate next paid spin; empty multiplier 503; history nested. Natural spins without an `outcome` field also complete. GUI clicks cannot be driven on this host (Chrome SEGV, Safari open -54, no display). Prior Edge CUA session recorded 22 original-page rounds including two uninterrupted free features; see browser-acceptance-resumed.json.
