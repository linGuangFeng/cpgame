# 1940 Beach Fun 字段/规则—来源—代码判定—样本印证

rulesHash：`sha256:4332cba4e18f7a2dfb8c99d2c5f8969e7650edc3d109f97204af38fb4d1a2af2`

| 规则 | 来源 | 代码判定 | 样本印证 |
|---|---|---|---|
| 5×4、1024 Ways，从左连续同符号或 Wild | 赔表 UI `label_ways1/2`；`props_value` 5 列 4 行 | `GameRuleCore.REELS/ROWS`；`evaluate()` 从左连续 | `captures/1940-Beach-Fun/round-batches.jsonl` 全部 4648 步均为 (4,4,4,4,4) |
| 符号 1–8 赔付 3/4/5 连 | `initRoom.prop_odds` | `PAY` 与 `ResultUtil.ORIGINAL_ODDS` | `fixtures/1940-Beach-Fun/enter/initRoom/response.json` |
| Scatter=9 不赔；Wild=10 只替补 | 赔表 “Scatter Symbol”“Wild Symbol”；前端 `scatterId`/`wildId` | Scatter 不进 `evaluate()`；Wild 无独立赔表 | 原厂 `win_array` 从未出现 prop 9/10 |
| 连消：中奖格掉落，金牌中奖变 Wild 留下 | 帮助 gold 文案；相邻 cascade `props[]` | `transition()` 非金中奖清除、金中奖变 Wild | 314 个连消完整局；`oracle/cascade-rounds.json` |
| 金牌仅 2–4 轴普通符号 | 帮助 + 4648 原厂步 | `requireGold()` 拒绝轴 1/5 或 Scatter/Wild 金 | 原厂 `is_gold=1` 仅 reel 1..3（0-based） |
| 普通倍率 1,2,3,5；免费 2,4,6,10 | 前端 `commonMultipleList`/`freeMultipleList`；`initRoom.type_multiple` | `BASE_MULTIPLIERS`/`FREE_MULTIPLIERS` | `enter/initRoom/response.json` |
| 3 个 Scatter 给 12 次免费，每多 1 个 +2 | 赔表 + 前端 `freeTimes+=2*(o-2)` | `awardedFreeSpins()` | `oracle/free-spins.json` 付费触发 `new_times=12` |
| Round 边界：一次付费 Spin + 全部 cascade + 全部 FREE_STEP 直到 `frees.surplus_times=0` | 原厂连续 `gameResult` | `validate()` 要求 pending==0 | 1865 个完整 Round |
| 购买入口不存在 | `buy_free_max_bet=-1`；无购买按钮 | Controller 不接受购买请求 | `initialData` 与 UI 证据 |
| 免费重转 | 赔表/前端允许 | 生成器不发明未观测重转 | SAMPLE_INSUFFICIENT：1865 付费局 0 次 |
