# 2470 Lucky Night Market — original protocol and rule evidence

Reassessed on 2026-09-14 from the original frontend extract, original provider fixtures, and a newly written independent arithmetic oracle. No previous server API or generator implementation was used. Runtime and browser acceptance results belong in the final current-status report; they are not inferred from these original captures.

## Original evidence and sampling

- Original frontend modules: `captures/2470-Lucky-Night-Market/frontend-extract/` (`SlotGameDefine.js`, `SlotDataMgr.js`, `GameMain.js`, `BigWin.js`, `GameWinAmountDisplay.js`, `GameFreeEnded.js`, `GameWheel.js`, `PlayerMgr.js`, and `help-en-us.json`).
- Provider round index: `captures/2470-Lucky-Night-Market/spin-index.jsonl`.
- Provider responses: `fixtures/2470-Lucky-Night-Market/spin/round-NNNN/step-NNN.response.json`, envelope `{status,body}`; protocol response is `body`.
- 3,000 original complete paid rounds: 2,436 ordinary losses, 444 ordinary wins, 118 eight-delivery Lucky Features, and two Lucky Wheels. These contain 3,826 provider deliveries.
- First 2,900 rounds are modeling inputs; final 100 complete rounds are reserved as holdout. A fresh independent oracle matched the payline list, cash award and free-state sequence on all 3,826 deliveries with zero differences.
- Wheel is `SAMPLE_INSUFFICIENT`: only two complete real rounds were observed before the 3,000 paid-start limit. The observed wheel multipliers are 100 and 200; no other wheel outcomes may be inferred as sampled.

## Board, paylines and award

The board is 3 columns by 3 rows. `res.ps` is column-major, `column * 3 + row`, where provider row 0 is the bottom visible row. Five protocol payline IDs are:

| `wa.l` | Provider positions | Display |
|---|---|---|
| 1 | 1,4,7 | middle |
| 2 | 0,3,6 | bottom |
| 3 | 2,5,8 | top |
| 4 | 0,4,8 | diagonal |
| 5 | 2,4,6 | diagonal |

The frontend visual row index is reversed; its animation index must not be mistaken for protocol payline numbering. Symbols are Wild 0, H1 1, H2 2, L1 3, L2 4, L3 5 and L4 6. Three matching symbols on a payline pay odds 100,50,25,10,5,3,2 respectively. Wild substitutes for every regular symbol; a line of three Wilds pays symbol 0. `wa` contains only `{c,l,o,s}`, with `c=3`; `o` is the paytable odd before the multiplier.

Total bet is `b * l * 5`. The award in base stake units is `sum(wa.o) * appliedMultiplier`, plus `5 * res.wem` if Lucky Wheel is active. Cash is units multiplied by `b * l`. Response `tw` and `res.tws` are the award of this delivery; `o = tw / (b*l*5)` and is allowed to be fractional. The advertised maximum is 3,200 times total bet. No original sampled round reaches the cap, so exact terminal cap behavior has not been inferred.

## Modes and state

Ordinary play uses only the center value `res.muls[1]` of the three-position horizontal reel. Observed non-ticket values are 1,2,3,5,10,15. A side-position 0 is decorative and does not trigger a wheel. Ordinary data has `f=[]`, `t=1`, `small_game_type=0`, `res.we=0`, `res.wem=0`.

Lucky Wheel requires a center ticket `muls[1]=0` with `we=1`. The center multiplier fallback for line awards is 1. Wheel cash is total bet times `wem`, and line cash remains payable in addition. `round-0313/step-001.response.json` has `wem=100`, no paylines and `o=100`. The other sampled wheel includes a line award and reaches `o=204`, confirming that the total must not be reduced to the wheel award alone. Help lists wheel multipliers 1,3,5,8,10,15,20,30,50,100,200,1000; current generation is limited by the observed 100 and 200.

Lucky Feature is a random paid-start feature, not a scatter-count trigger. It has exactly eight deliveries. The first request is type 1; all following seven requests are type 2. Every feature response has `t=2`, `small_game_type=2`, and object `f={bet,l,st,tt,twa}`. `tt=8` and `st` counts down 7,6,5,4,3,2,1,0. The terminal delivery retains its `f` object with `st=0`; it must not be replaced prematurely by `f=[]`. `f.twa` is cumulative feature cash, while `tw` remains this delivery's cash. Only the first delivery charges the stake (`bg=b*l*5`); subsequent deliveries have `bg=0` and `cg=tw`.

All three horizontal multiplier values contribute during the feature. The eight boards are independent deliveries; observed Wild symbols do not persist to later boards. All 118 observed first feature deliveries are matrix losses (`wa=[]`, `tw=0`), including all 115 training feature starts. Later deliveries include ordinary wins, Big Wins and EPIC Wins and must retain the full supported payout range. Feature purchase exists in enums, but the original `showBuyFree` and `checkBuyFree` methods are empty and captured `buy_free_max_bet=-1`; purchase is not an enabled mode.

The original client determines free status by whether `f` is an object, first delivery by `tt-1 == st`, and completion by `st==0`. On completion it displays the feature total, closes the free-end screen and resumes the start button. A new paid request must obtain a new complete round only after this terminal state.

## Original animation callback verification

The prior claim that BigWin cannot close during free games is incorrect and has been removed. `BigWin.js` has no `isFree` branch. `onTargetNumShow` schedules click permission after one second and automatic `onClosep` five seconds later. `onClosep` executes the supplied `onCloseView` callback after its 0.2-second fade.

`GameMain.showWin` supplies a valid callback for both ordinary and free games: BigWin close → `GameWinAmountDisplay.playTotalWinAnim` → `checkGameOver`. In `GameWinAmountDisplay.playWinAnim`, the `isFree` branch only selects animation/background treatment; the shared final tween executes `onWinAnimFinish` in both modes. If `f.st>0`, `onGameOver` schedules the next type-2 request. At `st=0`, `GameFreeEnded` completes before `onFreeModeFinish` and `gameToIdle` restore play.

BigWin count-up stages take approximately 7.405, 15.02 and 22.685 seconds in total, followed by up to six seconds before automatic closing. Large feature awards must be tested with enough time for these actual animations. Setting later feature awards below `o=10` to avoid BigWin is not a valid repair and would exclude 247 of 805 original training later deliveries. The holdout has a later feature delivery at `o=440`.

The fresh original-page reproduction on 2026-09-14 identified a concrete resource failure: the BigWin sound was absent, and `BigWin.playAudioLevel` threw `Cannot set properties of null (setting 'currentTime')`. The previous archive contained only four of the 33 configured audio clips; 29 audio JSON files and their 29 original MP3 files were missing. Recovering these exact original assets fixes this failure path without changing frontend code or suppressing high awards. Full configuration-driven closure recovered 249 original files, including 23 embedded original atlas files whose complete text hashes match the native version hashes. All 370 import and 194 native configuration entries plus the entry and engine resources are now covered by a 595-path ordinary static HTTP check. Actual animation completion remains subject to the separate current-run browser evidence.

## Observed generation constraints

The empirical model must preserve dependence inside a complete board and between symbols and the horizontal multiplier reel. Independent random cells and manufactured non-winning boards are not supported. State transitions must use a complete newly generated round; original response JSON and fixtures are offline evidence and regression inputs only.

Observed training limits, by genuine dealing entrance:

| Entry | Deliveries | Wild board maximum | Wild maxima by column |
|---|---:|---:|---|
| Ordinary | 2,783 | 5 | 3,2,2 |
| Feature first | 115 | 1 | 1,1,1 |
| Feature later | 805 | 4 | 2,2,2 |
| Wheel | 2 | 1 | 0,0,1 |

The independently generated analysis records per-position and pooled symbol counts with denominators, multiplier triples, board structures, award units and source paths. Training and holdout remain separated. At least 10,000 newly generated complete rounds must be checked before generation acceptance. Increased demo mode sampling must be separate from the Loader's normal generation configuration and must still read complete pre-generated Redis members.

## HTTP and balance fields

- `POST /cp/config/initialData`
- `POST /cp/account/getUserInfo`
- `POST /cp/single_game.Game/initRoom`
- `POST /cp/single_game.Game/gameResult`
- `POST /cp/Goldgame/user_game_history`

Successful response envelope is `{code:0,data,msg:"success",time}`. Game result requests include `token,gid,language,bet,level,type` and the provider signing fields `signapt,expire`. Captured stake choices are `bet_gold="10,3,0.8,0.08"`; minimum observed size is 0.08, level 1, total bet 0.4. Entry fixtures live in `fixtures/2470-Lucky-Night-Market/enter/cp-<component>-<method>/response.json` and paired request files.

`sg` and `start_gold` are the balance immediately before the delivery, `cg=tw-bg`, and `eg=sg+cg`. Every original delivery has a fresh `oid`/`rid` pair; examples show `oid==rid` within a delivery. Numeric identifiers may exceed safe JavaScript integer precision, so new responses should use decimal strings. History data is returned by `user_game_history`; each history item embeds detail deliveries in its `res` array.

Original feature history rows retain first-delivery `tw`, `o`, `f` and `eg`; only outer `cg` aggregates the completed round's net award. The history list displays `bg` and `cg`, and the detail screen reads each selected `res[groupIdx].res.tws`. An outer feature row with `tw=0` is therefore correct even when a later delivery wins. Response totals required by the original history screen are `totals.bet_golds`, `totals.change_golds`, and `totals.total`.

The signing parameter `expire` is `Date.now() + 1000*srvTimeOffset`; it is a time value, not a promised unique request identifier. The original request has no explicit per-request ID. Continuation progression must not be deduplicated solely because this signing value repeats.

## Original locales and entry bytes

`GameQueryConfig.lang` directly reads the URL's `language` parameter, and `LanguageManager` validates it against `LanguageDefine` before loading the game. Accepted original locales are `en-us,hi-in,th-th,zh-cn,zh-hk,vi-vn,id-id,in-telugu,in-marathi,pt-pt,es-es,fr-fr,bn-bd,ko-ko,tr-tr`. Short labels such as `bn` are not native locales and fall back to English. A correct Bengali local entry, for example, includes `language=bn-bd` and `sip=127.0.0.1:<injected port>`.

Supported locale selection does not imply a completely translated dictionary. The live original Hindi resource has `loading2_3=""`; the original truthiness check returns the literal key for an empty value. Hindi `GET_STARTED` is explicitly `GET STARTED`. The live original `zh-cn` dictionary itself contains English entry, help and ticker text (163 of its 175 values match English), so this behavior is not a missing-resource fallback. Original `zh-hk` is translated, including `GET_STARTED="開始遊戲"` and the Lucky Wheel ticker. These original translation gaps are preserved and recorded as non-blocking; no replacement translations are added.

Live official CDN `index.html` and `versionconfig.js` were fetched and byte-checked on 2026-09-14. Both exactly match the restored publish files and the v40 original archive. In particular, native `versionconfig.js` intentionally obtains its API host from `sip` and falls back to `http://127.0.0.1:9500/cp` when `sip` is absent. This fallback is original code, not a local modification. All scripts and callback logic remain unmodified.

The final Java Controller must conform to contract v3, accept the injected 50000–59999 port, and use Redis at 192.168.10.3:6379 database 15 for all demo results. A paid start makes its win/loss and integer-pool selection before reserving one complete member; the seven feature continuations project the same member. An empty pool is an explicit failure. Redis keys, index maintenance, distribution validation, packaging and acceptance follow `复刻要求.md`.

## Original localized Lucky Spin sprite coverage

The original resource configuration supplies the Lucky Spin title sprite for 10 of the 15 supported locales. `vi-vn`, `hi-in`, `zh-cn`, `in-telugu` and `in-marathi` have no registered localized Lucky Spin SpriteFrame. Original LanguageSprite logs the absent cached resource and preserves the existing prefab sprite; a fresh page therefore retains the serialized English title. This is a native localization gap, not a deployment 404, and the branch has no settlement or control-state callback. No fallback asset or frontend patch is added. Detailed original-code, prefab, config and hash evidence is in `reports/2470-Lucky-Night-Market/clean-rebuild/native-language-sprite-gaps.json`. The actual native menu is covered by UI acceptance; an external host sidebar is not present in this standalone original entry and is not invented.

## Clean rebuild model and runtime (2026-09-14)

The sole shared Java source is `server-api/2470-Lucky-Night-Market/src/main/java/com/cpgame/luckynightmarket`. Previous implementation source is not read or ported. The authoritative delivery status records any remaining former compiled artifacts while SMB cleanup is in progress. The fitted model contains aggregate three-symbol reel vectors, adjacent-reel transition counts, joint multiplier-triple counts, and feature payout-band transitions. It contains no full provider boards or complete-round responses. Generation draws correlated columns, judges the new board, and uses bounded rejection for observed entry caps and payout bands. Losses are sampled whole; no symbols are rewritten to force a loss. The last 100 original complete rounds were excluded from fitting.

Core and independent ResultUtil use `b*l` integer payout units. A paid total bet has five units. Redis indices are `PerKeyList_000002470` / `MaryKeyList_000002470`, numeric member=score=integer units; lists are `BetLog:000002470:%06d` / `MaryLog:000002470:%06d`. Normal ranges default 1..375 plus the separately stored zero-loss pool. Special ranges default 25..500 per complete round. The observed 200x wheel is supported by rules and regression but is outside the default special pool range, so it is rejected as a whole, never clipped.

LNM1 ASCII stores mode and each new step's nine symbol digits, three multiplier codes, and wheel prize; no JSON or cash fields are stored. The Controller independently projects cash from this member. Only the first feature response debits the bet; subsequent seven steps use the same member and final `f.st=0`.

Demo first randomly decides 80% win / 20% loss. Winners are selected from ordinary win, Lucky Wheel and Lucky Feature with probability favoring underrepresented modes, then uniformly among existing integer buckets of that mode, then uniformly among actual Redis members in the bucket. Every outcome remains probabilistic; no historical-response rotation or local fallback exists. These weights never modify the formal Loader.

The initial original-page run reproduced a BigWin stall with `app.audio.getEffect(...)=null`, preceded by missing audio import 404s. The original BigWin/free callbacks are intact. Recovery restores genuine original CDN resources without patching frontend logic. Current status and real browser acceptance evidence are authoritative for completion.


## 独立零奖标记（2026-09-15）

普通独立零奖完整局编码为 `LNM1|L|#`。Lucky Feature 保留 F 模式头、全部 8 个步骤及原有分号分隔，只将第 2..8 步中实际派奖为零、没有 Wheel 奖励的独立步骤替换为 `#`。第 1 步承担开启特色的状态，始终保留完整编码；Lucky Wheel 全部保留。数字 `0`、`#1`、特色首步标记或不足/超过 8 步的特色局均拒绝。

特色横向三个倍率的和只作用于当前步，不累加到后一步，不需要 `#N`。解析器按普通/featureLater 入口生成真实零奖步骤；特色入口沿用内嵌拟合模型的条件列向量和联合倍率三元组，遵守位置符号、Wild 和票券上限。生成器初始化时准备 10 个验证通过的备用步骤，运行时最多 5 个新候选，不读取历史响应、不强改符号或奖金字段。

压缩前验证完整局；回读验证模式、步数、每步真实派奖及所有保留步骤不变。因此特色 `st=7..0`、`tt=8`、`f.twa`、首步扣款和后续免费均保持。消费端领取后复用同一份解码事实；幂等请求、恢复房间和 History 保留同一盘面。Redis 为空仍失败。

`RoundCodec.encode` 默认输出标记，`encodeFull` 保留旧完整格式；新解码器同时兼容旧格式和标记格式。GeneratorMain 与 PoolInstaller 的回读检查改为语义一致性，不再要求被标记的零奖盘面完全相同。先更新消费端 `dist/controller.jar`，再用 `dist/loader.jar` 写入新 member。

保持现有奖池筛选配置：当前正式 `range.normal-min=1` 不生成普通零奖整局；需要写入普通 `LNM1|L|#` 时，普通范围须包含 0（例如 `range.normal-min=0`）。特色按完整局总奖筛选，内部零奖步骤不受普通池最小倍率影响。本次没有改动正式配置值、Redis 数据或运行中服务。

两份 JAR 共用 server-api 下的源码，Maven package 会输出到各自 dist。验证结果见 `reports/2470-Lucky-Night-Market/independent-loss-marker/validation.json`。
