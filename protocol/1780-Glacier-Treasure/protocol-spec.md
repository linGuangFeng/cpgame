# 1780 Glacier Treasure protocol specification — partial verified implementation

Status: READY_FOR_ACCEPTANCE.
rulesHash: `0139e446c93699f20cd06048145312e652edf09876efc7d4ff26cc207a73172e`.

This specification separates evidence from runtime support. Java GameRuleCore evaluates boards; BoardFactory samples from the 1780 corpus dealing model; Controller v3 serves the original publish tree and Redis complete rounds.

## Sources

- `fixtures/1780-Glacier-Treasure/enter/INDEX.md` and original Enter responses.
- Original `assets/Game1780/index.c1fff.js`, especially Game1780Mgr, Slot1780Define and game_1780_V.
- `assets/resources/import/0e/0e8193bfb.03966.json`: embedded help CSV, extracted without modifying the frontend into `help-extracted.json`.
- Original `history-view/response.json`: 30 History records, 40 Deliveries, 71 Steps.
- `rules.json` records exact original-source SHA-256 values. Paths are relative to /Volumes/hd/cpgame unless absolute.

## Observed HTTP interface

| Logical operation | Observed POST path | Evidence / response |
|---|---|---|
| Config | /cp/config/initialData | Enter INDEX and initialData response: body code/data/msg/time, bet sizes, level defaults, locale |
| Init | /cp/single_game.Game/initRoom?slot1780 | Init response: current props[], prop_odds, bet, level, balance and free state |
| Spin / continuation / purchase | /cp/single_game.Game/gameResult?slot1780 | Original Game1780Mgr; full corpus request chains still unavailable |
| Balance/user | /cp/account/getUserInfo | Enter INDEX |
| History day list | /cp/goldgame/single_game_user_gold_history?slot1780 | Entered from requestHistory; original History fixture |
| History paged round details | /cp/goldgame/single_game_user_history?slot1780 | requestDayHistory sends day, page, page_size=30 |
| Timing | /api/report/timing | Enter INDEX |

Game requests carry gid=1780 and token. Normal/free requests carry bet_gold=betSize and level=betLevel; free continuation uses the same endpoint without an invented free-mode request flag. Purchase uses bet_type=3. Optional activity act_id exists in the original client but is not implemented here. Session support is token-bearing request handling; a standalone /Session endpoint is not established.

The captured Config name string is "Magic Scroll" despite gid=1780. Original resources and game_id must govern identity; do not import another game's rules based on this string.

## Round, Delivery and Step

- A **Step** is one board, represented by an entry in Spin props[] or History result[]. It has six props_value columns, four horizontals, total_amout, and win_array. The spelling total_amout / win_amout is original.
- A **Delivery** is one Spin response containing all its cascade Steps. The frontend iterates GameResultList derived from props[]. A delivery terminates at its last nonwinning board, not at the first stopped reels.
- A **Round** includes its paid Delivery and every resulting free Delivery. History results[] supplies this grouping. Count the containing History record once, not each free Delivery or cascade.
- The free History record has 11 Deliveries: trigger plus ten free spins. The containing total_win is the trigger value (0), not the complete Round winnings (940.8). Sum Deliveries or use the final accumulated free total with appropriate paid payout.
- Free surplus_times determines continuation. The final observed free Delivery has surplus_times=0, total_times=10, multiple=14, total_win_amount=940.8. Requiring every free field to reset would contradict the evidence.
- checkFreeTimes opens the free total-win view, then updateCommonSpinViewWhenFreeSpinFinish returns to normal play. Browser completion must include these animations and usable buttons.
- This History subset contains no proven buy request or retrigger chain. free_origin_type=3 alone is not classified as a purchase.

## Board and payout

Six vertical reels each occupy five cells; four horizontal symbols belong to reels 2–5. Edge symbols are one cell; middle ordinary symbols can occupy 1–4 cells. A large ordinary or Wild symbol counts as **one** symbol for ways. Wild=13 substitutes ordinary symbols and appears only on reels 2–5. Scatter=12 does not substitute. Scatter trigger units are sum(grid), supported by three captured two-cell Scatter boards reporting nums=2.

For each ordinary symbol (1–11), count matching ordinary/Wild symbols from reel 1 until the first reel with no match. A run of 3–6 reels pays the corresponding original prop_odds entry times the product of per-reel counts. Payout is betSize × level × odds × ways × multiplier. Base bet is betSize × level × 20. The complete captured paytable is in rules.json.

Each ordinary winning symbol disappears. A winning silver-framed large symbol keeps its id/size and becomes an ordinary gold-framed symbol (prop 2–11; prop 1 never observed). A winning gold-framed symbol keeps its id/size and becomes Wild with frame 0. Nonwinning symbols persist, and retained symbols preserve their axis and relative order.

Dealing (origin-gap-fill first pages / new symbols only):

- Paid opening inner axes: 39 structures, denominator 3388. Gold tokens are absent (goldPct=0). Silver is 30.27% of large opening groups.
- Cascade **new** symbols: 6461/6461 are grid=1 frame=0. Silver and gold on later pages are transformed survivors, not newly dealt.
- Caps from corpus (never generate unseen counts): scatter units board 4, scatter units reel 3, **at most one Scatter symbol per vertical column** (1901 origin boards: 0 columns with two Scatter symbols; a grid=2 Scatter is still one symbol). Reel 2–5 may also have a Scatter on the extra top cell. wild board 3, wild reel 2, cascade pages 14.

Normal multiplier starts at 1 and increases by 1 after a winning step. Free multiplier starts at 2 and increases by 2 after winning steps, carrying between free Deliveries. Four Scatter *symbols* award ten initial free spins; each additional Scatter symbol adds two. `new_free.nums` is grid-sum units and can exceed the symbol count when a Scatter occupies two cells. Help confirms retriggers are possible, but their request/award lifecycle is not validated.

## Balance and History differences

The 40 captured Deliveries satisfy end_gold − start_gold = change_gold. Free Delivery change_gold equals its payout; paid Delivery change_gold equals payout minus base bet. Free balances connect to the preceding end_gold.

Thirty nested History records encode bet as boolean false. The regression obtains the unit stake from nominal bet_gold / 20 and level, consistent with the client formula. Free History bet_gold remains 4 even when the debit is zero; it must not be charged again. Do not reuse History schema blindly for the Spin response.

## Implemented checks and remaining limits

GameRuleCore computes payout; ResultUtil independently enumerates matching paths from the raw Init paytable. Java regression compares raw payout totals, all 46 win_array entries, winning flags, terminals, 31 cascade transitions, multiplier continuity and balance.

Current real History classification is ordinary loss 24, ordinary win 5, free feature 1. These are development regression samples. They are not a 100-Round holdout. Zero generated Rounds have been tested. SAMPLE_INSUFFICIENT applies to this accessible subset; the metadata claim of 1,864 Spin Rounds is not independently verified.

API, original HTML publish, language clicks, Controller, Loader, Redis and browser full-Round acceptance remain unavailable or untested. See protocol-handoff.json for exact blockers and required platform contract.
