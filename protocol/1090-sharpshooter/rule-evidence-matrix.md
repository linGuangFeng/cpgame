# Rule evidence matrix

Rules version `1090-rules-v2`; rules hash `e2d48bc36060cfa82c86468e67d20617b0f137bae5ed9986541823c558c7daff`. Full acceptance BLOCKED_EVIDENCE.

| Behavior ID | Evidence | Implementation / validation | Status |
|---|---|---|---|
| B-NORMAL-LOSS | 1235 complete paid losses | Independent oracle, API twice, browser five rounds | Observed branch verified |
| B-NORMAL-WIN | 200 complete paid wins and initRoom paytable | Oracle, API twice, browser multiple complete rounds | Observed branch verified |
| B-TUMBLE | Original frontend ID animation and raw props | Stable IDs, suffix refill, gold-to-Wild; 3368 original pages checked | Observed constraints verified |
| B-FREE-SPINS | 30 complete non-retrigger features | 12/14 free states; API twice and locked wager | Two uninterrupted non-retrigger browser features verified; retrigger blocked |
| B-FREE-SPINS retrigger | Original help; zero positive events in audited raw free and History states | Current empirical free Scatter cap 2 prevents retrigger | SAMPLE_INSUFFICIENT, blocks acceptance |
| B-ROUND-BOUNDARY | Paid/free counter audit | Single stored member, restored last state, terminal history | API verified |
| B-HISTORY | Saved list/detail responses | Six API round records with nested results | Ordinary and free browser details verified |
| B-REDIS-EMPTY | Runtime contract | Absent multiplier returns 503, no fallback | API verified |

Empirical caps are model restrictions, not proven universal provider limits. Latest 10000 generated complete rounds pass declared statistical checks for observed branches; exact probabilities are not claimed. The disjoint 100-round holdout contains no free feature. All 15 native startup/main locale entries and generic static full-play are verified; original blank/fallback translation cells are preserved. See reports/1090-Sharpshooter/evidence-gaps.json and browser-acceptance-recovery.json.

Resumed browser evidence: 16 additional complete rounds (8 normal wins, 5 losses, 3 free features; one free feature required reload before asset repair). Two uninterrupted free features paid 416.40 and 822.00. Exact original local assets repaired the free-trigger 404 and Auto panel. Remaining Redis pool: 892 members, all ASCII/unique/decode/Core checks pass. See browser-acceptance-resumed.json, native-locale-acceptance.json and redis-inventory-final.json.
