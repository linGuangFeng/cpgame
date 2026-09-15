#!/usr/bin/env node

import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';

const SECRET_KEY = /(?:password|passwd|token|authorization|cookie|session|web-token|^t$)/i;
const ALLOWED_SOURCE = 'ORIGINAL_AUTHORIZED_HTTP';
const WAITING_STATE = 'WAITING_CHROME_RELEASE';
const PAUSED_STATES = new Set([
  WAITING_STATE,
  'WAITING_BROWSER_POLICY_RECOVERY',
  'WAITING_CORRECT_ACCOUNT_SESSION',
  'WAITING_RELEASE_TO_B',
  'BROWSER_RELEASED_TO_C_WAITING_EXPLICIT_RELEASE_BROWSER_TO_B',
  'ABC222_EXACT_INSTANCE_CONNECTED_PAGE_CONTROL_BLOCKED',
]);
const REQUIRED_RESUME_SIGNAL = 'RELEASE_BROWSER_TO_B';

function fail(message) {
  throw new Error(message);
}

function parseArgs(argv) {
  const result = { command: argv[2] || 'validate-profile' };
  for (let index = 3; index < argv.length; index += 2) {
    const key = argv[index];
    const value = argv[index + 1];
    if (!key?.startsWith('--') || value == null) fail(`参数格式错误：${key || '<empty>'}`);
    result[key.slice(2)] = value;
  }
  return result;
}

async function readJson(file) {
  const value = JSON.parse(await fs.readFile(file, 'utf8'));
  if (!value || Array.isArray(value) || typeof value !== 'object') fail(`${file} 必须是JSON对象`);
  return value;
}

async function readJsonl(file) {
  const text = await fs.readFile(file, 'utf8');
  return text.split(/\r?\n/).filter(Boolean).map((line, index) => {
    try { return JSON.parse(line); }
    catch (error) { fail(`${file}:${index + 1} 不是有效JSON：${error.message}`); }
  });
}

function redact(value, key = '') {
  if (SECRET_KEY.test(key)) return '[REDACTED]';
  if (Array.isArray(value)) return value.map(item => redact(item));
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([childKey, childValue]) =>
      [childKey, redact(childValue, childKey)]));
  }
  return value;
}

function containsForbiddenGameIdKey(value) {
  if (Array.isArray(value)) return value.some(containsForbiddenGameIdKey);
  if (!value || typeof value !== 'object') return false;
  return Object.entries(value).some(([key, child]) =>
    key === 'integrationGameId' || containsForbiddenGameIdKey(child));
}

function pointer(root, jsonPointer) {
  if (typeof jsonPointer !== 'string' || !jsonPointer.startsWith('/')) return undefined;
  return jsonPointer.slice(1).split('/').reduce((value, rawPart) => {
    const part = rawPart.replaceAll('~1', '/').replaceAll('~0', '~');
    return value == null ? undefined : value[part];
  }, root);
}

function compare(actual, operator, expected) {
  switch (operator) {
    case 'eq': return actual === expected;
    case 'ne': return actual !== expected;
    case 'gt': return Number(actual) > Number(expected);
    case 'gte': return Number(actual) >= Number(expected);
    case 'lt': return Number(actual) < Number(expected);
    case 'lte': return Number(actual) <= Number(expected);
    case 'in': return Array.isArray(expected) && expected.includes(actual);
    case 'present': return actual !== undefined && actual !== null;
    default: fail(`未知比较操作符：${operator}`);
  }
}

function matchesRule(round, rule) {
  const conditions = Array.isArray(rule.conditions) ? rule.conditions : [];
  if (!conditions.length) return false;
  return conditions.every(condition =>
    compare(pointer(round, condition.path), condition.operator, condition.value));
}

function validateProfile(profile, { forFinalize = false } = {}) {
  const requiredText = ['batchId', 'directoryName', 'gameName', 'accountAlias', 'captureState'];
  for (const field of requiredText) {
    if (typeof profile[field] !== 'string' || !profile[field].trim()) fail(`profile.${field} 必须是非空字符串`);
  }
  if (profile.batchId !== 'B') fail('本工具只允许批次 B');
  if (profile.accountAlias !== 'abc222') fail('accountAlias 必须固定为 abc222');
  if (!Number.isInteger(profile.gameId) || ![42, 43, 45, 50].includes(profile.gameId)) {
    fail('gameId 必须是 42、43、45 或 50');
  }
  if (containsForbiddenGameIdKey(profile)) fail('profile 禁止包含 integrationGameId');
  const policy = profile.samplingPolicy;
  if (!policy || policy.maxPaidRounds !== 3000 || policy.ordinaryLossTarget !== 200
      || policy.ordinaryWinTarget !== 100 || policy.specialDefaultTarget !== 30
      || policy.rareRewardAbsencePaidRoundThreshold !== 1000
      || policy.userExplicitOverride !== true) {
    fail('采样口径必须是 LOSS=200、WIN=100、特殊默认=30、稀有奖励1000局未出现即视为不存在、最多3000，且标记用户明确覆盖');
  }
  if (!profile.credentialPolicy?.forbiddenPersistedFields?.includes('password')
      || !profile.credentialPolicy?.forbiddenPersistedFields?.includes('token')) {
    fail('credentialPolicy 必须禁止持久化 password/token');
  }
  if (PAUSED_STATES.has(profile.captureState)) return;
  if (profile.captureAuthorization?.parentReleaseSignal !== REQUIRED_RESUME_SIGNAL) {
    fail(`正式采集前必须记录批次 B 的恢复信号 ${REQUIRED_RESUME_SIGNAL}`);
  }
  if (profile.captureAuthorization?.accountConfirmedOnPage !== true
      || typeof profile.captureAuthorization?.accountConfirmationEvidenceRef !== 'string') {
    fail('正式采集前必须在页面确认 abc222，并记录非敏感证据引用');
  }
  if (!forFinalize) return;
  const protocol = profile.protocol;
  for (const field of ['spinEndpointPath', 'paidAmountPointer', 'terminalPointer', 'roundWinPointer']) {
    if (typeof protocol?.[field] !== 'string' || !protocol[field].trim()) {
      fail(`开始正式采集前必须用当前游戏证据填写 profile.protocol.${field}`);
    }
  }
  if (!Array.isArray(protocol.terminalValues) || !protocol.terminalValues.length) {
    fail('开始正式采集前必须填写 profile.protocol.terminalValues');
  }
  if (!Array.isArray(protocol.allowedSpinHosts) || !protocol.allowedSpinHosts.length) {
    fail('正式归档前必须根据真实网络证据填写 profile.protocol.allowedSpinHosts');
  }
  if (!Array.isArray(profile.classificationRules?.ordinaryLoss)
      || !profile.classificationRules.ordinaryLoss.length
      || !Array.isArray(profile.classificationRules?.ordinaryWin)
      || !profile.classificationRules.ordinaryWin.length) {
    fail('正式归档前必须根据真实规则和线协议填写普通 LOSS/WIN 分类条件');
  }
  if (profile.classificationRules?.specialDiscoveryStatus !== 'COMPLETE'
      || !Array.isArray(profile.classificationRules?.specialDiscoveryEvidenceRefs)
      || !profile.classificationRules.specialDiscoveryEvidenceRefs.length) {
    fail('正式归档前必须完成特殊/奖励模式发现并提供证据引用；确认无特殊模式也必须有证据');
  }
}

function validateExchange(exchange, profile, index) {
  const label = `exchange[${index}]`;
  if (exchange.sourceType !== ALLOWED_SOURCE) fail(`${label}.sourceType 必须是 ${ALLOWED_SOURCE}`);
  if (exchange.accountAlias !== profile.accountAlias) fail(`${label}.accountAlias 不匹配`);
  if (exchange.gameId !== profile.gameId) fail(`${label}.gameId 不匹配`);
  if (containsForbiddenGameIdKey(exchange)) fail(`${label} 禁止包含 integrationGameId`);
  if (typeof exchange.capturedAt !== 'string') fail(`${label}.capturedAt 缺失`);
  if (typeof exchange.request?.url !== 'string' || typeof exchange.request?.method !== 'string') {
    fail(`${label} 缺少原始请求方法或URL`);
  }
  if (!exchange.response || !Number.isInteger(exchange.response.status)) {
    fail(`${label} 缺少HTTP响应状态`);
  }
  if (exchange.response.status < 200 || exchange.response.status >= 300) {
    fail(`${label} HTTP响应不是成功状态：${exchange.response.status}`);
  }
  let requestHost;
  try { requestHost = new URL(exchange.request.url).host; }
  catch { fail(`${label}.request.url 不是有效URL`); }
  if (!profile.protocol.allowedSpinHosts.includes(requestHost)) {
    fail(`${label}.request.url 主机不在真实证据确认的 allowedSpinHosts 中：${requestHost}`);
  }
  const serialized = JSON.stringify(exchange);
  if (/112233/.test(serialized)) fail(`${label} 含禁止落盘的密码`);
  if (/"(?:token|password|authorization|cookie|session|web-token|t)"\s*:\s*"(?!\[REDACTED\])/i.test(serialized)) {
    fail(`${label} 含未脱敏凭据`);
  }
}

function endpointPath(rawUrl) {
  try { return new URL(rawUrl).pathname; }
  catch { return null; }
}

function extractStepFacts(step, profile) {
  const facts = {};
  for (const fact of profile.protocol.factPointers || []) {
    if (!fact || typeof fact.name !== 'string' || typeof fact.pointer !== 'string') {
      fail('profile.protocol.factPointers 每项必须提供 name 和 pointer');
    }
    facts[fact.name] = redact(pointer(step, fact.pointer), fact.name);
  }
  return facts;
}

function groupRounds(exchanges, profile) {
  const protocol = profile.protocol;
  const spinSteps = exchanges.filter(exchange =>
    endpointPath(exchange.request.url) === protocol.spinEndpointPath);
  const rounds = [];
  let active = null;
  for (const step of spinSteps) {
    const paidAmount = Number(pointer(step, protocol.paidAmountPointer));
    const terminalValue = pointer(step, protocol.terminalPointer);
    if (paidAmount > 0) {
      if (active && !active.complete) fail(`新付费起点出现时上一局尚未合法结束：${active.roundId}`);
      active = {
        schemaVersion: 1,
        gameId: profile.gameId,
        accountAlias: profile.accountAlias,
        sourceType: ALLOWED_SOURCE,
        roundId: String(pointer(step, protocol.roundIdPointer) ?? `capture-${rounds.length + 1}`),
        paidStartCapturedAt: step.capturedAt,
        paidAmount,
        complete: false,
        steps: [],
        facts: {},
      };
      rounds.push(active);
    }
    if (!active) fail('发现没有归属付费起点的后续 Step');
    if (active.complete) fail(`Round 已终局后出现无新付费起点的游离 Step：${active.roundId}`);
    const stepFacts = extractStepFacts(step, profile);
    for (const [name, value] of Object.entries(stepFacts)) {
      (active.facts[name] ||= []).push(value);
    }
    active.steps.push({
      capturedAt: step.capturedAt,
      requestOrdinal: step.ordinal,
      evidenceRef: step.evidenceRef,
      responseSha256: crypto.createHash('sha256')
        .update(JSON.stringify(redact(step.response?.body))).digest('hex'),
      facts: stepFacts,
    });
    if (protocol.terminalValues.includes(terminalValue)) {
      active.complete = true;
      active.terminalCapturedAt = step.capturedAt;
      active.terminalValue = redact(terminalValue, 'terminalValue');
      active.roundWin = redact(pointer(step, protocol.roundWinPointer), 'roundWin');
      active.terminalEvidenceRef = step.evidenceRef;
    }
  }
  return rounds;
}

function categoryRows(rounds, profile) {
  const completeRounds = rounds.filter(round => round.complete);
  const ordinary = [
    { id: 'ORDINARY_LOSS', target: profile.samplingPolicy.ordinaryLossTarget, conditions: profile.classificationRules?.ordinaryLoss },
    { id: 'ORDINARY_WIN', target: profile.samplingPolicy.ordinaryWinTarget, conditions: profile.classificationRules?.ordinaryWin },
  ];
  const specials = (profile.classificationRules?.specials || []).map(rule => ({
    id: rule.id,
    target: rule.target ?? 30,
    conditions: rule.conditions,
  }));
  return [...ordinary, ...specials].map(rule => {
    const actual = completeRounds.filter(round => matchesRule(round, rule)).length;
    const paidStarts = rounds.length;
    const rareRewardAbsent = rule.id !== 'ORDINARY_LOSS' && rule.id !== 'ORDINARY_WIN'
      && actual === 0 && paidStarts >= profile.samplingPolicy.rareRewardAbsencePaidRoundThreshold;
    const applicable = !rareRewardAbsent;
    let status = rareRewardAbsent ? 'NOT_APPLICABLE_RARE_REWARD_NOT_OBSERVED_WITHIN_1000_PAID_ROUNDS'
      : actual >= rule.target ? 'COVERED'
      : paidStarts >= profile.samplingPolicy.maxPaidRounds ? 'SAMPLE_INSUFFICIENT' : 'IN_PROGRESS';
    return { kind: rule.id, applicable, target: rule.target, actual,
      completeRoundCount: actual, status };
  });
}

async function finalize(args, profile) {
  for (const key of ['exchange-log', 'capture-dir', 'report-dir']) {
    if (!args[key]) fail(`finalize 缺少 --${key}`);
  }
  if (PAUSED_STATES.has(profile.captureState)) fail('浏览器尚未可用且账号未确认，禁止 finalize');
  validateProfile(profile, { forFinalize: true });
  const exchanges = await readJsonl(args['exchange-log']);
  exchanges.forEach((exchange, index) => validateExchange(exchange, profile, index));
  const rounds = groupRounds(exchanges, profile);
  if (!rounds.length) fail('未发现任何真实付费 Round 起点');
  if (rounds.length > profile.samplingPolicy.maxPaidRounds) {
    fail(`付费 Round 起点超过上限 ${profile.samplingPolicy.maxPaidRounds}`);
  }
  const incomplete = rounds.filter(round => !round.complete);
  if (incomplete.length) {
    fail(`存在 ${incomplete.length} 个未走到合法终局的 Round；达到3000起点也必须完成全部后续 Step`);
  }
  const completed = rounds.filter(round => round.complete);
  const categories = categoryRows(rounds, profile);
  const ordinaryLoss = categories.find(category => category.kind === 'ORDINARY_LOSS');
  const ordinaryWin = categories.find(category => category.kind === 'ORDINARY_WIN');
  const overlap = completed.filter(round =>
    matchesRule(round, { conditions: profile.classificationRules.ordinaryLoss })
    && matchesRule(round, { conditions: profile.classificationRules.ordinaryWin }));
  if (overlap.length) fail(`有 ${overlap.length} 个完整 Round 同时被分类为普通 LOSS 和普通 WIN`);
  const unclassifiedOrdinary = completed.filter(round =>
    !matchesRule(round, { conditions: profile.classificationRules.ordinaryLoss })
    && !matchesRule(round, { conditions: profile.classificationRules.ordinaryWin })
    && !(profile.classificationRules.specials || []).some(rule => matchesRule(round, rule)));
  if (unclassifiedOrdinary.length) {
    fail(`有 ${unclassifiedOrdinary.length} 个完整 Round 未归入普通或已确认特殊类别`);
  }
  const allCovered = categories.filter(category => category.applicable)
    .every(category => category.actual >= category.target);
  const atLimit = rounds.length === profile.samplingPolicy.maxPaidRounds;
  if (!allCovered && !atLimit) fail('适用类别尚未达标且付费Round不足3000，禁止停止或生成终态报告');

  const captureDir = path.resolve(args['capture-dir']);
  const reportDir = path.resolve(args['report-dir']);
  await fs.mkdir(captureDir, { recursive: true });
  await fs.mkdir(reportDir, { recursive: true });
  const roundIndex = path.join(captureDir, 'round-index.jsonl');
  const spinIndex = path.join(captureDir, 'spin-index.jsonl');
  await fs.writeFile(roundIndex, rounds.map(row => JSON.stringify(row)).join('\n') + '\n');
  await fs.writeFile(spinIndex, rounds.map((round, index) => JSON.stringify({
    schemaVersion: 1,
    gameId: profile.gameId,
    accountAlias: profile.accountAlias,
    sourceType: ALLOWED_SOURCE,
    paidRoundIndex: index + 1,
    roundId: round.roundId,
    roundEvidenceRef: `round-index.jsonl#${index + 1}`,
    complete: round.complete,
  })).join('\n') + '\n');
  const coverage = {
    targetPerCategory: 200,
    ordinaryLossTarget: profile.samplingPolicy.ordinaryLossTarget,
    ordinaryWinTarget: profile.samplingPolicy.ordinaryWinTarget,
    specialTargetPerCategory: profile.samplingPolicy.specialDefaultTarget,
    rareRewardAbsencePaidRoundThreshold: profile.samplingPolicy.rareRewardAbsencePaidRoundThreshold,
    userExplicitCoverageOverride: {
      ordinaryLossTarget: 200,
      ordinaryWinTarget: 100,
      specialAndRewardTarget: 30,
      rareRewardAbsencePaidRoundThreshold: 1000,
      note: '用户明确指定本批次覆盖口径；普通WIN目标为100。稀有奖励在1000个付费局未出现即视为不存在；特殊类别不使用v14统一200默认值。',
    },
    maxPaidRounds: 3000,
    paidRoundStarts: rounds.length,
    completedRoundCount: completed.length,
    ordinaryRoundCount: (ordinaryLoss?.actual || 0) + (ordinaryWin?.actual || 0),
    categories,
    stopReason: allCovered ? 'ALL_APPLICABLE_CATEGORY_TARGETS_REACHED' : 'MAX_3000_PAID_ROUNDS_REACHED',
  };
  await fs.writeFile(path.join(reportDir, 'round-sample-coverage.json'),
    JSON.stringify({ schemaVersion: 1, gameId: profile.gameId, accountAlias: profile.accountAlias,
      roundSampleCoverage: coverage }, null, 2) + '\n');
  console.log(JSON.stringify({ gameId: profile.gameId, paidRoundStarts: rounds.length,
    completedRoundCount: completed.length, categories, stopReason: coverage.stopReason }, null, 2));
}

const args = parseArgs(process.argv);
if (!args.profile) fail('缺少 --profile');
const profile = await readJson(args.profile);
validateProfile(profile);
if (args.command === 'validate-profile') {
  console.log(JSON.stringify({ ok: true, gameId: profile.gameId,
    captureState: profile.captureState, accountAlias: profile.accountAlias,
    samplingPolicy: profile.samplingPolicy }, null, 2));
} else if (args.command === 'finalize') {
  await finalize(args, profile);
} else {
  fail(`未知命令：${args.command}`);
}
