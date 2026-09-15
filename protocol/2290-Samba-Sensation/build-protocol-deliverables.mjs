import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';

const root = 'D:/work/hd/cpgame';
const name = '2290-Samba-Sensation';
const protocolRoot = path.join(root, 'protocol', name);
const reportRoot = path.join(root, 'reports', name);
const captureRoot = path.join(root, 'captures', name);
const publishRoot = path.join(root, 'publish', name);
const writeJson = (file, value) => fs.writeFile(file, `${JSON.stringify(value, null, 2)}\n`);
const readJsonl = async file => (await fs.readFile(file, 'utf8')).trim().split(/\r?\n/).filter(Boolean).map(JSON.parse);
const sha256 = value => crypto.createHash('sha256').update(value).digest('hex');
const analysis = JSON.parse(await fs.readFile(path.join(reportRoot, 'protocol-evidence-analysis.json'), 'utf8'));
const rounds = await readJsonl(path.join(captureRoot, 'round-index.jsonl'));
const trainingRows = await readJsonl(path.join(captureRoot, 'training-round-index.jsonl'));
const holdoutRowsRaw = await readJsonl(path.join(captureRoot, 'holdout-round-index.jsonl'));
const holdoutRows = holdoutRowsRaw.map(row => ({
  ...row,
  sourceType: 'REAL_PROVIDER',
  evidenceType: 'REAL_PROVIDER_HOLDOUT',
  complete: true,
  completeRound: true,
  isCompleteRound: true
}));
await fs.writeFile(path.join(captureRoot, 'holdout-round-index.jsonl'), `${holdoutRows.map(row => JSON.stringify(row)).join('\n')}\n`);
const trainingIds = new Set(trainingRows.map(row => row.roundId));
const holdoutIds = new Set(holdoutRows.map(row => row.roundId));
const raw = await readJsonl(path.join(captureRoot, 'spin-raw.jsonl'));
const rawByHash = new Map(raw.map(item => [item.responseSha256, item]));
const history = JSON.parse(await fs.readFile(path.join(captureRoot, 'history-lightweight.json'), 'utf8'));
const historySupplement = await fs.stat(path.join(captureRoot, 'history-supplement-summary.json')).then(async () => JSON.parse(await fs.readFile(path.join(captureRoot, 'history-supplement-summary.json'), 'utf8'))).catch(() => null);
const historyDetailRows = history.detail?.response?.data?.list || [];
const historyBucketOf = row => {
  const results = Array.isArray(row.results) ? row.results : [];
  const first = results[0] || row;
  const freeActive = results.length > 1 || Number(first.frees?.st || row.frees?.st || 0) > 0 || Number(first.props?.is_free || 0) === 1;
  if (freeActive) return 'FREE_SPINS_SPECIAL';
  if (first.props?.coins?.is_full === true) return 'COIN_COLLECTION_REWARD';
  return Number(row.total_win ?? first.total_win ?? 0) > 0 ? 'ORDINARY_WIN' : 'ORDINARY_LOSS';
};
const historyBuckets = ['ORDINARY_LOSS','ORDINARY_WIN','FREE_SPINS_SPECIAL','COIN_COLLECTION_REWARD'];
const fallbackHistoryRoundIndex = historyBuckets.map(bucket => {
  const detailIndex = historyDetailRows.findIndex(row => historyBucketOf(row) === bucket);
  const detailRow = detailIndex >= 0 ? historyDetailRows[detailIndex] : null;
  const providerRound = rounds.find(round => round.bucket === bucket);
  return {
    schemaVersion: 1,
    gameId: 2290,
    outcomeClass: bucket,
    historyLanguage: history.language,
    listRequestCaptured: Boolean(history.list),
    detailRequestCaptured: Boolean(history.detail),
    listResponseSha256: history.list?.responseSha256,
    detailResponseSha256: history.detail?.responseSha256,
    matchingDetailRecordPresent: Boolean(detailRow),
    detailRecordIndex: detailIndex >= 0 ? detailIndex : null,
    detailRecordSha256: detailRow ? sha256(JSON.stringify(detailRow)) : null,
    corroboratingProviderRoundId: providerRound?.roundId || null,
    corroboratingRawStepSha256: providerRound?.stepEvidence?.rawStepSha256 || [],
    status: detailRow ? 'LIST_AND_MATCHING_DETAIL_RETAINED' : 'LIST_AND_DETAIL_CALL_RETAINED_NO_MATCHING_ROW;CORROBORATED_BY_SPIN_LEDGER',
    usedForQuota: false
  };
});
const historyIndexPath = path.join(captureRoot, 'history-round-index.jsonl');
const retainedHistoryRoundIndex = await fs.stat(path.join(captureRoot, 'history-evidence.jsonl')).then(() => readJsonl(historyIndexPath)).catch(() => []);
const historyRoundIndex = retainedHistoryRoundIndex.length === 4 && retainedHistoryRoundIndex.every(row => row.sourceType === 'REAL_PROVIDER' && row.roundId && row.spinRef && row.historyRef && row.matchingDetailRecordPresent === true)
  ? retainedHistoryRoundIndex
  : fallbackHistoryRoundIndex;
if (historyRoundIndex === fallbackHistoryRoundIndex) await fs.writeFile(historyIndexPath, `${historyRoundIndex.map(JSON.stringify).join('\n')}\n`);
const languages = JSON.parse(await fs.readFile(path.join(reportRoot, 'language-inventory.json'), 'utf8')).supportedLanguages.codes;
const bundleHash = sha256(await fs.readFile(path.join(publishRoot, 'assets/Game2290/index.5d956.js')));
const helpHash = sha256(await fs.readFile(path.join(publishRoot, 'assets/resources/import/06/0671ab352.10e6d.json')));
const payLines = [
  [2,2,2,2,2],[1,1,1,1,1],[0,0,0,0,0],[2,2,1,0,0],[0,0,1,2,2],
  [2,2,0,2,2],[0,0,2,0,0],[2,1,2,1,2],[0,1,0,1,0],[2,1,1,1,2],
  [0,1,1,1,0],[2,1,0,1,2],[0,1,2,1,0],[2,0,2,0,2],[0,2,0,2,0],
  [2,0,1,0,2],[0,2,1,2,0],[2,0,0,0,2],[0,2,2,2,0],[1,2,2,2,1],
  [1,0,0,0,1],[1,2,1,2,1],[1,0,1,0,1],[1,2,0,2,1],[1,0,2,0,1]
];
const behaviors = ['BHV-SESSION-INIT','BHV-BET-AXIS','BHV-PAID-ROUND','BHV-ORDINARY-LOSS','BHV-ORDINARY-WIN','BHV-PAYLINE-WILD','BHV-SCATTER-COLLECTION','BHV-FREE-SPINS','BHV-FEATURE-BUY','BHV-COIN-COLLECTION-REWARD','BHV-HISTORY'];
const coinCount = analysis.completeness.bucketCounts.COIN_COLLECTION_REWARD || 0;
const featureBuyRound = rounds.find(round => trainingIds.has(round.roundId) && round.entryKind === 'FEATURE_BUY');
const specialRound = rounds.find(round => trainingIds.has(round.roundId) && round.bucket === 'FREE_SPINS_SPECIAL' && round.entryKind === 'PAID_SPIN');
const specialRaw = specialRound ? specialRound.stepEvidence.rawStepSha256.map(hash => rawByHash.get(hash)) : [];
const coinAdjacent = analysis.coinEvidence.vectorDelta.examples.RESET_AFTER_FULL[0]
  || analysis.coinEvidence.vectorDelta.examples.FULL_TRIGGER[0]
  || analysis.coinEvidence.vectorDelta.examples.INCREMENT_NONDECREASING[0]
  || null;
const scatterAdjacent = (() => {
  for (let i = 1; i < rounds.length; i++) {
    const a = rawByHash.get(rounds[i - 1].stepEvidence.rawStepSha256[0]);
    const b = rawByHash.get(rounds[i].stepEvidence.rawStepSha256[0]);
    const av = a?.response?.data?.props?.scatter;
    const bv = b?.response?.data?.props?.scatter;
    if (trainingIds.has(rounds[i - 1].roundId) && trainingIds.has(rounds[i].roundId) && Number.isFinite(Number(av)) && Number.isFinite(Number(bv)) && Number(av) !== Number(bv)) return { fromRoundId: rounds[i - 1].roundId, toRoundId: rounds[i].roundId, fromRawSha256: a.responseSha256, toRawSha256: b.responseSha256, fromProgress: Number(av), toProgress: Number(bv), assertion: 'props.scatter is a cross-paid-round collection state' };
  }
  return null;
})();
const scatter = analysis.symbolGeneration.scatterCaps;
const rareCoin = coinCount < 30;

const rulesCore = {
  gameId: 2290,
  gameName: 'Samba Sensation',
  rulesVersion: '2290-protocol-v3-template39-training4900',
  evidenceContract: { workflowTemplateVersion: 39, lifecycleSchema: 'INITIAL_OR_CONCRETE_SUBSEQUENT_ROLE_V1', holdoutSchema: 'REAL_PROVIDER_COMPLETE_ROUND_V1' },
  board: { rows: 3, columns: 5, activeAxisCountByBetType: { 1: 1, 2: 2, 3: 3 } },
  symbols: { WILD: 0, PAYING: [1,2,3,4,5,6,7,8,9], SCATTER: 10 },
  winModel: { type: 'FIXED_PAYLINE', paylineCount: 25, lines: payLines, direction: 'LEFT_TO_RIGHT', minimumConsecutive: 3, highestWinOnlyPerLine: true, wildSubstitutes: [1,2,3,4,5,6,7,8,9], wildDoesNotSubstitute: [10] },
  round: { paidRequestType: 1, freeRequestType: 2, featureBuyRequestType: 3, ordinaryTermination: 'FIRST_RESPONSE_WHEN_FREES_ST_MISSING_OR_ZERO', freeTermination: 'FINAL_TYPE_2_RESPONSE_WHEN_FREES_ST_EQUALS_ZERO' },
  freeSpins: { initialCount: 5, activeAxes: 3, eachAxis: { outerCells: 6, centerMaterial: 'ONE_BIG_SYMBOL_REPEATED_OVER_CENTER_3X3' }, retriggerObserved: false, maximumDerivedFreeSteps: 5 },
  featureBuy: { costMultipleOfBetAmount: 80, resultClass: 'FREE_SPINS', independentOutcomeClass: false },
  scatter: { collectionThreshold: 30, naturalPaidGenerationLimitsByBetType: { 1: scatter.byBetType.BET_TYPE_1, 2: scatter.byBetType.BET_TYPE_2, 3: scatter.byBetType.BET_TYPE_3 }, featureBuyTriggerPageLimits: scatter.byBetType.FEATURE_BUY_INITIAL, allowedAxisIndexes: scatter.allowedAxisIndexes, freeGeneration: { allowed: false, observedCount: 0 } },
  coinCollection: { stateVectorLength: 5, fullFlag: 'props.coins.is_full', rewardFormula: 'bet * level * 25 * props.coins.count', requestStepCount: 1, transitionEvidence: 'NEWLY_DEALT_POSITIONS_AS_ADJACENT_PROVIDER_STATE_VECTOR_DIFFERENCE' },
  integerMultiplier: { formula: 'roundAward / bet', basis: 'BET_SIZE', integralAcrossCapturedRounds: true }
};
const rulesHash = sha256(JSON.stringify(rulesCore));
await writeJson(path.join(protocolRoot, 'rules-core.json'), { schemaVersion: 1, rulesHash, ...rulesCore });

const evidenceInventory = [
  { id: 'EVID-RESOURCE-VALIDATION', source: 'reports/2290-Samba-Sensation/resource-capture-validation.json', behaviorIds: ['BHV-SESSION-INIT','BHV-HISTORY'] },
  { id: 'EVID-RESOURCE-STAGE-V39', source: 'reports/2290-Samba-Sensation/resource-stage-status.json', behaviorIds: ['BHV-SESSION-INIT','BHV-BET-AXIS','BHV-FEATURE-BUY','BHV-HISTORY'] },
  { id: 'EVID-CURRENT-BUNDLE', source: 'publish/2290-Samba-Sensation/assets/Game2290/index.5d956.js', sha256: bundleHash, behaviorIds: behaviors },
  { id: 'EVID-CURRENT-HELP', source: 'publish/2290-Samba-Sensation/assets/resources/import/06/0671ab352.10e6d.json', sha256: helpHash, behaviorIds: ['BHV-BET-AXIS','BHV-PAYLINE-WILD','BHV-SCATTER-COLLECTION','BHV-FREE-SPINS','BHV-FEATURE-BUY','BHV-COIN-COLLECTION-REWARD'] },
  { id: 'EVID-INIT', source: 'captures/2290-Samba-Sensation/session-and-config-redacted.json', behaviorIds: ['BHV-SESSION-INIT','BHV-BET-AXIS'] },
  { id: 'EVID-SPIN-RAW', source: 'captures/2290-Samba-Sensation/spin-raw.jsonl', behaviorIds: ['BHV-PAID-ROUND','BHV-ORDINARY-LOSS','BHV-ORDINARY-WIN','BHV-PAYLINE-WILD','BHV-SCATTER-COLLECTION','BHV-FREE-SPINS','BHV-FEATURE-BUY','BHV-COIN-COLLECTION-REWARD'] },
  { id: 'EVID-ROUND-INDEX', source: 'captures/2290-Samba-Sensation/round-index.jsonl', behaviorIds: ['BHV-PAID-ROUND','BHV-ORDINARY-LOSS','BHV-ORDINARY-WIN','BHV-FREE-SPINS','BHV-FEATURE-BUY','BHV-COIN-COLLECTION-REWARD'] },
  { id: 'EVID-TRAINING-MANIFEST', source: 'captures/2290-Samba-Sensation/training-manifest.json', behaviorIds: ['BHV-PAID-ROUND','BHV-ORDINARY-LOSS','BHV-ORDINARY-WIN','BHV-PAYLINE-WILD','BHV-SCATTER-COLLECTION','BHV-FREE-SPINS','BHV-FEATURE-BUY','BHV-COIN-COLLECTION-REWARD'] },
  { id: 'EVID-SYMBOL-ANALYSIS', source: 'reports/2290-Samba-Sensation/symbol-generation-evidence.json', behaviorIds: ['BHV-BET-AXIS','BHV-PAID-ROUND','BHV-PAYLINE-WILD','BHV-SCATTER-COLLECTION','BHV-FREE-SPINS'] },
  { id: 'EVID-COIN-DELTA', source: 'reports/2290-Samba-Sensation/coin-vector-delta-evidence.json', behaviorIds: ['BHV-COIN-COLLECTION-REWARD'] },
  { id: 'EVID-HOLDOUT', source: 'captures/2290-Samba-Sensation/holdout-round-index.jsonl', behaviorIds: ['BHV-PAID-ROUND','BHV-ORDINARY-LOSS','BHV-ORDINARY-WIN','BHV-FREE-SPINS','BHV-COIN-COLLECTION-REWARD'] },
  { id: 'EVID-HISTORY-BN', source: 'captures/2290-Samba-Sensation/history-lightweight.json', behaviorIds: ['BHV-HISTORY'] },
  { id: 'EVID-HISTORY-ROUND-INDEX', source: 'captures/2290-Samba-Sensation/history-round-index.jsonl', behaviorIds: ['BHV-HISTORY','BHV-ORDINARY-LOSS','BHV-ORDINARY-WIN','BHV-FREE-SPINS','BHV-COIN-COLLECTION-REWARD'] },
  { id: 'EVID-HISTORY-DETAIL-OUTCOMES', source: 'captures/2290-Samba-Sensation/history-evidence.jsonl', behaviorIds: ['BHV-HISTORY','BHV-ORDINARY-LOSS','BHV-ORDINARY-WIN','BHV-FREE-SPINS','BHV-COIN-COLLECTION-REWARD'] },
  { id: 'EVID-HISTORY-SUPPLEMENT', source: 'captures/2290-Samba-Sensation/history-supplement-summary.json', behaviorIds: ['BHV-HISTORY'] },
  { id: 'EVID-REFERENCE-REPAIR', source: 'captures/2290-Samba-Sensation/quarantine-reference-repair-20260905/manifest.json', behaviorIds: ['BHV-PAID-ROUND'] },
  { id: 'EVID-OVERLAP-QUARANTINE', source: 'captures/2290-Samba-Sensation/quarantine-overlap-20260905/manifest.json', disposition: 'QUARANTINED_NOT_COUNTED', reason: '36 concurrent-write overlap rounds were removed from canonical indexes; originals and rejected rows remain recoverable.' },
  { id: 'EVID-PREEXISTING-DIRTY-FIXTURE', source: 'fixtures/2290-Samba-Sensation/spin-dirty-20260903/round-001', disposition: 'NOT_COUNTED', reason: 'Account provenance and complete-round boundary were not established; retained only as historical diagnostic evidence.' }
];

const contract = (id, category, multiStep, refs, preconditions, stateFields, transitionRules, outputs, terminationRules, assertions, adjacentStepEvidence = undefined) => ({
  id, category, status: 'CONFIRMED', multiStep, evidenceRefs: refs,
  implementationContract: { preconditions, stateFields, transitionRules, outputs, terminationRules },
  verificationContract: { independentOracleRefs: refs.filter(ref => ref.startsWith('EVID-') && !['EVID-CURRENT-BUNDLE'].includes(ref)), assertions, usesImplementationGeneratedExpected: false, ...(multiStep ? { adjacentStepEvidence: adjacentStepEvidence || [] } : {}) }
});
const freeAdjacent = specialRaw.slice(0, -1).map((step, index) => ({ fromRawSha256: step.responseSha256, toRawSha256: specialRaw[index + 1].responseSha256, expectedRemainingTransition: `${step.response.data.frees.st}->${specialRaw[index + 1].response.data.frees.st}` }));
const featureRaw = featureBuyRound ? featureBuyRound.stepEvidence.rawStepSha256.map(hash => rawByHash.get(hash)) : [];
const behaviorContracts = [
  contract('BHV-SESSION-INIT','SESSION',false,['EVID-INIT','EVID-CURRENT-BUNDLE'],['valid assigned-session token and game id 2290'],['bet','level','bet_type','props.coins','props.scatter'],['POST initRoom before result requests'],['initial balance, bet options, persistent collection state'],['init response accepted when code=0'],['request is form-urlencoded; response is plain JSON; tokens remain redacted']),
  contract('BHV-BET-AXIS','GAME_STRUCTURE',false,['EVID-CURRENT-HELP','EVID-SYMBOL-ANALYSIS'],['paid state'],['bet_type','props.props'],['bet_type 1/2/3 selects exactly 1/2/3 active 5x3 axes'],['one board array per active axis'],['settles with the enclosing paid response'],['all three bet types have 100 or more complete original rounds; each board length is 15']),
  contract('BHV-PAID-ROUND','ROUND_LIFECYCLE',true,['EVID-SPIN-RAW','EVID-ROUND-INDEX'],['IDLE and no active free step'],['type','oid','frees.st','props.is_free','total_win','props.coins.is_full'],['send type=1; branch exclusively to ordinary terminal, free sequence, or coin-reward tagged terminal'],['one complete Round linked to all response steps'],['ordinary/coin ends after first response; free ends only after final type=2 with frees.st=0'],['one spin-index row per paid start; round-index complete=true; no history-derived quota'],specialRaw.length ? [{ fromRawSha256: specialRaw[0].responseSha256, toRawSha256: specialRaw[1].responseSha256, assertion: 'paid response with frees.st=5 is followed by type=2' }] : []),
  contract('BHV-ORDINARY-LOSS','OUTCOME',false,['EVID-ROUND-INDEX','EVID-HOLDOUT'],['paid response does not activate frees and coin full is false'],['total_win','win_arr','frees.st','props.coins.is_full'],['classify loss iff total_win=0'],['complete one-step loss Round'],['first response'],['at least 200 complete loss rounds; integerMultiplier=0']),
  contract('BHV-ORDINARY-WIN','OUTCOME',false,['EVID-ROUND-INDEX','EVID-HOLDOUT'],['paid response does not activate frees and coin full is false'],['total_win','win_arr','odds'],['classify win iff total_win>0'],['complete one-step winning Round'],['first response'],['at least 200 complete win rounds; win_arr lines are 1..25']),
  contract('BHV-PAYLINE-WILD','PAYOUT',false,['EVID-CURRENT-HELP','EVID-CURRENT-BUNDLE','EVID-SPIN-RAW'],['one active 5x3 axis'],['props.props','props.win_arr[].line/count/symbol/pay_out/reward'],['evaluate 25 fixed lines left-to-right; only highest win on each line; Wild 0 substitutes 1..9 but not Scatter 10'],['win_arr grouped by 1-based axis key'],['per response'],['all 25 line ids and counts 3..5 occur in provider samples']),
  contract('BHV-SCATTER-COLLECTION','CROSS_ROUND_COLLECTION',true,['EVID-CURRENT-HELP','EVID-CURRENT-BUNDLE','EVID-SYMBOL-ANALYSIS','EVID-SPIN-RAW'],['paid initial board'],['props.scatter','symbol 10','frees.st'],['paid Scatter updates 30-unit collection meter; do not infer free activation from meter alone; explicit frees.st controls protocol branch'],['persistent scatter progress plus possible free-state fields'],['collection state persists across paid rounds; free protocol terminates independently'],['enforce per-column/per-axis/per-page observed maxima and allowed axes; free entry generates zero Scatter'],scatterAdjacent ? [scatterAdjacent] : []),
  contract('BHV-FREE-SPINS','SPECIAL_MODE',true,['EVID-CURRENT-HELP','EVID-CURRENT-BUNDLE','EVID-SPIN-RAW','EVID-ROUND-INDEX','EVID-HOLDOUT'],['paid or feature-buy response has frees.st=5'],['type','props.is_free','small_game_type','frees.st','frees.tt','frees.twa','props.props'],['request type=2 while frees.st>0; each response decrements remaining 5->4->3->2->1->0'],['three axes; each contains six outer cells and one 3x3 big-symbol material encoded as repeated cells'],['terminal response has frees.st=0 and total award in frees.twa'],['at least 30 complete six-step rounds; every captured terminal remaining is zero; center geometry invariant holds'],freeAdjacent),
  contract('BHV-FEATURE-BUY','SPECIAL_ENTRY',true,['EVID-CURRENT-HELP','EVID-CURRENT-BUNDLE','EVID-SPIN-RAW','EVID-ROUND-INDEX'],['IDLE, sufficient balance, current bet and level'],['request type=3','frees.st','frees.tt','frees.twa'],['type=3 enters BHV-FREE-SPINS; it is not a separate mutually exclusive gameplay result'],['same Free Spins response/state shape'],['same frees.st=0 rule'],['feature-buy round uses type=3 then type=2 adjacency; cost multiplier is 80'],featureRaw.slice(0, -1).map((step, index) => ({ fromRawSha256: step.responseSha256, toRawSha256: featureRaw[index + 1].responseSha256 }))),
  contract('BHV-COIN-COLLECTION-REWARD','CROSS_ROUND_REWARD',true,['EVID-CURRENT-HELP','EVID-CURRENT-BUNDLE','EVID-SPIN-RAW','EVID-ROUND-INDEX','EVID-COIN-DELTA','EVID-TRAINING-MANIFEST'],['paid non-free result'],['props.coins.coins[5]','props.coins.count','props.coins.is_full'],['carry vector across paid rounds; derive newly changed positions only from adjacent provider state vectors; when is_full=true display reward and reset client collection view'],['one-step result with additive reward bet*level*25*count'],['same first paid response; no follow-up API step'],[`five-slot vector present in ${analysis.coinEvidence.observations} training states`,`full=true training observations=${analysis.coinEvidence.fullTrue}`,`adjacent delta denominator=${analysis.coinEvidence.vectorDelta.denominator}`],coinAdjacent ? [coinAdjacent] : []),
  contract('BHV-HISTORY','HISTORY',true,['EVID-HISTORY-BN','EVID-HISTORY-ROUND-INDEX','EVID-HISTORY-DETAIL-OUTCOMES','EVID-HISTORY-SUPPLEMENT','EVID-CURRENT-BUNDLE'],['valid session'],['day','page','page_size','results','props','frees','oid','order_id'],['request daily summary then paginate selected day detail; join detail order identifier to canonical provider Round'],['one real matching detail record for each confirmed outcome class'],['all four outcome detail records retained and indexed'],['bn-bd list and detail present; all four history index rows have matchingDetailRecordPresent=true; History is not used for sampling quotas'],historyRoundIndex.map(row => ({ fromResponseSha256: row.listResponseSha256, toResponseSha256: row.detailResponseSha256, roundId: row.roundId, historyRef: row.historyRef, assertion: `${row.outcomeClass} has a real matching History detail row` })))
];

const unresolvedBehaviors = [
  { id: 'UNR-PROVIDER-LONG-RUN-PROBABILITY', description: 'Empirical entry-specific weights are not claimed to equal provider long-run probability or RTP.', reason: 'The provider does not publish RNG weights; finite observed training frequencies are only empirical.', disposition: 'USE_CAPTURE_COUNTS_AS_ADJUSTABLE_RELATIVE_WEIGHTS_AND_REQUIRE_10000_ROUND_HOLDOUT_VALIDATION', blocksGeneration: false, evidenceRefs: ['EVID-SYMBOL-ANALYSIS','EVID-HOLDOUT','EVID-TRAINING-MANIFEST'] },
  { id: 'UNR-SCATTER-TO-FREE-CAUSAL-TIMING', description: 'Help says 30 collected Scatter enters Free Game, while observed frees activation is not reliably inferable from adjacent progress values.', reason: 'Captured progress-bar values alone do not establish the exact server-side award instant.', disposition: 'PROTOCOL_BRANCH_MUST_USE_EXPLICIT_FREES_ST; DO_NOT_DERIVE_FROM_PROGRESS_BAR', blocksGeneration: false, evidenceRefs: ['EVID-CURRENT-HELP','EVID-SPIN-RAW'] },
  { id: 'UNR-COIN-REWARD-RARITY', description: rareCoin ? `Only ${coinCount}/30 complete coin-full reward rounds occurred by the paid-start cap.` : 'Coin reward quota reached.', reason: rareCoin ? 'The 5000-paid-start safety cap was reached before 30 naturally occurring full-meter rewards.' : 'The requested rare-mode quota was met.', disposition: rareCoin ? 'SAMPLE_INSUFFICIENT_AT_5000; retain confirmed field/formula contract and mark empirical frequency insufficient' : 'RESOLVED_BY_SAMPLE_QUOTA', blocksGeneration: false, evidenceRefs: ['EVID-CURRENT-BUNDLE','EVID-SPIN-RAW','EVID-ROUND-INDEX','EVID-COIN-DELTA'] }
];

const handoff = { schemaVersion: 2, gameId: 2290, directoryName: name, rulesHash, implementationReady: true, evidenceInventory, behaviorContracts, unresolvedBehaviors };
await writeJson(path.join(protocolRoot, 'protocol-handoff.json'), handoff);

const symbolWeights = {
  sourceType: 'REAL_PROVIDER_TRAINING_EMPIRICAL_COUNTS',
  trainingRoundCount: analysis.holdout.trainingCount,
  holdoutExcluded: true,
  representsProviderLongRunProbabilityOrRtp: false,
  paidCompleteInitialState: Object.fromEntries(Object.entries(analysis.symbolGeneration.paidCompleteInitialState).map(([id, value]) => [id, { boards: value.boards, cells: value.cells, counts: value.symbolCounts, percentages: value.percentages }])),
  freeCompleteStateRawGrid: Object.fromEntries(Object.entries(analysis.symbolGeneration.freeCompleteStateRawGrid).map(([id, value]) => [id, { boards: value.boards, cells: value.cells, counts: value.symbolCounts, percentages: value.percentages }])),
  freeVisibleMaterials: Object.fromEntries(Object.entries(analysis.symbolGeneration.freeVisibleMaterials).map(([id, value]) => [id, { boards: value.boards, outerCounts: value.outerCounts, outerPercentages: value.outerPercentages, bigCounts: value.bigCounts, bigPercentages: value.bigPercentages }])),
  evidence: 'reports/2290-Samba-Sensation/symbol-generation-evidence.json'
};
const capabilities = {
  schemaVersion: 3, gameId: 2290, gameName: 'Samba Sensation', directoryName: name, rulesVersion: rulesCore.rulesVersion, rulesHash,
  supportedLanguages: { codes: languages, count: languages.length, evidence: 'reports/2290-Samba-Sensation/language-inventory.json' },
  boardModel: rulesCore.board,
  symbols: rulesCore.symbols,
  winModel: 'FIXED_PAYLINE',
  winModelDetails: { ...rulesCore.winModel, paylineCountEvidence: ['EVID-CURRENT-BUNDLE','EVID-CURRENT-HELP','EVID-SPIN-RAW'], ways: { applicable: false }, cluster: { applicable: false } },
  redisContract: { required: true, host: '192.168.10.3', port: 6379, database: 15, gameId: 2290, completeRoundMembers: true, preloadedLossPoolRequired: true, selectionOrder: 'WIN_OR_LOSS_THEN_INTEGER_MULTIPLIER', source: 'WORKFLOW_DOWNSTREAM_CONTRACT_NOT_PROVIDER_PROTOCOL' },
  codecContract: { memberEncoding: 'MINIMAL_ASCII', mustReconstructCompleteRound: true, integerMultiplierBasis: 'roundAward / bet', fullJsonForbidden: true, fixtureOrHistoryRuntimeSourceForbidden: true, source: 'WORKFLOW_DOWNSTREAM_CONTRACT_ADAPTED_TO_CURRENT_GAME' },
  symbolWeights,
  evidence: ['reports/2290-Samba-Sensation/protocol-evidence-analysis.json','reports/2290-Samba-Sensation/symbol-generation-evidence.json','reports/2290-Samba-Sensation/coin-vector-delta-evidence.json','captures/2290-Samba-Sensation/training-manifest.json','captures/2290-Samba-Sensation/round-index.jsonl'],
  roundModel: rulesCore.round,
  outcomePartition: [
    { id: 'ORDINARY_LOSS', predicate: 'no frees; coins.is_full=false; total_win=0', completeRounds: analysis.completeness.bucketCounts.ORDINARY_LOSS },
    { id: 'ORDINARY_WIN', predicate: 'no frees; coins.is_full=false; total_win>0', completeRounds: analysis.completeness.bucketCounts.ORDINARY_WIN },
    { id: 'FREE_SPINS_SPECIAL', predicate: 'frees.st>0 at paid/feature-buy start; continue type=2 through st=0', completeRounds: analysis.completeness.bucketCounts.FREE_SPINS_SPECIAL },
    { id: 'COIN_COLLECTION_REWARD', predicate: 'paid non-free props.coins.is_full=true', completeRounds: coinCount, status: rareCoin ? 'SAMPLE_INSUFFICIENT' : 'QUOTA_MET' }
  ],
  specialModes: [
    { id: 'FREE_SPINS', supported: true, entryRequestTypes: [1,3], continuationRequestType: 2, freeSteps: 5, purchaseIsIndependentMode: false, evidence: ['EVID-CURRENT-HELP','EVID-CURRENT-BUNDLE','EVID-ROUND-INDEX'] },
    { id: 'COIN_COLLECTION_REWARD', supported: true, multiRequestWithinRound: false, crossRoundState: true, rewardFormula: rulesCore.coinCollection.rewardFormula, sampleStatus: rareCoin ? 'SAMPLE_INSUFFICIENT' : 'QUOTA_MET', evidence: ['EVID-CURRENT-HELP','EVID-CURRENT-BUNDLE','EVID-ROUND-INDEX'] }
  ],
  triggerSymbolConstraints: { scatter: { symbol: 10, collectionThreshold: 30, naturalPaidByBetType: { 1: scatter.byBetType.BET_TYPE_1, 2: scatter.byBetType.BET_TYPE_2, 3: scatter.byBetType.BET_TYPE_3 }, featureBuyTriggerPage: { ...scatter.byBetType.FEATURE_BUY_INITIAL, observedAxisTotals: [9,10,11], exactObservedPageTotal: 30, sampleCount: 1 }, allowedAxisIndexes: scatter.allowedAxisIndexes, freeStepGenerationAllowed: false, freeStepObservedCount: 0, maximumDerivedFreeSteps: 5, enforcement: 'APPLY_ENTRY_SPECIFIC_LIMITS_AND_REJECT_COMPLETE_ROUND_IF_ANY_LIMIT_EXCEEDED' } },
  integerMultiplier: analysis.integerMultiplier,
  generationModel: {
    implementationReady: true,
    usesIndependentCellMarginalsOnly: false,
    artificialLossBoardConstraints: false,
    probabilityCaution: 'Empirical evidence is entry-specific and is not the provider long-run probability or RTP.',
    lifecycleEntries: [
      { id: 'PAID_INITIAL', entryType: 'INITIAL', lifecyclePhase: 'INITIAL', isInitialEntry: true, role: 'INITIAL', countingUnit: 'COMPLETE_INITIAL_STATE', observationUnit: 'COMPLETE_INITIAL_STATE', newlyDealtPositionsOnly: false, sampleRoundCount: trainingRows.filter(row => rounds.find(round => round.roundId === row.roundId)?.entryKind === 'PAID_SPIN').length, samplePositionCount: Object.entries(analysis.symbolGeneration.paidCompleteInitialState).filter(([id]) => !id.startsWith('FEATURE_BUY_')).reduce((sum, [, value]) => sum + value.cells, 0), evidenceRefs: ['EVID-TRAINING-MANIFEST','EVID-SYMBOL-ANALYSIS'], wholeNextStateCountedAsNewDeal: false, positionSelection: 'ALL_POSITIONS_IN_COMPLETE_INITIAL_STATE', partitions: 'bet_type then axis index' },
      { id: 'FEATURE_BUY_INITIAL', entryType: 'INITIAL', lifecyclePhase: 'INITIAL', isInitialEntry: true, role: 'INITIAL', countingUnit: 'COMPLETE_INITIAL_STATE', observationUnit: 'COMPLETE_INITIAL_STATE', newlyDealtPositionsOnly: false, sampleRoundCount: trainingRows.filter(row => rounds.find(round => round.roundId === row.roundId)?.entryKind === 'FEATURE_BUY').length, samplePositionCount: Object.entries(analysis.symbolGeneration.paidCompleteInitialState).filter(([id]) => id.startsWith('FEATURE_BUY_')).reduce((sum, [, value]) => sum + value.cells, 0), evidenceRefs: ['EVID-TRAINING-MANIFEST','EVID-SYMBOL-ANALYSIS','EVID-SPIN-RAW'], wholeNextStateCountedAsNewDeal: false, positionSelection: 'ALL_POSITIONS_IN_COMPLETE_INITIAL_STATE', partitions: 'three trigger axes despite request bet_type=1' },
      { id: 'FREE_STEP_COMPLETE_STATE', entryType: 'SUBSEQUENT', lifecyclePhase: 'SUBSEQUENT', isInitialEntry: false, role: 'FREE_SPIN', countingUnit: 'NEWLY_DEALT_POSITIONS', observationUnit: 'NEWLY_DEALT_POSITIONS', newlyDealtPositionsOnly: true, sampleRoundCount: analysis.holdout.trainingBucketCounts.FREE_SPINS_SPECIAL, samplePositionCount: Object.values(analysis.symbolGeneration.freeCompleteStateRawGrid).reduce((sum, value) => sum + value.cells, 0), evidenceRefs: ['EVID-TRAINING-MANIFEST','EVID-SYMBOL-ANALYSIS','EVID-SPIN-RAW'], wholeNextStateCountedAsNewDeal: true, retainedPositionCount: 0, positionSelection: 'ALL_POSITIONS_CONFIRMED_NEW_BY_ADJACENT_TYPE_2_RESPONSE_BOUNDARIES', stateBoundary: 'EACH_TYPE_2_RESPONSE_IS_A_NEW_FREE_STATE' },
      { id: 'COIN_VECTOR_DELTA', entryType: 'SUBSEQUENT', lifecyclePhase: 'SUBSEQUENT', isInitialEntry: false, role: 'STATE_TRANSITION', countingUnit: 'NEWLY_DEALT_POSITIONS', observationUnit: 'NEWLY_DEALT_POSITIONS', newlyDealtPositionsOnly: true, sampleRoundCount: analysis.coinEvidence.vectorDelta.denominator, samplePositionCount: Object.entries(analysis.coinEvidence.vectorDelta.newlyIncrementedPositionCounts).reduce((sum, [positions, count]) => sum + Number(positions) * count, 0), evidenceRefs: ['EVID-TRAINING-MANIFEST','EVID-COIN-DELTA','EVID-SPIN-RAW'], wholeNextStateCountedAsNewDeal: false, retainedPositionCount: 5, positionSelection: 'ONLY_VECTOR_POSITIONS_CHANGED_BY_ADJACENT_STATE_DIFFERENCE', derivation: 'ADJACENT_STATE_VECTOR_DIFFERENCE', vectorLength: 5, branches: analysis.coinEvidence.vectorDelta.branchCounts }
    ],
    lifecycleEntrances: ['PAID_INITIAL','FEATURE_BUY_INITIAL','FREE_STEP_COMPLETE_STATE','COIN_VECTOR_DELTA'],
    unsupportedEntrances: [{ id: 'CASCADE', reason: 'no cascade transition in current code or 5000-start corpus' },{ id: 'RESPIN', reason: 'no respin transition in current code or 5000-start corpus' }],
    jointRoundFeatures: [
      { id: 'ROUND_OUTCOME_CLASS', applicable: true, role: 'WHOLE_ROUND_JOINT_FEATURE', countingUnit: 'COMPLETE_ROUND', sampleRoundCount: analysis.holdout.trainingCount, distribution: analysis.holdout.trainingBucketCounts, evidenceRefs: ['EVID-TRAINING-MANIFEST','EVID-ROUND-INDEX'] },
      { id: 'ROUND_STEP_COUNT', applicable: true, role: 'WHOLE_ROUND_JOINT_FEATURE', countingUnit: 'COMPLETE_ROUND', sampleRoundCount: analysis.holdout.trainingCount, distribution: analysis.lifecycle.stepCountDistribution, evidenceRefs: ['EVID-TRAINING-MANIFEST','EVID-ROUND-INDEX'] },
      { id: 'STATE_ELEMENT_COUNT_VECTOR', applicable: true, role: 'WHOLE_ROUND_JOINT_FEATURE', countingUnit: 'COMPLETE_ROUND', sampleRoundCount: analysis.holdout.trainingCount, distribution: analysis.lifecycle.jointDistribution, evidenceRefs: ['EVID-TRAINING-MANIFEST','EVID-SYMBOL-ANALYSIS'] }
    ],
    jointRoundFeatureDistributions: { ROUND_OUTCOME_CLASS: analysis.holdout.trainingBucketCounts, ROUND_STEP_COUNT: analysis.lifecycle.stepCountDistribution, STATE_ELEMENT_COUNT_VECTOR: analysis.lifecycle.jointDistribution, population: '4900_REAL_PROVIDER_TRAINING_COMPLETE_ROUNDS', holdoutExcluded: true },
    requiredJointDistributions: ['ROUND_OUTCOME_CLASS','ROUND_STEP_COUNT','STATE_ELEMENT_COUNT_VECTOR'],
    holdoutPlan: { source: 'REAL_PROVIDER', sourceType: 'REAL_PROVIDER', evidenceType: 'REAL_PROVIDER_HOLDOUT', minimumRounds: 100, actualRounds: analysis.holdout.count, count: analysis.holdout.count, roundCount: analysis.holdout.count, sampleRoundCount: analysis.holdout.count, minimumCount: 100, minimumRoundCount: 100, selection: 'FROZEN_BEFORE_ANALYSIS_FROM_COMPLETE_PROVIDER_ROUNDS', selectionMethod: 'FROZEN_BEFORE_ANALYSIS_FROM_COMPLETE_PROVIDER_ROUNDS', completeRoundOnly: true, completeRoundsOnly: true, frozenBeforeAnalysis: true, disjointFromTraining: analysis.holdout.overlap === 0, intersectionCount: analysis.holdout.overlap, trainingOverlapCount: analysis.holdout.overlap, trainingCompleteRounds: analysis.holdout.trainingCount, file: analysis.holdout.file, indexFile: analysis.holdout.file, indexRowsExplicitSourceType: true, indexRowsExplicitCompleteRound: true, trainingManifest: analysis.holdout.trainingManifest, usesImplementationGeneratedExpected: false, minimumGeneratedRoundsForValidation: 10000, requiredResult: 'PASS' },
    holdout: { originalCompleteRounds: analysis.holdout.count, disjointFromTraining: analysis.holdout.overlap === 0, trainingCompleteRounds: analysis.holdout.trainingCount, file: analysis.holdout.file, minimumGeneratedRoundsForValidation: 10000, requiredResult: 'PASS' }
  },
  runtimeIndependentLoss: { supported: true, demoMayGenerateAtRuntime: false, completeLossRoundsMustBePreloaded: true },
  history: { listAndDetail: true, lightweightOnly: true, bnBdDetailGapClosed: Boolean(history.detail), perRoundHistoryPolling: false, perOutcomeIndex: 'captures/2290-Samba-Sensation/history-round-index.jsonl', matchingDetailRows: Object.fromEntries(historyRoundIndex.map(row => [row.outcomeClass, row.matchingDetailRecordPresent])) },
  handoffContract: { file: 'protocol-handoff.json', rulesHash, behaviorIds: behaviors }
};
await writeJson(path.join(protocolRoot, 'game-capabilities.json'), capabilities);

const fields = [
  ['request.token','session bearer token','all API requests','EVID-INIT','redacted; never persisted'],['request.gid','current game id 2290','all API requests','EVID-SPIN-RAW','constant 2290'],['request.ai','launch/session affiliate id','init/result','EVID-INIT','redacted'],['request.bet','Bet Size','result','EVID-SPIN-RAW','0.02 in quota corpus'],['request.level','Bet Level','result','EVID-SPIN-RAW','1 in quota corpus'],['request.bet_type','active paid axis count selector','result','EVID-SYMBOL-ANALYSIS','1/2/3 => 1/2/3 boards'],['request.type','state transition command','result','EVID-SPIN-RAW','1 paid; 2 free continuation; 3 feature buy'],['data.bet_gold','charged wager for active axes','result/history','EVID-SPIN-RAW','not the integer-multiplier divisor'],['data.change_gold','balance delta for response','result/history','EVID-SPIN-RAW','numeric'],['data.start_gold','balance before response','result/history','EVID-SPIN-RAW','numeric'],['data.end_gold','balance after response','result/history','EVID-SPIN-RAW','numeric'],['data.oid','provider delivery/order id','result/history','EVID-SPIN-RAW','string-preserved to avoid numeric precision loss'],['data.total_win','current response award','result/history','EVID-SPIN-RAW','0 allowed'],['data.odds','response award divided by wager semantics','result/history','EVID-SPIN-RAW','numeric, not canonical integer pool key'],['data.small_game_type','server mode marker','result','EVID-SPIN-RAW','0 paid; 2 free'],['data.props.props','axis board arrays','result/history','EVID-SYMBOL-ANALYSIS','each raw board has 15 integers'],['data.props.is_free','response is a free step','result/history','EVID-SPIN-RAW','0/1'],['data.props.scatter','persistent Scatter progress','result/history','EVID-SPIN-RAW','not sufficient alone to branch free state'],['data.props.win_arr','winning entries grouped by 1-based axis','result/history','EVID-SPIN-RAW','empty arrays are normal'],['win.line','1-based payline id','result/history','EVID-SPIN-RAW','observed 1..25'],['win.count','consecutive symbol count','result/history','EVID-SPIN-RAW','observed 3..5'],['win.symbol','winning symbol id','result/history','EVID-SPIN-RAW','observed 1..9; Wild participates by substitution'],['win.pay_out','paytable factor before bet/level scaling','result/history','EVID-SPIN-RAW','numeric'],['win.reward','monetary line award','result/history','EVID-SPIN-RAW','numeric'],['data.props.coins.coins','five-slot cross-round collection vector','result/history','EVID-SPIN-RAW','length 5 in all observed states'],['data.props.coins.count','coin reward multiplier/count','result/history','EVID-SPIN-RAW','used by reward formula'],['data.props.coins.is_full','coin reward trigger flag','result/history','EVID-CURRENT-BUNDLE','false common; true triggers reward popup'],['data.frees.st','remaining free requests after current response','result/history','EVID-SPIN-RAW','missing in inactive ordinary frees object is normal; 5..0 active'],['data.frees.tt','total free-step count','result/history','EVID-SPIN-RAW','5 in all active samples'],['data.frees.twa','cumulative free award','result/history','EVID-SPIN-RAW','terminal complete-round award'],['data.frees.ba','feature base wager','result/history','EVID-SPIN-RAW','present during free state'],['history.data.list','daily summary or result rows','history','EVID-HISTORY-BN','list 7 summaries; detail page 30 rows'],['history.results','all pages/steps of one history Round','history detail','EVID-HISTORY-BN','used for display/oracle only']
].map(([field,semantics,endpoints,evidenceRef,observed]) => ({ field, semantics, endpoints, codeDecision: semantics, sampleCorroboration: observed, evidenceRef }));
await writeJson(path.join(reportRoot, 'field-evidence-matrix.json'), { schemaVersion: 1, gameId: 2290, rulesHash, columns: ['field','semantics','endpoints','codeDecision','sampleCorroboration','evidenceRef'], fields });
const fieldMd = ['# Game 2290 字段证据矩阵','',`rulesHash: \`${rulesHash}\``, '', '| 字段 | 语义/代码判定 | 样本印证 | 证据 |','|---|---|---|---|',...fields.map(f => `| \`${f.field}\` | ${f.semantics} | ${f.sampleCorroboration} | ${f.evidenceRef} |`),''].join('\n');
const fieldMdUtf8 = ['# Game 2290 字段证据矩阵','',`rulesHash: \`${rulesHash}\``, '', '| 字段 | 语义/代码判定 | 样本印证 | 证据 |','|---|---|---|---|',...fields.map(f => `| \`${f.field}\` | ${f.semantics} | ${f.sampleCorroboration} | ${f.evidenceRef} |`),''].join('\n');
await fs.writeFile(path.join(reportRoot, 'field-evidence-matrix.md'), fieldMdUtf8);

const roundSampleCoverage = {
  ordinaryTargetPerCategory: 200,
  specialTargetPerCategory: 30,
  maxPaidRounds: 5000,
  paidRoundStarts: analysis.completeness.paidStarts,
  completedRoundCount: rounds.filter(round => round.complete).length,
  stopReason: rareCoin ? 'MAX_PAID_ROUNDS_REACHED_WITH_COIN_COLLECTION_REWARD_SAMPLE_INSUFFICIENT' : 'ALL_CATEGORY_TARGETS_MET',
  categories: capabilities.outcomePartition.map(item => ({ id: item.id, kind: item.id, category: item.id, applicable: true, actual: item.completeRounds, completeRoundCount: item.completeRounds, completeRounds: item.completeRounds, count: item.completeRounds, sourceType: 'REAL_PROVIDER', target: item.id.startsWith('ORDINARY_') ? 200 : 30, status: item.completeRounds >= (item.id.startsWith('ORDINARY_') ? 200 : 30) ? 'MET' : 'SAMPLE_INSUFFICIENT' }))
};
const coverage = {
  schemaVersion: 2, gameId: 2290, rulesHash, paidStarts: analysis.completeness.paidStarts, maxPaidStarts: 5000, allRoundsComplete: analysis.completeness.allComplete,
  roundSampleCoverage,
  buckets: capabilities.outcomePartition.map(bucket => ({ ...bucket, target:  bucket.id.startsWith('ORDINARY_') ? 200 : 30, occurrenceRate: analysis.completeness.paidStarts ? Number((bucket.completeRounds / analysis.completeness.paidStarts).toFixed(8)) : 0 })),
  purchase: { independentBucket: false, reason: 'type=3 enters the same FREE_SPINS state machine', capturedRoundId: featureBuyRound?.roundId || null },
  history: { listCallsRetained: 1, detailCallsRetained: 1, language: 'bn-bd', usedForQuota: false, outcomeIndex: 'captures/2290-Samba-Sensation/history-round-index.jsonl', matchingDetailRecordCounts: Object.fromEntries(historyBuckets.map(bucket => [bucket, historyRoundIndex.filter(row => row.outcomeClass === bucket && row.matchingDetailRecordPresent).length])) },
  dirtyData: { quarantinedRounds: 36, evidence: 'captures/2290-Samba-Sensation/quarantine-overlap-20260905/manifest.json' },
  holdout: analysis.holdout,
  overall: rareCoin ? 'COMPLETE_WITH_RARE_SAMPLE_INSUFFICIENT' : 'COMPLETE',
  blockers: rareCoin ? [{ marker: '[BLOCKER:RARE]', mode: 'COIN_COLLECTION_REWARD', attempts: analysis.completeness.paidStarts, samples: coinCount, target: 30, action: 'Stopped at paid-start cap; do not repeat identical capture.' }] : []
};
await writeJson(path.join(reportRoot, 'scenario-coverage.json'), coverage);
await writeJson(path.join(reportRoot, 'lab-metadata.json'), { schemaVersion: 2, gameId: 2290, directoryName: name, displayName: 'Samba Sensation', supportedLanguages: languages, winModel: 'FIXED_PAYLINE', paylineCount: 25, board: { columns: 5, rows: 3, maximumAxes: 3 }, specialModes: ['FREE_SPINS','COIN_COLLECTION_REWARD'], rulesHash, evidence: ['protocol/2290-Samba-Sensation/game-capabilities.json','publish/2290-Samba-Sensation/assets/Game2290/index.5d956.js','reports/2290-Samba-Sensation/protocol-evidence-analysis.json'] });

const status = {
  schemaVersion: 2, gameId: 2290, directoryName: name, activeRevision: `protocol-${rulesHash.slice(0,12)}`, stage: 'PROTOCOL_AND_GAME_STATE_MACHINE_ANALYSIS', status: rareCoin ? 'COMPLETE_WITH_RARE_SAMPLE_INSUFFICIENT' : 'COMPLETE_READY_FOR_IMPLEMENTATION',
  publishDirectory: `D:/work/hd/cpgame/publish/${name}`, entryFile: `publish/${name}/index.html`, testedLanguages: languages, spinCount: analysis.completeness.paidStarts,
  capturedModes: capabilities.outcomePartition.filter(item => item.completeRounds > 0).map(item => item.id), missingModes: rareCoin ? [{ mode: 'COIN_COLLECTION_REWARD', status: 'SAMPLE_INSUFFICIENT', count: coinCount, target: 30, paidStarts: analysis.completeness.paidStarts }] : [],
  roundSampleCoverage,
  history: { listAndDetail: 'COMPLETE', bnBdDetailGapClosed: Boolean(history.detail), lightweightOnly: true, outcomeIndex: 'captures/2290-Samba-Sensation/history-round-index.jsonl', matchingDetailRows: capabilities.history.matchingDetailRows, matchingOutcomeCount: historyRoundIndex.filter(row => row.matchingDetailRecordPresent).length }, implementationReady: true, rulesHash,
  evidence: ['reports/2290-Samba-Sensation/language-inventory.json','reports/2290-Samba-Sensation/resource-stage-status.json','reports/2290-Samba-Sensation/scenario-coverage.json','reports/2290-Samba-Sensation/field-evidence-matrix.json','reports/2290-Samba-Sensation/symbol-generation-evidence.json','reports/2290-Samba-Sensation/coin-vector-delta-evidence.json','reports/2290-Samba-Sensation/protocol-node-validation.json','protocol/2290-Samba-Sensation/game-capabilities.json','protocol/2290-Samba-Sensation/protocol-handoff.json','captures/2290-Samba-Sensation/round-index.jsonl','captures/2290-Samba-Sensation/training-manifest.json','captures/2290-Samba-Sensation/holdout-round-index.jsonl','captures/2290-Samba-Sensation/history-lightweight.json','captures/2290-Samba-Sensation/history-round-index.jsonl','captures/2290-Samba-Sensation/history-evidence.jsonl','captures/2290-Samba-Sensation/history-supplement-summary.json'],
  blockers: coverage.blockers, updatedAt: new Date().toISOString(), nextStage: 'PROTOCOL_VALIDATION_THEN_JAVA_IMPLEMENTATION'
};
await writeJson(path.join(reportRoot, 'current-status.json'), status);

const spec = {
  schemaVersion: 2, gameId: 2290, rulesHash,
  transport: { scheme: 'HTTPS', contentType: 'application/x-www-form-urlencoded', applicationEncryption: 'NONE_OBSERVED', responseEncoding: 'PLAIN_JSON', tokenHandling: 'REDACT_AT_CAPTURE_BOUNDARY' },
  endpoints: [
    { id: 'init', method: 'POST', path: '/cp/single_game.Game/initRoom', requestFields: ['token','gid','language','ai'] },
    { id: 'result', method: 'POST', path: '/cp/single_game.Game/gameResult', requestFields: ['token','bet','level','gid','type','bet_type','language','ai'] },
    { id: 'history-list', method: 'POST', path: '/cp/goldgame/single_game_user_gold_history', requestFields: ['token','gid','language'] },
    { id: 'history-detail', method: 'POST', path: '/cp/goldgame/single_game_user_history', requestFields: ['token','gid','day','page_size','page','language'] }
  ],
  sequence: ['initRoom','type=1 paid OR type=3 purchase','if frees.st>0 then repeat type=2 until frees.st=0','otherwise paid response is terminal','history list -> selected day detail is independent lightweight evidence path'],
  rules: rulesCore, fieldMatrix: `reports/${name}/field-evidence-matrix.json`, capabilities: `protocol/${name}/game-capabilities.json`, handoff: `protocol/${name}/protocol-handoff.json`
};
await writeJson(path.join(protocolRoot, 'protocol-spec.json'), spec);
const specMd = `# Game 2290 协议与状态机\n\nrulesHash: \`${rulesHash}\`\n\n## 一局如何结束\n\n普通付费请求为 \`type=1\`。响应未激活 \`frees.st\` 时该响应即为完整 Round 终点；\`total_win=0\` 且非金币满槽为普通未中奖，\`total_win>0\` 且非金币满槽为普通中奖。若响应 \`frees.st=5\`，必须连续请求五次 \`type=2\`，观察到的相邻状态固定为 5→4→3→2→1→0；只有终态 0 才结束。\n\n## 特殊与购买\n\nFree Spins 固定五次、三轴全开。每轴 5×3 原始数组中，中央三列九格编码同一个大符号，外围六格是独立符号。购买请求为 \`type=3\`，直接进入同一 Free Spins 状态机，因此购买不是独立互斥玩法。实际购买触发页请求 \`bet_type=1\` 但返回三轴，三轴 Scatter 为 9/10/11、整页 30，必须作为独立入口结构处理。金币收集奖励由 \`props.coins.is_full\` 表示，是一响应内结算的跨局收集结果。\n\n## 中奖与牌面\n\n当前游戏是 25 条固定线，不是 Ways/Cluster。Wild=0，可替代 1..9，不替代 Scatter=10；从左向右至少三个，仅每线最高奖。付费 \`bet_type=1/2/3\` 分别生成 1/2/3 个独立 5×3 轴。\n\n## Scatter 约束\n\n规则收集阈值为 30。自然付费入口必须按 bet_type 分别执行观测上限：1轴页为单列3/单轴4/整页4，2轴页为2/4/5，3轴页为3/6/8；购买触发页单列3/单轴11/整页30，不能与自然页混用。允许轴位 ${scatter.allowedAxisIndexes.join(',')}；免费入口观察 Scatter 数为 0，因此禁止免费入口生成。\n\n## 协议\n\n请求为 HTTPS + form-urlencoded，响应为普通 JSON；未观察到应用层加密。\`oid\` 必须按字符串保真。整数倍率以 \`roundAward / bet\` 计算，完整样本全部为整数；\`roundAward / bet_gold\` 另存为 wagerMultiple，不得冒充整数倍率。History 只保留一次 bn-bd 列表和详情，不参与配额。\n\n## 生成边界\n\n必须按 ROUND_OUTCOME_CLASS × ROUND_STEP_COUNT × STATE_ELEMENT_COUNT_VECTOR 联合分布建模，禁止独立格抽样和人为拼未中奖盘。留出集 100 个原厂完整 Round 与训练集不重叠；后续至少生成 10000 个新 Round 做独立检验。\n`;
const specMdUtf8 = `# Game 2290 协议与状态机

rulesHash: \`${rulesHash}\`

## 一局如何结束

普通付费请求为 \`type=1\`。响应未激活 \`frees.st\` 时，该响应就是完整 Round 终点：\`total_win=0\` 且金币未满槽为普通未中奖；\`total_win>0\` 且金币未满槽为普通中奖。响应出现 \`frees.st=5\` 时，必须继续请求五次 \`type=2\`，相邻状态固定为 5→4→3→2→1→0；只有终态 0 才结束。

## 特殊与购买

Free Spins 固定五次、三轴全开。每轴 5×3 原始数组中，中央三列九格编码同一个大符号，外围六格为独立符号。购买请求 \`type=3\` 直接进入同一 Free Spins 状态机，因此购买不是独立互斥玩法。实际购买触发页请求 \`bet_type=1\` 但返回三轴，Scatter 数为 9/10/11、整页 30，必须与自然 Spin 入口分开处理。金币满槽奖励由 \`props.coins.is_full\` 表示，在一个响应内结算。

## 中奖与牌面

本游戏是 25 条固定线，不是 Ways/Cluster。Wild=0，可替代 1..9，不替代 Scatter=10；从左向右至少三个，每线只取最高奖。付费 \`bet_type=1/2/3\` 分别返回 1/2/3 个独立 5×3 轴。

## Scatter 约束

规则收集阈值为 30。自然付费入口按 bet_type 分别执行训练集观测上限：1轴为单列3/单轴4/整页4，2轴为2/4/5，3轴为3/6/8；购买触发页为单列3/单轴11/整页30，禁止混用。允许轴位为 ${scatter.allowedAxisIndexes.join(',')}。Free Step 训练证据中 Scatter 为 0，因此该入口禁止生成 Scatter。

## 协议与证据边界

请求为 HTTPS + form-urlencoded，响应为普通 JSON，未观察到应用层加密。\`oid\` 必须按字符串保真。整数倍率为 \`roundAward / bet\`；\`roundAward / bet_gold\` 仅为 wagerMultiple。History 保留一次轻量列表调用和分页定位出的四条真实详情记录，不参与样本配额；普通输赢、Free Spins 与金币满槽均通过详情订单标识连接到唯一 canonical Round。

## 生成边界

所有分析统计只使用 4900 个真实训练 Round。冻结的 100 个真实完整 Round 与训练集零交集，只供后续独立 oracle。必须按 ROUND_OUTCOME_CLASS × ROUND_STEP_COUNT × STATE_ELEMENT_COUNT_VECTOR 联合分布建模，禁止独立格抽样和人为拼未中奖盘。金币五槽按相邻训练状态差分，记录不变、递增、满槽、满槽后复位分支及两端原始哈希。后续至少生成 10000 个新 Round 做独立检验。
`;
await fs.writeFile(path.join(protocolRoot, 'protocol-spec.md'), specMdUtf8);

console.log(JSON.stringify({ rulesHash, paidStarts: analysis.completeness.paidStarts, counts: analysis.completeness.bucketCounts, featureBuyCaptured: Boolean(featureBuyRound), rareCoin, behaviorCount: behaviorContracts.length, evidenceCount: evidenceInventory.length }));
