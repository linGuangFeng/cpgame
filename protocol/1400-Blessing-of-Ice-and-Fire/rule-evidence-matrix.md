# 1400 field / rule evidence matrix

| Rule | Source | Code | Sample |
| --- | --- | --- | --- |
| 3x3 column-major | Game1400Define COL_NUM=3, transformSymbolIdList `t[3*i+n]` | BlessingResultUtil | initRoom p length 9 |
| Middle-row win | game_1400 `symbolList[i][1]` + captured w | evaluateOdd indices 1,4,7 | w=[2,2,2] odd=50 |
| Odds 100/50/25/5 | initRoom prop_odds | payTable() | 1,1,1 → 100 |
| Empty 0 | transform skips 0 | EMPTY=0 | w contains 0 → tw=0 |
| Empty only in losing middle | origin unique wins 8/8 full9; losses fillerZeros=0 | BlessingBoardGenerator.requireOriginShape | win = aligned 3x3 |
| History detail time+extend | origin history-detail `time` unix seconds, `extend.act_id` | BlessingController.historyRow | Game1400GameDetailView `new Date(1e3*time)` + `extend.act_id` |
| bet_type 1/2/3 | reqGameResult ternary | BlessingRoundFactory | 20 rounds each |
| Both cost 2x | bet_gold/2 when type 3 | charged = unit*2 | type3 bet_gold=1 with bet=0.5 level=1 |
| Both win x2 | playDoubleTotalWinAmountAnim total/2 | bothMultiplier=2 | 100+5 → total 105 |
| No special | no frees/mali/buy in 60 spins + frontend | generation realtime | specialFlags=[] |
