# 1810 Treasure Hunt v40 protocol specification

All observations below come from the landed 3,824-round real-provider corpus. A paid request posts `token`, base `bet_gold`, `level`, `gid=1810`, and `language` to `/cp/single_game.Game/gameResult`. One response contains the complete round in ordered `props[]`.

The board uses column-major row counts 3/4/3 and ten fixed paylines recorded in `game-capabilities.json`. Wild 7 substitutes and the paytable is `{1:100,2:50,3:20,4:10,5:5,6:3,7:200}`. Each `win_arr.tw` is `bet*level*odd`; a step with `all=1` applies x10 only to the step total. Paid cost is `bet*level*10`.

Ordinary rounds have one board. Treasure Hunt rounds use `type=2`, `small_game_type=3`, contain 2–9 whole-board steps, keep nonterminal steps at zero win, and settle on the last step. All-reels rounds use `all=1`, all ten paylines win, and the complete step receives x10.

The controller also implements the original init and History list/detail endpoints. Runtime results come only from verified complete-round facts in Redis db15; empty pools fail closed.
