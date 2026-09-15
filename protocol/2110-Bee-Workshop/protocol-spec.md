# 2110 Bee Workshop — protocol and implementation handoff

Status: CORE_VALIDATED_BLOCKED_INTEGRATION_AND_EVIDENCE. READY_FOR_ACCEPTANCE=false.
Rules hash: bw2110-lines20-paytable-v2-a7afb966-deal-3d5f4e28
Behavior IDs: BW2110.ORDINARY_LOSS, BW2110.ORDINARY_WIN, BW2110.MYSTERY_BOX, BW2110.FREE_STICKY_SYMBOLS.

## Rule authority and evidence
GameRuleCore is the sole rule authority. Controller and Loader package identical core/model bytes.
ResultUtil independently enumerates payable symbol candidates over the authoritative payline/paytable data.
Source bundle: resources/2110-Bee-Workshop/en/static.cpgame.io/fixed/assets/Game2110/index.1cbca.js (SHA-256 a7afb966d24388b875efa8242f62260a35ad33fb489577d84f6dfa25e2766fce).
Original help: resources/2110-Bee-Workshop/en/static.cpgame.io/fixed/assets/resources/import/06/0671ab352.10e6d.json (SHA-256 de037cf89975786d23776d60d2bf5ac3c976e51a89db4b39fa8b76cacc0fdf92).
The rule-evidence-matrix.json maps original help keys and verified captures.

Board: five reels, three rows, column-major bottom-to-top. Paying symbols 1–7, Wild 8, Scatter 9.
Wild is legal only on reels 2,3,4. Highest award on each of 20 lines pays left-to-right.
Paytable 3/4/5: 1=100/300/1000; 2=30/60/300; 3=10/30/100; 4=8/20/80; 5=6/10/60; 6=5/8/50; 7=5/8/40.
Visual paths (top=0): 11111,22222,00000,21012,01210,22122,00100,10001,12221,21112,01110,11211,11011,12121,10101,21212,01010,22100,00122,12101.

## Complete round and deliveries
Ordinary loss/win and mystery are one paid delivery. Mystery has 5–10 special positions revealing a single paying symbol.
Three/four/five Scatter award 8/10/15 free spins. Observed triggers are 36 three-Scatter and two four-Scatter.
Generation remains capped at the observed four Scatter: 9 or 11 total deliveries including the paid trigger.
Five-Scatter/15-free-spin generation is SAMPLE_INSUFFICIENT despite the explicit original help rule.

Free position 7 is the center seed. Each free step reveals previous sticky cells as one target (1–7), samples compatible empirical background columns, then marks EVERY visible cell matching the current center target sticky. Previous positions persist. No free Scatter is legal. The theoretical sticky maximum is 15; generated per-entry bounds are the stricter observed bounds in deal-model.json.
One History chain verifies frees.st=8,7,6,5,4,3,2,1,0. Terminal settlement creates one grouped History row.

## Empirical model
SHA-256 3d5f4e2805cd49bda59a53a06232f40385e8a0de1f7db1c4263e8e9adf39fa85; packaged com/cpgame/g2110/core/deal-model.json.
Fit uses aggregate whole-column triples, mystery mask/target counts, and separate free entry tables. It stores no runtime fixture paths or complete historical rounds. Whole candidate rejection applies structural, outcome and transition bounds; it does not overwrite a line to force a win.
Mystery/trigger entry selection is held across retries to prevent rejection from changing the selected mode.
Denominators: ordinary 1210, mystery 80, triggers 38, free initial 1, free continuation 7; 48 terminal snapshots support emission bounds only. Initial free support is degenerate because only one source chain exists.
100 complete holdout rounds (50 loss,30 win,20 mystery) are excluded from fitted counts. Prior rule audit inspected the corpus; holdout is a model-fit partition, not a claim of unseen rule discovery.
The 10,000 stress test uses 2,500 per kind, not the production mixture or an RTP calibration claim.

## APIs and session money
POST /cp/config/initialData, /cp/account/getUserInfo, /cp/single_game.Game/initRoom, /cp/activity/getActivity and /cp/config/setGameConfig implement the observed shell surface.
POST /cp/single_game.Game/gameResult advances one delivery.
POST /cp/goldgame/single_game_user_gold_history and /cp/goldgame/single_game_user_history return daily totals and grouped completed results.
GET /api/balance, /api/session and /health expose integration state. Unknown routes are 404.

Spin form fields include token, bet_gold, level, gid, language and ai. A paid wager is pinned for every continuation.
Stake=bet_gold*level*20; default .02*10*20=4. Each line tw=odd*bet_gold*level.
Response envelope: {code,data,msg,time}. props.prop is the board. type=1 is paid; type=2/small_game_type=2 is free.
Free st decreases to zero, tt is award count, twa is cumulative free winnings.
The member is claimed once at paid entry and retained in memory for all continuations; initRoom returns the last delivered state without advancing an unfinished round. Process-restart session persistence is not implemented.
Captured game_info.name is Magic Scroll for gid2110; this source value is preserved. Captured buy_free_max_bet=-1; no purchase route is invented.

## Redis contract
192.168.10.3:6379 DB15; prefix cpgame:v3:2110:deal-3d5f4e28:units.
Production first selects LOSS/WIN from captured paid-entry weights 1189/239 (denominator1428), then chooses uniformly among existing integer buckets for that outcome and atomically RPOPs one complete member.
Integers are exact payout units based on bet-size*level. Divide by20 for total-stake odds; this explicitly preserves fractional total-stake awards instead of flooring them.
BW1|kindOrdinal|15digits.hexMask~... is printable ASCII structural data, not full JSON. Rules and independent payout are rechecked.
Empty selected outcome/bucket is explicit 503 PREGENERATED_CACHE_EMPTY, without fallback or reselection.
Testing-only playthrough_kind requires token prefix playthrough- and consumes separately preloaded kind pools.
Formal Loader uses SecureRandom, rejects seed properties, and defaults clear=false. It uses HMSET for server compatibility. Explicit clearing only follows known indexes under this game's new version prefix; never FLUSHDB or other-game deletion.

## Launch and acceptance
Platform descriptor: server-api/2110-Bee-Workshop/demo-controller.properties. Jar: dist/controller.jar. Runtime config: dist/controller.properties.
Accept --config, --port and --publish (split or equals); PORT environment also accepted. Port must be50000–59999; there is no default listener port.
Loader dist contains one all-dependency redis-loader.jar, generator.properties and self-locating Windows.cmd supporting --no-pause.
Canonical offline validation: build-and-test.sh. Legacy GenerationValidationMain and older reports are superseded, not acceptance evidence.

Verified: 1476 raw payout responses; 100 holdout complete rounds; one full History free chain; 10000 generated rounds; v3 parser; 850 Redis members and metadata read back.
Not verified: platform startup currently fails to inject a port; browser blocked the LAN page; fresh original-page static closure, Spin animation recovery, language and History UI completion.
Original entry provenance remains unverified. publish/index.html SHA-256=7bb9c908b6831848e3f3d9a2af56fe4386adcf89db0752c2b4fd3f4fa2184739, 8331bytes; it was not modified in this recovery. The manifest's source index.html returns ENOENT. Referenced spin-full-from-box.tgz is unlocated at the three inspected exact paths.
Sample counts cover the indexed corpus: loss1189, ordinary win101, mystery100, triggers38, free snapshots48. History confirms one free chain. Do not count triggers/snapshots as complete free rounds or use generated data to meet source targets.

## History correction verified in candidate only (2026-09-08 continuation)
Delivery status: CANDIDATE_VALIDATED_PROMOTION_BLOCKED.
The captured detail request includes day, page_size=30 and page=1. Its response returns30 rows while statistics.total_bet_gold=2480. The seven-day response totals5752 across daily rows2480 and3272. Statistics therefore cover the selected day independently of the displayed page.
ControllerMain source and target/controller-history-candidate.jar now retain all completed session rounds, select the requested UTC day, paginate with page/page_size, report full-day totals on every page, and return seven daily rows including zero days. Invalid History query values return400.
Regression with31 actual Redis loss rounds passed pages30/1/0, each with total_bet_gold124, correct seven daily rows and wallet99876. The candidate also passed four modes twice, two additional10-free rounds (11 deliveries each), and empty-cache branches.
The formal dist/controller.jar remains the previous version because atomic replacement returned Errno16 Resource busy. Exact ownership is unverified: visible local lsof/process queries found no matching handle/process, the path is an SMB3.1.1 share at192.168.10.3, and the game-specific stop endpoint says the game is not running. Do not infer a classloader hold from this error alone; do not force overwrite or stop a shared host.
The candidate SHA256 is386e58bac812bd4d7505a0824a877ed2713b11653145a3c428e0673747bdcea8. See reports/2110-Bee-Workshop/controller-lock-diagnostic-20260908.json and history-pagination-validation-20260908.json.
The production static handler also matched all491 existing manifest files (57307129 bytes); actual HTTP entry remained404. These checks do not establish original HTML provenance or browser animation acceptance.
