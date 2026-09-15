#!/usr/bin/env node

import fs from 'node:fs/promises';
import path from 'node:path';

const ROOT = path.resolve(process.cwd());
const TARGETS = [
  { gameId: 42, directoryName: '42-Lucky-Dragon' },
  { gameId: 43, directoryName: '43-Lucky-Wheel' },
  { gameId: 45, directoryName: '45-Rio-Carnival' },
  { gameId: 50, directoryName: '50-Lucky-Cat-II' },
];
const CATEGORIES = [
  'resources', 'publish', 'server-api', 'generator', 'screenshots',
  'captures', 'fixtures', 'protocol', 'redis-pack', 'reports',
];

async function readJson(relativePath) {
  return JSON.parse(await fs.readFile(path.join(ROOT, relativePath), 'utf8'));
}

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function assertRawIdentity(value, target, label) {
  assert(value.gameId === target.gameId, `${label}.gameId 必须是 raw gid ${target.gameId}`);
  if ('requestedOriginalGid' in value) {
    assert(value.requestedOriginalGid === target.gameId,
      `${label}.requestedOriginalGid 必须是 raw gid ${target.gameId}`);
  }
  if ('directoryName' in value) {
    assert(value.directoryName === target.directoryName, `${label}.directoryName 不匹配`);
  }
  const serialized = JSON.stringify(value);
  assert(!serialized.includes('integrationGameId'), `${label} 禁止 integrationGameId`);
  assert(!serialized.includes('8000000+'), `${label} 禁止派生 game id 表达式`);
}

function assertFormalZero(status, ledger, target) {
  const coverage = status.roundSampleCoverage;
  assert(status.spinCount === 0, `${target.directoryName} spinCount 必须保持0`);
  assert(coverage?.paidRoundStarts === 0, `${target.directoryName} formal paidRoundStarts 必须保持0`);
  assert(coverage?.completedRoundCount === 0,
    `${target.directoryName} formal completedRoundCount 必须保持0`);
  assert(ledger.paidRoundStarts === 0, `${target.directoryName} ledger paidRoundStarts 必须保持0`);
  assert(ledger.completedRoundCount === 0,
    `${target.directoryName} ledger completedRoundCount 必须保持0`);
  assert(ledger.networkExchangeCount === 0,
    `${target.directoryName} networkExchangeCount 必须保持0`);
  assert(ledger.sourceTypeRequired === 'ORIGINAL_AUTHORIZED_HTTP',
    `${target.directoryName} formal sourceType 必须是 ORIGINAL_AUTHORIZED_HTTP`);
}

const audit = await readJson('reports/_workflow/batch-b-readiness-audit.json');
const results = [];
for (const target of TARGETS) {
  const base = target.directoryName;
  const source = await readJson(`resources/${base}/source-catalog-evidence.json`);
  const profile = await readJson(`captures/${base}/capture-profile.json`);
  const ledger = await readJson(`captures/${base}/capture-ledger.json`);
  const capabilities = await readJson(`protocol/${base}/game-capabilities.draft.json`);
  const handoff = await readJson(`protocol/${base}/protocol-handoff.draft.json`);
  const status = await readJson(`reports/${base}/current-status.json`);
  const scenarios = await readJson(`reports/${base}/scenario-coverage.json`);
  for (const [label, value] of Object.entries({ source, profile, ledger, capabilities, handoff, status, scenarios })) {
    assertRawIdentity(value, target, `${base}.${label}`);
  }
  assert(capabilities.draft === true, `${base} capabilities 必须保持draft`);
  assert(handoff.draft === true, `${base} handoff 必须保持draft`);
  assert(String(capabilities.rulesVersion).startsWith('UNKNOWN_'),
    `${base} 无原始规则证据时 rulesVersion 必须保持UNKNOWN`);
  assertFormalZero(status, ledger, target);
  const categoryAudit = audit.boundedPerGameTenCategoryAudit?.[base];
  assert(categoryAudit && Object.keys(categoryAudit).length === CATEGORIES.length,
    `${base} 必须有十类bounded审计`);
  assert(CATEGORIES.every(category => Object.hasOwn(categoryAudit, category)),
    `${base} bounded审计类别不完整`);
  if (target.gameId === 42) {
    const ui = await readJson('protocol/42-Lucky-Dragon/ui-history-evidence-contract.json');
    assertRawIdentity(ui, target, `${base}.uiHistoryContract`);
    assert(ui.formalOriginalHttpEligible === false, 'gid42 UI证据不得计入formal HTTP');
    assert(ui.sessionAggregate?.paidRoundStarts === 10, 'gid42 UI paid rounds 必须为10');
    assert(ui.sessionAggregate?.ordinaryLossRounds === 9, 'gid42 UI LOSS 必须为9');
    assert(ui.sessionAggregate?.ordinaryWinRounds === 1, 'gid42 UI WIN 必须为1');
    assert(ui.confirmedWinningDetail?.transactionId === '2093422299384020992',
      'gid42 第10局交易号不匹配');
    assert(ui.confirmedWinningDetail?.candidateOnly === true,
      'gid42 WILD_MULTIPLIER_X3 必须保持候选状态');
  }
  if (target.gameId === 43) {
    const readiness = await readJson('protocol/43-Lucky-Wheel/browser-readiness-evidence.json');
    assertRawIdentity(readiness, target, `${base}.browserReadiness`);
    assert(readiness.extensionInstanceId === '35d86d21-ac60-4133-9019-8c4645472100',
      'gid43 浏览器准备必须绑定指定extensionInstanceId');
    assert(readiness.ownTab?.url === 'https://hms-paddle.com/#/game3',
      'gid43 仅允许记录已发现的自有HMS标签');
    assert(readiness.accountVerified === false, 'gid43 控制桥失败后不得声称账号已核验');
    assert(readiness.realSpinAttempted === false, 'gid43 浏览器准备阶段禁止Spin');
    assert(readiness.formalOriginalHttpEligible === false,
      'gid43 标签元数据不得计入formal HTTP');
  }
  results.push({
    gameId: target.gameId,
    directoryName: base,
    rawIdentity: 'PASS',
    tenCategoryAudit: 'PASS',
    draftRuleBoundary: 'PASS',
    formalOriginalHttpPaidRoundStarts: 0,
    formalCompletedRounds: 0,
  });
}

const report = {
  schemaVersion: 1,
  batchId: 'B',
  result: 'PASS_OFFLINE_PREPARATION_ONLY',
  browserUsed: false,
  completionClaim: false,
  results,
  uiHistoryEvidence42: {
    paidRoundStarts: 10,
    ordinaryLossRounds: 9,
    ordinaryWinRounds: 1,
    countedAsFormalOriginalHttp: false,
  },
  browserReadiness43: {
    exactExtensionInstanceConnected: true,
    ownHmsTabFound: true,
    accountVerified: false,
    minimumBetObserved: false,
    realSpinAttempted: false,
    countedAsFormalOriginalHttp: false,
  },
  blocker: 'WAITING_EXPLICIT_RELEASE_BROWSER_TO_B_FOR_ORIGINAL_AUTHORIZED_HTTP_AND_RULE_EVIDENCE',
};
const output = path.join(ROOT, 'reports/_workflow/batch-b-offline-gate-report.json');
await fs.writeFile(output, `${JSON.stringify(report, null, 2)}\n`);
console.log(JSON.stringify(report, null, 2));
