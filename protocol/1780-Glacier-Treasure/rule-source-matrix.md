# Rule source matrix

rulesHash: `1554fcf2ca822247deefd1eaa0d7f3b6ad3c2f622ab0db0af142863342cd9743`
Status: PARTIAL_IMPLEMENTATION_BLOCKED_ON_EVIDENCE; no production-playability claim.

| Behavior ID / rule | Primary original evidence | Implementation / verification |
|---|---|---|
| 1780.board.geometry | Init props_value/horizontals; Help UI_Game1780_12; client rowSymbolCount=4, colSymbolCount=5 | Board validates 6 × 5 plus 4 horizontal symbols; all 71 boards pass |
| 1780.payout.leftways | Init prop_odds; Help _30, _31, _33 and BetDetails_Tips; Game1780Mgr betLinesConfig=[20] | Core product counts; independent path-enumeration oracle with raw Init odds; 46 captured breakdowns |
| Large symbol counts | Help _12 | Grid height never multiplies ordinary/Wild ways |
| Scatter units | History list[14].results[0], list[19].results[3] and [4]: one grid=2 Scatter, nums=2 | Core sums Scatter grid. This fixed three initial regression discrepancies |
| Wild placement/substitution | Help _3 and _12; Slot1Constant wildId=13 | No edge Wild; excludes Scatter substitution |
| 1780.cascade.frames | Help _14, _16, _17, _35, _36; client checkEliminate | Retained silver→gold→Wild, removals and survivor order verified on 31 transitions |
| 1780.multiplier.normal | Help _19/_20; Slot1Constant 1, +1 | All ordinary Step multipliers match capture |
| 1780.multiplier.free | Help _23/_24; onGameResult derives start multiplier from end multiple and props count | Ten free Deliveries and six free cascades maintain captured multiplier |
| 1780.scatter.initial | Help _22; original new_free | Initial award 10 at 4, +2 for each extra. No retrigger transport inference |
| 1780.free.terminal | client checkFreeTimes; terminal History frees | surplus_times=0; preserve recorded total_times/multiple/total_win_amount |
| 1780.buy.request | reqBuyFreeTimes, getBuyFreeTimesCost; Help _27/_28 | bet_type=3 and cost ×60 known; production buy transport and request-chain validation blocked |
| Retrigger support | Help _25 | Observed in help, no accessible complete retrigger request chain; not implemented |
| Wallet | Original start_gold/end_gold/change_gold across 40 Deliveries | Java checks debit and free balance continuity; no Balance endpoint served |
| Session | Enter requests/INDEX and client token fields | Endpoint/session runtime not implemented |
| Language labels | Embedded help CSV has 15 locale columns; 11 resource directories observed | Text extraction only; no language click acceptance |
| Per-axis/board special-count caps | Accessible 71 boards and animation fillers | Descriptive observed maxima only; full-corpus legal generation caps unresolved |
| Entry distributions | History initial / refill / transformed symbol observations | Counts, denominators and percentages are in Java report; no independent-cell generator |
| Mali relation | No conclusive original material inspected establishes it | UNKNOWN; no cross-game rule substitution |

Source paths and hashes are in rules.json. Unmodified original resources remain in resources/1780-Glacier-Treasure. Animation filler Math.random routines are presentation code and are not used as an outcome distribution.
