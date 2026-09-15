# 2300 Monster Slayer — rule evidence audit (2026-09-08)

Status: NEEDS_EVIDENCE_AND_REPAIR. This audit supersedes prior acceptance claims, without changing the existing executable rulesHash. That hash describes the legacy implementation and is not a verified final rule contract.

| Rule / contract | Original evidence | Existing implementation finding | Status |
| --- | --- | --- | --- |
| 5 columns × 3 rows; paying symbols 1–10 | Original assets/main/index.93d76.js, SlotGameDefine ROW/COL/multipleConfig; original res.ps | Core board size and payout table match the inspected frontend constants | SOURCE_CONFIRMED; full oracle verification pending |
| Paid start type=1; follow type=2; terminal f.nt=0 | 2,051 original request/response pairs, reconstructed in reports/2300-Monster-Slayer/canonical-round-evidence-20260908.json | Collector directory boundaries are not Round boundaries | RECOUNTED |
| Feature modes 1/2/3/4 | Original SlotGameDefine FreeType enum and SlotDataMgr | Core rejects mode 4; service fabricates feature state from step index | NEEDS_REPAIR; mode 4 wire evidence missing |
| Purchase type=3/4/5; frontend multipliers 60/500/200 | Original SlotDataMgr.gameBuyFree / buyMultipleConfig | Service accepts all three using the same generic feature pool | FRONTEND_CONFIRMED; original purchase responses missing |
| Entry-specific distributions | Reconstructed paid/follow requests; evidence-summary-20260908.json excludes 100 complete holdout Rounds and one anomalous chain | Legacy paid symbol weights mix paid starts and follow-up steps; factory samples all cells independently and then edits boards to force outcomes | INVALID_GENERATION_MODEL |
| Complete special progression | Raw f.a/f.h/f.loc/f.r/f.rbs/f.gt/f.nt; max observed Round length 32 deliveries | Factory chooses 6–10 follow steps arbitrarily; service derives locations, hit counts and roles from delivery index | NEEDS_REPAIR |
| Redis selection | User contract: random win/loss then random existing integer multiplier bucket | MonsterSlayerService.spin chooses outcome by paidOrdinal modulo 5/2 | NEEDS_REPAIR |
| Independent verification | User contract: independent ResultUtil/oracle and 100 holdout + 10,000 generated complete Rounds | Old model report accepts constructive losses and only ordinary-win holdout matches; shared RuleCore supplies evaluator predicates | OLD_PASS_REVOKED |
| History | fixtures/2300-Monster-Slayer/history-view/response.json inspected recursively | Contains normal/feature types 1/2 and modes 0/1/2; no purchased or mode-4 result found | INSPECTED; complete UI verification pending |

## Reconstructed sample counts

Main spin corpus: 1,752 collector directories, 2,051 deliveries, 1,388 paid requests and 663 follow requests. These reconstruct 1,388 terminated Rounds: loss 1,137; ordinary win 194; special 57. One special chain has a wallet discontinuity at spin/round-558/step-001.response.json and is quarantined. Trusted complete counts are therefore loss 1,137; win 194; special 56.

Special mode coverage counts Rounds visiting each mode (overlap is intentional): gt=1: 14; gt=2: 42; gt=3: 4; gt=4: 0. Win 194/200, gt=1 14/30 and gt=3 4/30 are SAMPLE_INSUFFICIENT. No new original-site sampling was performed. Old spin-dirty-20260903 has two copies of one ordinary loss response, not new purchase evidence.

## Required existing evidence location

The inspected per-game fixtures, dirty backup and History do not contain original purchase type=3/4/5 request-response chains or a gt=4 reward chain. The programmatic resource download report explicitly states zero Spin/History/paid requests. The frontend constants establish UI branches but do not establish the exact purchased opening state, reward board constraints, payout projection or terminal wire state. Provide the path of the already-collected archive/files covering these branches; do not collect new samples.

## Implementation and validation state

No runtime replay was introduced. No original frontend, unrelated game, common platform or Redis pool was modified. Existing Java code and JARs are retained for repair. The present turn did not rebuild, run 10,000 generated rounds, or perform browser acceptance; prior reports are historical evidence and cannot establish current acceptance.

## Current repair after the initial audit

Rules version: 2300-monster-slayer-v2-partial. The rulesHash now hashes the sorted compact UTF-8 rules-core.json object and is synchronized with both JARs and protocol documents. This is a verified partial contract, not full-game acceptance.

- Ordinary generation now samples weighted whole-reel triples from 1,237 correctly identified paid normal deliveries and rejects illegal boards/outcomes without changing cells. Core decoding enforces no feature placeholders, at most one Scatter, and Scatter only in cells 3–11.
- Paid Redis selection now draws outcome first, then an available integer multiplier randomly. Empty selected outcomes do not switch class. Unsupported special pools fail before member consumption; unsupported purchases fail before claim or charge.
- Loader generation/codec checks finish before Redis connection. No namespace clearing remains. Current default special requests fail preflight before Redis access.
- Shared core additionally checks special delivery continuity and the stricter observed 32-delivery bound, but this does not validate a complete special-state model.
- Controller contract v3 configuration, PORT injection, exact directory case and JAR-relative publish resolution are repaired.
- Final JUnit: 8 tests, 0 failures. Independent oracle: 1,331 original ordinary Rounds and 10,000 new ordinary Rounds; 94 reserved ordinary Rounds verified, 6 reserved special Rounds blocked.
- Real Redis read-only audit returned PONG and 300 ordinary-loss members. No member was consumed or written during the repair; existing cached member provenance is not revalidated by that read.
- Additional entry gap: publish-manifest.json states index.html was borrowed from 2350-Curupira. The original 2300 entry is required; the borrowed page is not accepted as original-page gameplay evidence.

Authoritative current evidence: ordinary-repair-validation-20260908.json, java-build-repair-20260908.json, redis-readonly-repair-20260908.json and current-status.json in reports/2300-Monster-Slayer. Earlier audit findings describe the pre-repair implementation where superseded by this section.

## Continue-repair 2026-09-09

Decision: CONTINUE. Independent inventory confirmed:

- Ordinary complete Rounds: loss 1137 / win 194 (threshold miss>=50, win>=30). KEEP. Do not recapture.
- Purchase complete Rounds: 20 each in `captures/2300-Monster-Slayer/buy-modes/{000002300,100002300,200002300}` with request `type=3/4/5`. KEEP.
- `f.gt=4` wire evidence: buy-modes/100002300/rounds/round-007/step-014.response.json. KEEP.
- Original 2300 `index.html` is in `resources/2300-Monster-Slayer/programmatic-http/collect-20260908/files/static.cpgame.io/v2/2300/index.html` and flattened into `publish/2300-monster-slayer/index.html` (reportv2.js retarget only). Hash differs from 2350-Curupira. KEEP.
- Redis indexes `PerKeyListt_0/1/2` are rejected. Repair writes `PerKeyList_000002300` (ordinary), `MaryKeyList_000002300` + `MaryLog:000002300` (type=3), `PerKeyList_100002300` + `MaryKeyList_100002300` + `MaryLog:100002300` (type=4), `PerKeyList_200002300` + `MaryKeyList_200002300` + `MaryLog:200002300` (type=5).
- Natural special remains SAMPLE_INSUFFICIENT; `generateSpecial` stays blocked.

Loader wrote Redis db 15: 600 ordinary members on `PerKeyList_000002300`/`BetLog:000002300`, 80 purchase members each on `MaryKeyList_000002300`/`MaryLog:000002300`, `MaryKeyList_100002300`+`PerKeyList_100002300`/`MaryLog:100002300`, `MaryKeyList_200002300`+`PerKeyList_200002300`/`MaryLog:200002300`. Legacy `PerKeyListt_0/1/2` deleted. HTTP API playtest on :52311 completed ordinary win+miss and purchase type=3/4/5 twice each to `f.nt=0`. Original page: GET STARTED, two ordinary complete rounds, FEATURE BUY panel, type=3 8-step terminal and type=4 12-step terminal. Natural special remains SAMPLE_INSUFFICIENT.
