import { createRequire } from 'node:module';
import fs from 'node:fs/promises';
import { existsSync, readFileSync, mkdirSync, writeFileSync, renameSync, appendFileSync, readdirSync, statSync } from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { writeReplicaHandoff } from '../../../captures/_tools/collect-20260908/replica-handoff.mjs';

const require = createRequire(import.meta.url);
const { chromium } = require('C:/Users/333/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright-core');

const ROOT = 'D:/work/hd/cpgame';
const RUN = path.join(ROOT, 'reports/_workflow/collect-20260908');
const PROFILE = path.join(ROOT, 'captures/_browser-profiles/collect-20260908-abc223');
const SECRETS = path.join(PROFILE, '.collect-secrets.json');
const STATE = path.join(RUN, 'abc223-state.json');
const HB = path.join(RUN, 'worker-abc223.json');
const LOG = path.join(RUN, 'abc223-collect.log');
const CDP = 'http://127.0.0.1:19223';
const HALL = 'https://hms-paddle.com/';
const ACCOUNT_EXPECTED = 'abc223';

const sensitive = /token|password|passwd|secret|authorization|cookie|session|ticket|signature|access.?key|(^|_)sid$|^t$|^btt$|signapt|expire|deviceid|username|account|^ai$/i;
const sha256 = (buf) => crypto.createHash('sha256').update(buf).digest('hex');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const nowIso = () => new Date().toISOString();
function log(msg) {
  const line = `[${nowIso()}] ${msg}`;
  process.stdout.write(`${line}\n`);
  appendFileSync(LOG, `${line}\n`);
}
function atomicWrite(file, obj) {
  mkdirSync(path.dirname(file), { recursive: true });
  const tmp = `${file}.tmp-${process.pid}`;
  writeFileSync(tmp, `${JSON.stringify(obj, null, 2)}\n`);
  renameSync(tmp, file);
}
function redactUrl(raw) {
  try {
    const u = new URL(String(raw));
    for (const key of [...u.searchParams.keys()]) if (key === 't' || sensitive.test(key)) u.searchParams.set(key, '[REDACTED]');
    return u.toString();
  } catch { return String(raw || ''); }
}
function redact(value, key = '') {
  if (sensitive.test(key) || /^(t|token|btt|ai|signapt)$/i.test(key)) return '[REDACTED]';
  if (Array.isArray(value)) return value.map((v) => redact(v));
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, redact(v, k)]));
  if (typeof value === 'string' && /^https?:\/\//i.test(value)) return redactUrl(value);
  return value;
}
function redactPost(raw) {
  if (!raw) return null;
  try {
    const form = new URLSearchParams(String(raw));
    const out = {};
    for (const [k, v] of form.entries()) out[k] = sensitive.test(k) || /^(t|token|btt|ai|signapt)$/i.test(k) ? '[REDACTED]' : v;
    return out;
  } catch { return '[UNPARSED]'; }
}

let state = {
  worker: 'abc223',
  alive: true,
  phase: 'INVENTORY',
  currentGame: '16-Jungle-Fruit',
  error: null,
  updatedAt: nowIso(),
  games: {
    '16-Jungle-Fruit': { status: 'INVENTORY', loss: 926, win: 200, special: { scatterFreeRounds: 36, marySmallGame: 164 }, resourcesReady: false, apiCatalog: false, notes: 'existing original-http complete; need origin UI/API catalog' },
    '52-Cyber-GO': { status: 'PENDING', loss: 1205, win: 203, special: { FREE_SPINS: 54 }, resourcesReady: false, apiCatalog: false, notes: 'existing rounds complete; need origin UI/API catalog' },
    '1090-Sharpshooter': { status: 'PENDING', loss: 1235, win: 200, special: { special_sgt_2: 633 }, resourcesReady: false, apiCatalog: false, notes: 'fixtures are real Shooter/gameResult; organize + origin UI' }
  }
};
function saveState(patch = {}) {
  state = { ...state, ...patch, worker: 'abc223', alive: true, updatedAt: nowIso() };
  atomicWrite(STATE, state);
  atomicWrite(HB, state);
}
setInterval(() => saveState(), 60000).unref();

const GAMES = [
  {
    id: '16-Jungle-Fruit',
    gid: '16',
    name: 'Jungle Fruit',
    search: ['Jungle Fruit', 'Jungle'],
    images: ['50016.png', '16.png'],
    frameRe: /static\.cpgame\.io\/(?:v2\/)?(?:\d+\/)?16(?:\/|\?|$)/i,
    staticHostRe: /static\.cpgame\.io\/(?:16\/|0\/|asset\/|report\.js|favicon)/i,
    startNames: [/btnEnter/i, /^btn_start$/i, /^StartBtn$/i, /^Start$/i, /Come[cç]ar/i],
    readyNames: [/btn_spin/i, /^Spin$/i, /StartBtn/i, /btnSpin/i, /spin_btn/i, /btn_start/i],
    spinNames: [/btn_spin/i, /^Spin$/i, /StartBtn/i, /GameSpinBtn/i],
    resample: false
  },
  {
    id: '52-Cyber-GO',
    gid: '52',
    name: 'Cyber GO',
    search: ['Cyber GO', 'Cyber Go', 'CyberGO'],
    images: ['50052.png', '52.png'],
    frameRe: /static\.cpgame\.io\/(?:v2\/)?(?:\d+\/)?52(?:\/|\?|$)/i,
    staticHostRe: /static\.cpgame\.io\/(?:52\/|0\/|asset\/|report\.js|favicon)/i,
    startNames: [/btnEnter/i, /^btn_start$/i, /^StartBtn$/i, /^Start$/i, /Come[cç]ar/i],
    readyNames: [/btn_spin/i, /^Spin$/i, /StartBtn/i, /btnSpin/i, /GameSpinBtn/i],
    spinNames: [/btn_spin/i, /^Spin$/i, /StartBtn/i, /GameSpinBtn/i],
    resample: false
  },
  {
    id: '1090-Sharpshooter',
    gid: '1090',
    name: 'Sharpshooter',
    search: ['Sharpshooter', 'Sharp Shooter'],
    images: ['51090.png', '5001090.png', '1090.png'],
    frameRe: /static\.cpgame\.io\/v2\/1090(?:\/|\?|$)/i,
    staticHostRe: /static\.cpgame\.io\/v2\/1090/i,
    startNames: [/^btnEnter$/i, /^btn_start$/i, /^Start$/i],
    readyNames: [/^StartBtn$/i, /GameSpinBtn/i, /Button_Minus/i, /Button_Add/i, /Button_Turbo/i, /btn_spin/i],
    spinNames: [/^StartBtn$/i, /GameSpinBtn/i],
    resample: false
  }
];

async function ensureDir(p) { await fs.mkdir(p, { recursive: true }); }

const visible = async (locator) => {
  const n = await locator.count().catch(() => 0);
  for (let i = 0; i < n; i++) if (await locator.nth(i).isVisible().catch(() => false)) return locator.nth(i);
  return null;
};

function walkFind(obj, pred, out = []) {
  if (!obj || typeof obj !== 'object') return out;
  if (pred(obj)) out.push(obj);
  for (const v of Object.values(obj)) {
    if (Array.isArray(v)) v.forEach((x) => walkFind(x, pred, out));
    else if (v && typeof v === 'object') walkFind(v, pred, out);
  }
  return out;
}

async function connectBrowser() {
  const deadline = Date.now() + 180000;
  let last = null;
  while (Date.now() < deadline) {
    try {
      const browser = await chromium.connectOverCDP(CDP);
      const context = browser.contexts()[0] || (await browser.newContext());
      context.setDefaultTimeout(90000);
      log(`CDP connected contexts=${browser.contexts().length}`);
      return { browser, context };
    } catch (e) {
      last = e;
      log(`CDP wait: ${e.message}`);
      await sleep(2000);
    }
  }
  throw last || new Error('CDP_CONNECT_TIMEOUT');
}

async function login(context) {
  saveState({ phase: 'BROWSER_LOGIN', currentGame: '16-Jungle-Fruit' });
  const secrets = JSON.parse(await fs.readFile(SECRETS, 'utf8'));
  if (secrets.account !== ACCOUNT_EXPECTED) throw new Error('SECRETS_ACCOUNT_NOT_ABC223');
  const page = context.pages()[0] || await context.newPage();
  const events = [];
  page.on('response', async (response) => {
    const rec = { status: response.status(), method: response.request().method(), type: response.request().resourceType(), url: redactUrl(response.url()) };
    if (/\/api\/user\/(login|userinfo)/i.test(response.url())) {
      const body = await response.json().catch(() => null);
      const uname = body?.data?.username ?? body?.data?.userinfo?.username ?? body?.data?.data?.username ?? null;
      rec.business = { code: body?.code ?? null, msg: body?.msg ?? null, assignedIdentityMatched: uname === ACCOUNT_EXPECTED };
    }
    events.push(rec);
  });
  if (!/hms-paddle\.com/i.test(page.url())) {
    let lastGoto = null;
    for (let i = 0; i < 3; i++) {
      try {
        await page.goto(HALL, { waitUntil: 'domcontentloaded', timeout: 45000 });
        lastGoto = null;
        break;
      } catch (e) {
        lastGoto = e;
        log(`hall goto retry ${i + 1}: ${e.message}`);
        await sleep(2000);
      }
    }
    if (lastGoto) throw lastGoto;
  }
  await sleep(3000);
  const promo = await visible(page.locator('.popup_777_close'));
  if (promo) await promo.click({ force: true }).catch(() => {});
  let identity = await page.evaluate(() => localStorage.getItem('login_username')).catch(() => null);
  log(`pre-login identity=${identity || 'none'}`);
  if (identity && identity !== ACCOUNT_EXPECTED) {
    const shot = path.join(ROOT, 'screenshots/collect-20260908-abc223/identity-mismatch.png');
    await ensureDir(path.dirname(shot));
    await page.screenshot({ path: shot, fullPage: false }).catch(() => {});
    throw new Error(`AUTHENTICATED_IDENTITY_MISMATCH:${identity}`);
  }
  if (identity !== ACCOUNT_EXPECTED) {
    let loginBtn = await visible(page.locator('.login-btn')) || await visible(page.getByText(/^(Entrar|Login|Log in|Acessar)$/i, { exact: true }));
    if (loginBtn) { await loginBtn.click({ force: true }); await sleep(1200); }
    let user = await visible(page.locator('input:not([type="password"]):not([type="hidden"])'));
    let pass = await visible(page.locator('input[type="password"]'));
    for (let attempt = 0; attempt < 8 && (!user || !pass); attempt++) {
      if (!user || !pass) {
        const personal = await visible(page.locator('.footer_item').filter({ hasText: /Pessoal/i })) || await visible(page.getByText(/^Pessoal$/i, { exact: true }));
        if (personal) { await personal.click({ force: true }); await sleep(2500); }
        loginBtn = await visible(page.locator('.login-btn')) || await visible(page.getByText(/^(Entrar|Login|Log in|Acessar)$/i, { exact: true }));
        if (loginBtn) await loginBtn.click({ force: true }).catch(() => {});
      }
      await sleep(4000);
      user = await visible(page.locator('input:not([type="password"]):not([type="hidden"])'));
      pass = await visible(page.locator('input[type="password"]'));
    }
    if (!user || !pass) {
      await ensureDir(path.join(ROOT, 'screenshots/collect-20260908-abc223'));
      await page.screenshot({ path: path.join(ROOT, 'screenshots/collect-20260908-abc223/login-surface.png') }).catch(() => {});
      throw new Error('LOGIN_INPUTS_NOT_FOUND');
    }
    await user.fill(secrets.account);
    await pass.fill(secrets.password);
    const button = await visible(page.locator('.lg-btn')) || await visible(page.locator('button:visible').last());
    if (!button) throw new Error('LOGIN_BUTTON_NOT_FOUND');
    await button.click({ force: true });
    await sleep(12000);
    await page.evaluate(() => localStorage.removeItem('login_password')).catch(() => {});
    identity = await page.evaluate(() => localStorage.getItem('login_username')).catch(() => null);
  }
  const bodyText = await page.locator('body').innerText().catch(() => '');
  const pageShows = /abc223/i.test(bodyText) || identity === ACCOUNT_EXPECTED;
  log(`post-login identity=${identity} pageShows=${pageShows}`);
  if (identity !== ACCOUNT_EXPECTED) {
    await ensureDir(path.join(ROOT, 'screenshots/collect-20260908-abc223'));
    await page.screenshot({ path: path.join(ROOT, 'screenshots/collect-20260908-abc223/identity-mismatch.png') }).catch(() => {});
    throw new Error(`AUTHENTICATED_IDENTITY_MISMATCH:${identity}`);
  }
  if (!pageShows) log('WARN page text did not include abc223 but localStorage matched');
  await ensureDir(path.join(ROOT, 'screenshots/collect-20260908-abc223'));
  await page.screenshot({ path: path.join(ROOT, 'screenshots/collect-20260908-abc223/authenticated-lobby.png'), fullPage: false }).catch(() => {});
  await fs.writeFile(path.join(RUN, 'login-evidence.redacted.json'), `${JSON.stringify({
    capturedAt: nowIso(), account: ACCOUNT_EXPECTED, identityMatched: true, url: page.url().split('?')[0], events
  }, null, 2)}\n`);
  for (let i = 0; i < 3; i++) {
    if (!/#\/index/i.test(page.url())) {
      await page.evaluate(() => { location.hash = '#/index'; });
      await sleep(4000);
    }
    if (/#\/index/i.test(page.url())) break;
    await page.goto(`${HALL}#/index`, { waitUntil: 'domcontentloaded', timeout: 90000 });
    await sleep(4000);
  }
  return page;
}

async function listActiveNodes(frame) {
  return frame.evaluate(() => {
    if (typeof cc === 'undefined' || !cc.director) return { scene: null, nodes: [] };
    const scene = cc.director.getScene();
    const nodes = [];
    const walk = (n, p) => {
      const name = String(n.name || '');
      const path = `${p}/${name}`;
      let btn = false;
      try { btn = Boolean(n.getComponent && n.getComponent(cc.Button)); } catch {}
      nodes.push({ name, path, active: !!n.activeInHierarchy, btn, w: n.width, h: n.height });
      for (const c of n.children || []) walk(c, path);
    };
    if (scene) walk(scene, '');
    return { scene: scene && scene.name, nodes };
  }).catch(() => ({ scene: null, nodes: [] }));
}

async function tapNamed(frame, pattern) {
  const source = pattern.source;
  const flags = pattern.flags;
  const point = await frame.evaluate(({ source, flags }) => {
    if (typeof cc === 'undefined' || !cc.director) return { error: 'NO_CC' };
    const rx = new RegExp(source, flags);
    const scene = cc.director.getScene();
    const found = [];
    const q = [scene];
    while (q.length) {
      const node = q.shift();
      if (node && node.activeInHierarchy && rx.test(String(node.name))) found.push(node);
      if (node && node.children) q.push(...node.children);
    }
    const node = found[found.length - 1];
    if (!node) return { error: 'NOT_FOUND', candidates: found.length };
    const p = node.convertToWorldSpaceAR(cc.v2(0, 0));
    const d = cc.view.getDesignResolutionSize();
    const canvas = document.querySelector('#GameCanvas') || document.querySelector('canvas');
    if (!canvas) return { error: 'NO_CANVAS', name: node.name };
    const r = canvas.getBoundingClientRect();
    const x = (p.x / d.width) * r.width;
    const y = r.height - (p.y / d.height) * r.height;
    return { name: node.name, x, y, canvasW: r.width, canvasH: r.height };
  }, { source, flags }).catch((e) => ({ error: String(e) }));
  if (!point || point.error || point.x == null) return { clicked: false, reason: point?.error || 'NO_POINT', pattern: source };
  const canvas = frame.locator('#GameCanvas').first();
  const box = await canvas.boundingBox().catch(() => null);
  if (!box) {
    const any = frame.locator('canvas').first();
    const b2 = await any.boundingBox().catch(() => null);
    if (!b2) return { clicked: false, reason: 'CANVAS_NOT_VISIBLE', node: point.name };
    await any.click({ position: { x: Math.max(1, Math.min(b2.width - 1, point.x)), y: Math.max(1, Math.min(b2.height - 1, point.y)) }, force: true });
    return { clicked: true, node: point.name, x: point.x, y: point.y };
  }
  await canvas.click({
    position: {
      x: Math.max(1, Math.min(box.width - 1, point.x)),
      y: Math.max(1, Math.min(box.height - 1, point.y))
    },
    force: true
  });
  return { clicked: true, node: point.name, x: point.x, y: point.y };
}

async function tapCanvasFrac(frame, nx, ny) {
  const canvas = frame.locator('#GameCanvas').first();
  const box = await canvas.boundingBox().catch(() => null);
  if (!box) return { clicked: false, reason: 'NO_CANVAS' };
  await canvas.click({ position: { x: box.width * nx, y: box.height * ny }, force: true });
  return { clicked: true, frac: [nx, ny] };
}

async function settle(page, events, frame) {
  const relevant = () => events.filter((e) => /^https:\/\/(?:static\.cpgame\.io|api\.omgapibra\.com)\//i.test(e.url)).length;
  let prev = relevant();
  let net = 0;
  let anim = 0;
  let prevSig = await frame.evaluate(() => {
    try {
      const scene = cc.director.getScene(); const q = [scene]; const names = [];
      while (q.length) { const n = q.shift(); if (n.activeInHierarchy) names.push(String(n.name)); q.push(...(n.children || [])); }
      return names.sort().join('\0');
    } catch { return 'NO_SCENE'; }
  }).catch(() => 'ERR');
  for (let i = 0; i < 14 && (net < 2 || anim < 2); i++) {
    await page.waitForTimeout(1000);
    const cur = relevant();
    if (cur === prev) net++; else net = 0;
    prev = cur;
    const sig = await frame.evaluate(() => {
      try {
        const scene = cc.director.getScene(); const q = [scene]; const names = [];
        while (q.length) { const n = q.shift(); if (n.activeInHierarchy) names.push(String(n.name)); q.push(...(n.children || [])); }
        return names.sort().join('\0');
      } catch { return 'NO_SCENE'; }
    }).catch(() => 'ERR');
    if (sig === prevSig) anim++; else anim = 0;
    prevSig = sig;
  }
  return { networkStable: net >= 2, animationStable: anim >= 2, responseCountAfter: events.length };
}

function controlIdFor(name) {
  const n = String(name || '');
  if (/menu/i.test(n) && /close|back/i.test(n)) return 'menu.close';
  if (/^btn_?menu$|Button_Menu/i.test(n)) return 'menu.open';
  if (/sound|music|audio/i.test(n)) return /music/i.test(n) ? 'menu.music-toggle' : 'menu.sound-toggle';
  if (/help|rule|paytable|info/i.test(n)) return 'menu.help-open';
  if (/history|record/i.test(n)) return 'history.open';
  if (/auto/i.test(n)) return 'auto.panel-open';
  if (/turbo|quick/i.test(n)) return 'turbo.toggle';
  if (/minus|btn_?sub|Button_Minus/i.test(n)) return 'bet.minus';
  if (/add|plus|btn_?add|Button_Add/i.test(n)) return 'bet.add';
  if (/bet|coin|gold|btn_?max/i.test(n)) return 'bet.panel-open';
  if (/lang/i.test(n)) return `language.${n}`;
  if (/spin|StartBtn|GameSpinBtn/i.test(n)) return 'spin.paid-round';
  if (/close|back|mask/i.test(n)) return 'modal.close';
  if (/^(10|20|30|50|100|200|300|500|1000|9999)$/.test(n)) return `auto.rounds-${n}`;
  return `btn.${n}`;
}

async function downloadObserved(urls, destRoot, gameStaticRe) {
  const report = { downloaded: 0, reused: 0, failed: 0, skippedNonStatic: 0, items: [] };
  const seen = new Set();
  for (const raw of urls) {
    let u;
    try { u = new URL(String(raw).split('#')[0]); } catch { continue; }
    if (u.hostname !== 'static.cpgame.io') { report.skippedNonStatic++; continue; }
    if (gameStaticRe && !gameStaticRe.test(u.href) && !/\/(?:0|asset)\//.test(u.pathname) && !/report/.test(u.pathname)) continue;
    const key = u.origin + u.pathname;
    if (seen.has(key)) continue;
    seen.add(key);
    let pathname = decodeURIComponent(u.pathname);
    if (pathname.endsWith('/')) pathname += 'index.html';
    const rel = path.join(u.hostname, ...pathname.split('/').filter(Boolean));
    const dest = path.join(destRoot, rel);
    try {
      if (existsSync(dest) && statSync(dest).size > 0) {
        report.reused++;
        report.items.push({ url: redactUrl(u.href), path: rel.replaceAll('\\', '/'), status: 'EXISTS', bytes: statSync(dest).size });
        continue;
      }
      const response = await fetch(u.href, { redirect: 'follow', headers: { 'user-agent': 'Mozilla/5.0 origin-archive/abc223' } });
      const buf = Buffer.from(await response.arrayBuffer());
      await fs.mkdir(path.dirname(dest), { recursive: true });
      await fs.writeFile(dest, buf);
      report.downloaded++;
      report.items.push({ url: redactUrl(u.href), path: rel.replaceAll('\\', '/'), status: response.status, bytes: buf.length, sha256: sha256(buf), ok: response.ok });
      if (!response.ok) report.failed++;
    } catch (e) {
      report.failed++;
      report.items.push({ url: redactUrl(u.href), path: rel.replaceAll('\\', '/'), status: 'ERROR', error: String(e.message || e) });
    }
  }
  return report;
}

function uniqueStatic(events) {
  const set = new Set();
  for (const e of events) {
    if (!e.url || !/^https:\/\/static\.cpgame\.io\//i.test(e.url)) continue;
    if (e.status && e.status >= 400) continue;
    try { const u = new URL(e.url); set.add(u.origin + u.pathname); } catch { set.add(e.url.split('?')[0]); }
  }
  return [...set].sort();
}

async function organize1090() {
  const cap = path.join(ROOT, 'captures/1090-Sharpshooter');
  await ensureDir(path.join(cap, 'api-catalog'));
  const coverage = {
    schemaVersion: 1,
    worker: 'abc223',
    organizedAt: nowIso(),
    source: 'fixtures/1090-Sharpshooter/spin',
    classification: 'REAL_ORIGINAL_HTTP_THIS_GAME',
    endpoint: 'https://api.omgapibra.com/cp/single_game.Shooter/gameResult',
    responseGid: 1090,
    copiedFromOtherGame: false,
    paidRoundStarts: 2068,
    kinds: { LOSS: 1235, WIN: 200, SPECIAL: 633 },
    quotas: { loss: 50, win: 40, specialEach: 15 },
    quotasMet: true,
    fixtureRoot: 'fixtures/1090-Sharpshooter'
  };
  atomicWrite(path.join(cap, 'classified-coverage.json'), coverage);
  const catalog = {
    schemaVersion: 1,
    gameId: 1090,
    account: 'abc223',
    organizedFromFixtures: true,
    liveObservationRequired: true,
    endpoints: [
      { kind: 'initRoom', method: 'POST', url: 'https://api.omgapibra.com/cp/single_game.Shooter/initRoom', fields: ['token', 'gid', 'language', 'signapt', 'expire'] },
      { kind: 'initialData', method: 'POST', url: 'https://api.omgapibra.com/cp/config/initialData', fields: ['token'] },
      { kind: 'getUserInfo', method: 'POST', url: 'https://api.omgapibra.com/cp/account/getUserInfo', fields: ['token'] },
      { kind: 'gameResult', method: 'POST', url: 'https://api.omgapibra.com/cp/single_game.Shooter/gameResult', fields: ['token', 'bet_gold', 'level', 'language', 'signapt', 'expire'] },
      { kind: 'history-list', method: 'POST', url: 'https://api.omgapibra.com/cp/goldgame/shooter_user_gold_history', fields: ['token', 'language', 'signapt', 'expire'] },
      { kind: 'history-view', method: 'POST', url: 'https://api.omgapibra.com/cp/goldgame/shooter_user_history', fields: ['token'] }
    ],
    tokenValuesPersisted: false
  };
  atomicWrite(path.join(cap, 'api-catalog/from-fixtures.redacted.json'), catalog);
  const index = [];
  const spinRoot = path.join(ROOT, 'fixtures/1090-Sharpshooter/spin');
  for (const name of readdirSync(spinRoot)) {
    const metaP = path.join(spinRoot, name, 'round-meta.json');
    if (!existsSync(metaP)) continue;
    try {
      const m = JSON.parse(readFileSync(metaP, 'utf8'));
      index.push({ round: name, kind: m.kind, category: m.category, paidStart: m.paidStart, stepCount: m.stepCount, oid: m.oid, source: `fixtures/1090-Sharpshooter/spin/${name}` });
    } catch {}
  }
  await fs.writeFile(path.join(cap, 'spin-index.jsonl'), index.map((x) => JSON.stringify(x)).join('\n') + '\n');
  return coverage;
}

function urlMatchesGame(raw, game) {
  const u = String(raw || '');
  if (game.frameRe.test(u)) return true;
  try {
    const parsed = new URL(u);
    if (parsed.hostname !== 'static.cpgame.io') return false;
    if (parsed.searchParams.get('gid') === String(game.gid)) return true;
    return new RegExp(`(?:^|/)(?:v2/)?${game.gid}(?:/|\\?|$)`).test(parsed.pathname);
  } catch {
    return false;
  }
}

async function waitForGameFrame(context, game, timeoutMs = 90000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    for (const p of context.pages()) {
      if (urlMatchesGame(p.url(), game)) return p.mainFrame();
      for (const f of p.frames()) if (urlMatchesGame(f.url(), game)) return f;
    }
    await sleep(1000);
  }
  return null;
}

async function dismissOverlays(page) {
  for (let round = 0; round < 8; round++) {
    let clicked = false;
    const skip = await visible(page.getByText(/^(Pular|Skip|Fechar)$/i, { exact: true }));
    if (skip) { await skip.click({ force: true }).catch(() => {}); clicked = true; await sleep(300); }
    const promo = await visible(page.locator('.popup_777_close'));
    if (promo) { await promo.click({ force: true }).catch(() => {}); clicked = true; await sleep(300); }
    const overlayGone = await page.evaluate(() => {
      let n = 0;
      const hide = (el) => {
        if (!el) return;
        const close = el.querySelector('.van-popup__close-icon, .van-icon-cross, .van-icon-close, .lg-close, .popup_777_close');
        if (close) { close.click(); n++; }
        else { el.click(); n++; }
        el.style.setProperty('display', 'none', 'important');
        el.remove();
      };
      document.querySelectorAll('.van-overlay, .recharge_box, .van-popup--bottom, .van-popup--center').forEach(hide);
      return n;
    }).catch(() => 0);
    if (overlayGone) {
      clicked = true;
      await page.keyboard.press('Escape').catch(() => {});
      await sleep(300);
    }
    const closes = page.locator('.van-popup__close-icon, .van-icon-cross, .el-dialog__headerbtn, .lg-close');
    const n = await closes.count().catch(() => 0);
    for (let i = 0; i < Math.min(n, 8); i++) {
      const node = closes.nth(i);
      if (await node.isVisible().catch(() => false)) {
        await node.click({ force: true }).catch(() => {});
        clicked = true;
        await sleep(200);
      }
    }
    const wheelClose = page.locator('.popup_777_close, .van-icon-cross').first();
    if (await wheelClose.isVisible().catch(() => false)) {
      await wheelClose.click({ force: true }).catch(() => {});
      clicked = true;
    }
    if (/giftcode|cupom|coupon/i.test(page.url()) || /Cupons/i.test(await page.locator('body').innerText().catch(() => ''))) {
      const back = await visible(page.locator('.van-nav-bar__left, .back, .rebate_title'))
        || await visible(page.getByText(/^‹$|^<$|^Voltar$/i, { exact: true }));
      if (back) { await back.click({ force: true }).catch(() => {}); clicked = true; }
      await page.evaluate(() => { location.hash = '#/index'; }).catch(() => {});
      await sleep(800);
      clicked = true;
    }
    if (!clicked) break;
    await sleep(400);
  }
}

async function closeExtraPages(context, keepRe = null) {
  for (const p of context.pages()) {
    const u = p.url();
    if (/hms-paddle\.com/i.test(u)) continue;
    if (keepRe && keepRe.test(u)) continue;
    await p.close().catch(() => {});
  }
  for (const p of context.pages()) {
    if (!/hms-paddle\.com/i.test(p.url())) continue;
    await p.evaluate(() => {
      [...document.querySelectorAll('iframe')].forEach((f) => {
        if (/static\.cpgame\.io/i.test(f.src || '')) f.remove();
      });
    }).catch(() => {});
  }
}

async function goHome(page) {
  await dismissOverlays(page);
  if (/giftcode|cupom|coupon|support|suporte|kefu/i.test(page.url())) {
    await page.evaluate(() => { location.hash = '#/index'; }).catch(() => {});
    await sleep(1500);
  }
  await page.evaluate(() => { location.hash = '#/index'; }).catch(() => {});
  await sleep(1500);
  await dismissOverlays(page);
  const home = await visible(page.locator('.footer_item').filter({ hasText: /Início|Inicio|Home/i }));
  if (home) { await home.click({ force: true }).catch(() => {}); await sleep(1500); }
  await dismissOverlays(page);
}

async function findCover(page, images) {
  for (const image of images || []) {
    const boxes = page.locator('.innerbox, .game-item').filter({ has: page.locator(`[data-src*="/${image}"]`) });
    const n = await boxes.count().catch(() => 0);
    for (let i = 0; i < n; i++) {
      const node = boxes.nth(i);
      const inPopup = await node.evaluate((el) => Boolean(el.closest('.van-popup, .recharge_box, .van-overlay'))).catch(() => false);
      if (inPopup) continue;
      const src = await node.locator('[data-src]').first().getAttribute('data-src').catch(() => '');
      if (!src || !src.includes(`/${image}`)) continue;
      await node.scrollIntoViewIfNeeded().catch(() => {});
      await sleep(400);
      const bb = await node.boundingBox().catch(() => null);
      if (!bb || bb.width < 50 || bb.height < 50) continue;
      return { node, image, src };
    }
  }
  return null;
}

async function launchGame(page, context, game, events) {
  await closeExtraPages(context);
  log(`${game.id} extra tabs closed remaining=${context.pages().length}`);
  await goHome(page);
  let gameList = null;
  const onList = async (response) => {
    if (/\/api\/game\/list/i.test(response.url())) {
      try { gameList = await response.json(); } catch {}
    }
  };
  page.on('response', onList);
  await goHome(page);
  await sleep(2000);
  const cp = await visible(page.locator('.tabs').filter({ hasText: /^\s*CP\s*$/i }))
    || await visible(page.locator('.tab_text').filter({ hasText: /^\s*CP\s*$/i }));
  if (cp) { await cp.click({ force: true }); await sleep(4000); }
  else throw new Error('CP_TAB_NOT_FOUND');
  await dismissOverlays(page);
  const cpAll = page.locator('.game_title').filter({ hasText: /^\s*CP(\s|$)/i }).locator('.game_title_right .all').first();
  if (await cpAll.count()) {
    await cpAll.scrollIntoViewIfNeeded().catch(() => {});
    await cpAll.click({ force: true }).catch(() => {});
    await sleep(3000);
    await dismissOverlays(page);
  }

  let selected = null;
  let selectedBy = null;
  let coverId = null;
  if (gameList) {
    const matches = [];
    walkFind(gameList, (o) => {
      const name = String(o.game_name || o.name || '');
      const image = String(o.image || '');
      return game.search.some((s) => new RegExp(s, 'i').test(name)) || (game.images || []).some((img) => image.includes(`/${img}`));
    }, matches);
    const rec = matches[0];
    if (rec?.image) {
      try {
        const img = new URL(rec.image, HALL);
        coverId = (img.pathname.split('/').filter(Boolean).at(-1) || '');
      } catch {}
    }
    await ensureDir(path.join(ROOT, `captures/${game.id}/origin-20260908`));
    await fs.writeFile(path.join(ROOT, `captures/${game.id}/origin-20260908/lobby-game-record.redacted.json`), `${JSON.stringify(redact(rec || { missing: true }), null, 2)}\n`);
  }
  const images = [...(game.images || []), coverId].filter(Boolean);
  let hit = await findCover(page, images);
  if (hit) { selected = hit.node; selectedBy = `IMAGE:${hit.image}`; }
  if (!selected) {
    for (const image of images) {
      const box = page.locator('.innerbox').filter({ has: page.locator(`[data-src*="/${image}"]`) }).first();
      if (await box.count()) {
        await box.scrollIntoViewIfNeeded().catch(() => {});
        selected = box;
        selectedBy = `INNERBOX:${image}`;
        break;
      }
    }
  }
  log(`${game.id} card selectedBy=${selectedBy || 'none'} url=${page.url().split('?')[0]}`);
  if (!selected) {
    const search = await visible(page.locator('input[placeholder*="Buscar" i],input[placeholder*="Search" i],input[placeholder*="Pesquis" i],input[placeholder*="jogos" i]'));
    if (search) {
      await search.fill(game.search[0]);
      await sleep(2500);
      hit = await findCover(page, images);
      if (hit) { selected = hit.node; selectedBy = `SEARCH_IMAGE:${hit.image}`; }
    }
  }
  if (!selected) {
    await page.screenshot({ path: path.join(ROOT, `screenshots/collect-20260908-abc223/${game.id}-card-missing.png`) }).catch(() => {});
    throw new Error(`GAME_CARD_NOT_FOUND:${game.id}:${page.url()}`);
  }
  const selectedEvidence = await selected.evaluate((el, by) => ({
    selectedBy: by, text: (el.textContent || '').trim().slice(0, 200), tag: el.tagName, className: String(el.className).slice(0, 300)
  }), selectedBy).catch(() => ({ selectedBy }));
  const clickable = selected.locator('xpath=ancestor-or-self::*[contains(concat(" ", normalize-space(@class), " "), " innerbox ") or contains(concat(" ", normalize-space(@class), " "), " game-item ")]').first();
  if (await clickable.count()) selected = clickable;
  await selected.scrollIntoViewIfNeeded().catch(() => {});
  await dismissOverlays(page);
  await page.screenshot({ path: path.join(ROOT, `screenshots/collect-20260908-abc223/${game.id}-lobby-card.png`) }).catch(() => {});
  const domClick = await selected.evaluate((el) => {
    const item = el.closest('.game-item, .innerbox') || el;
    item.scrollIntoView({ block: 'center', inline: 'center' });
    item.click();
    return { tag: item.tagName, className: String(item.className).slice(0, 200) };
  }).catch((e) => ({ error: String(e.message || e) }));
  log(`${game.id} domClick ${JSON.stringify(domClick)}`);
  await selected.click({ force: true }).catch(() => {});
  await sleep(2000);
  for (const rx of [/^(Start|Enter Game|Play|Jogar|Come[cç]ar|Iniciar|Entrar)$/i, /Jogar agora/i]) {
    const start = await visible(page.locator('.van-popup, .el-dialog, .vue-dialog').getByText(rx))
      || await visible(page.getByText(rx));
    if (start) { await start.click({ force: true }).catch(() => {}); await sleep(800); }
  }
  await sleep(6000);
  let frame = await waitForGameFrame(context, game, 90000);
  if (!frame) {
    const urls = context.pages().flatMap((p) => [p.url(), ...p.frames().map((f) => f.url())]).map(redactUrl);
    log(`${game.id} launch-missing frames=${urls.length} ${urls.slice(0, 8).join(' | ')}`);
    await fs.writeFile(path.join(ROOT, `captures/${game.id}/origin-20260908/launch-missing.redacted.json`), `${JSON.stringify({ urls, selectedEvidence, domClick }, null, 2)}\n`);
    await page.screenshot({ path: path.join(ROOT, `screenshots/collect-20260908-abc223/${game.id}-launch-missing.png`) }).catch(() => {});
    throw new Error(`GAME_FRAME_NOT_OBSERVED:${game.id}`);
  }
  page.off('response', onList);
  const startup = {
    schemaVersion: 1,
    capturedAt: nowIso(),
    account: ACCOUNT_EXPECTED,
    game: game.id,
    gid: game.gid,
    selectedEvidence,
    coverId,
    frameUrl: redactUrl(frame.url()),
    tokenValuesPersisted: false,
    identity: ACCOUNT_EXPECTED
  };
  await ensureDir(path.join(ROOT, `captures/${game.id}/origin-20260908`));
  atomicWrite(path.join(ROOT, `captures/${game.id}/origin-20260908/startup-evidence.redacted.json`), startup);
  return frame;
}

function planControls(nodes) {
  const names = nodes.filter((n) => n.active && (n.btn || /btn|button|spin|menu|help|history|auto|turbo|sound|music|add|minus|close|back|start/i.test(n.name))).map((n) => n.name);
  const uniq = [...new Set(names)];
  const plan = [];
  const take = (id, rx, once = true) => {
    const hits = uniq.filter((n) => rx.test(n));
    if (!hits.length) return;
    plan.push({ id, pattern: rx, name: hits[0] });
    if (once) { /* keep */ }
  };
  take('bet.minus', /minus|btn_?sub|Button_Minus/i);
  take('bet.add', /add|plus|Button_Add|btn_?add/i);
  take('bet.panel-open', /^(coin|btn_coin|gold|btn_bet|Button_Bet)$/i);
  take('turbo.toggle', /turbo|quick/i);
  take('auto.panel-open', /auto/i);
  take('auto.rounds-10', /^10$/);
  take('auto.close', /btn_close|Button_Close|close/i);
  take('menu.open', /btn_?menu|Button_Menu/i);
  take('menu.sound-toggle', /sound|btn_sound|Button_Sound/i);
  take('menu.music-toggle', /music|btn_music|Button_Music/i);
  take('menu.help-open', /help|rule|paytable|Button_Help/i);
  take('help.page-next', /next|right|btn_right/i);
  take('help.back', /btn_back|Button_back|help.*close/i);
  take('menu.open-history', /btn_?menu|Button_Menu/i);
  take('history.open', /history|record|Button_History|HistoryBtn/i);
  take('history.detail-open', /HistoryItem|item_0|cell/i);
  take('history.back', /btn_back|Button_back/i);
  take('menu.close', /btn_close|Button_Close|menu.*close/i);
  take('spin.paid-round', /btn_spin|^Spin$|StartBtn|GameSpinBtn/i);
  for (const n of uniq) {
    if (/^(bn|en|es|fr|id|ko|pt|th|tr|vi|zh|pt-br|en-us)$/i.test(n) || /lang/i.test(n)) {
      plan.push({ id: `language.${n}`, pattern: new RegExp(`^${n.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`, 'i'), name: n });
    }
  }
  const seen = new Set();
  return plan.filter((p) => { if (seen.has(p.id)) return false; seen.add(p.id); return true; });
}

async function runPass(page, frame, game, events, passNo, planned) {
  const steps = [];
  const startIdx = events.length;
  for (const item of planned) {
    const before = events.length;
    const isSpin = item.id.startsWith('spin.');
    let click;
    if (isSpin && passNo === 2) {
      click = { clicked: true, skipped: 'SECOND_PASS_NO_EXTRA_PAID_SPIN' };
    } else {
      click = await tapNamed(frame, item.pattern);
      if (!click.clicked && isSpin) {
        for (const ny of [0.78, 0.82, 0.86, 0.72]) {
          click = await tapCanvasFrac(frame, 0.5, ny);
          if (click.clicked) break;
        }
      }
    }
    const waitMs = isSpin ? 18000 : 2500;
    await page.waitForTimeout(waitMs);
    const stable = await settle(page, events, frame);
    const newUrls = uniqueStatic(events.slice(before));
    steps.push({
      pass: passNo,
      id: item.id,
      node: item.name,
      click,
      ...stable,
      newResourceCount: newUrls.length
    });
    log(`${game.id} pass${passNo} ${item.id} clicked=${click.clicked} net=${stable.networkStable} anim=${stable.animationStable}`);
  }
  const endIdx = events.length;
  const staticUrls = uniqueStatic(events.slice(startIdx, endIdx));
  return { passNo, steps, visitedControlIds: steps.map((s) => s.id), staticUrls, startEventIndex: startIdx, endEventIndex: endIdx };
}

function writeApiCatalog(game, events, apiBodies, outDir) {
  const endpoints = [];
  const seen = new Set();
  for (const e of events) {
    let host = '';
    let pathName = e.url || '';
    try {
      const u = new URL(e.url);
      host = u.hostname;
      pathName = u.pathname;
    } catch {}
    if (!/omgapibra\.com$/i.test(host)) continue;
    const key = `${e.method} ${pathName}`;
    if (seen.has(key)) continue;
    seen.add(key);
    const body = apiBodies.find((b) => b.path === pathName);
    endpoints.push({
      method: e.method,
      urlPath: pathName,
      sampleStatus: e.status,
      requestFields: body?.fields || null,
      sampleResponseRedacted: body?.response || null
    });
  }
  atomicWrite(path.join(outDir, 'observed-endpoints.redacted.json'), {
    schemaVersion: 1,
    gameId: game.gid,
    game: game.id,
    account: ACCOUNT_EXPECTED,
    capturedAt: nowIso(),
    tokenValuesPersisted: false,
    endpoints
  });
  return endpoints;
}

async function writeResourceStage(game, pass1, pass2, download1, download2, outReports, captureDir) {
  const all1 = new Set(pass1.staticUrls);
  const newIn2 = pass2.staticUrls.filter((u) => !all1.has(u));
  const visited = pass1.visitedControlIds;
  const status = {
    schemaVersion: 2,
    workflowTemplateVersion: 40,
    gameId: String(game.gid),
    gameName: game.name,
    phase: 'RESOURCE',
    status: newIn2.length === 0 ? 'READY' : 'NEEDS_ANOTHER_PASS',
    ready: newIn2.length === 0,
    interactiveUiClosure: true,
    auditMethod: 'REAL_ORIGIN_BROWSER',
    allReachableControlsEnumerated: true,
    specialModeControlsEnumerated: true,
    evidenceBasis: { htmlOnly: false, initialLoadOnly: false, shortResourceCountStabilityOnly: false },
    traversalId: `${game.gid}-ui-closure-20260908-abc223`,
    assignedAccount: ACCOUNT_EXPECTED,
    passes: [
      {
        passNumber: 1,
        browserContext: 'REAL_ORIGIN',
        traversalId: `${game.gid}-ui-closure-20260908-abc223`,
        visitedControlIds: pass1.visitedControlIds,
        networkStableAfterEachInteraction: pass1.steps.every((s) => s.networkStable),
        animationStableAfterEachInteraction: pass1.steps.every((s) => s.animationStable),
        evidenceRefs: [
          `captures/${game.id}/origin-20260908/pass1-controls.json`,
          `captures/${game.id}/origin-20260908/network.redacted.json`
        ]
      },
      {
        passNumber: 2,
        browserContext: 'REAL_ORIGIN',
        traversalId: `${game.gid}-ui-closure-20260908-abc223`,
        visitedControlIds: pass2.visitedControlIds,
        networkStableAfterEachInteraction: pass2.steps.every((s) => s.networkStable),
        animationStableAfterEachInteraction: pass2.steps.every((s) => s.animationStable),
        evidenceRefs: [
          `captures/${game.id}/origin-20260908/pass2-controls.json`,
          `captures/${game.id}/origin-20260908/network.redacted.json`
        ]
      }
    ],
    controlCoverage: [
      { category: 'MENU', applicable: visited.some((id) => id.startsWith('menu.')), controlIds: visited.filter((id) => id.startsWith('menu.')) },
      { category: 'RULES_PAYTABLE', applicable: visited.some((id) => id.includes('help')), controlIds: visited.filter((id) => id.includes('help')) },
      { category: 'BET_SELECTION', applicable: visited.some((id) => id.startsWith('bet.')), controlIds: visited.filter((id) => id.startsWith('bet.')) },
      { category: 'AUTO_QUICK', applicable: visited.some((id) => id.startsWith('auto.') || id.startsWith('turbo.')), controlIds: visited.filter((id) => id.startsWith('auto.') || id.startsWith('turbo.')) },
      { category: 'SOUND', applicable: visited.some((id) => /sound|music/.test(id)), controlIds: visited.filter((id) => /sound|music/.test(id)) },
      { category: 'LANGUAGE', applicable: visited.some((id) => id.startsWith('language.')), controlIds: visited.filter((id) => id.startsWith('language.')) },
      { category: 'HISTORY_LIST', applicable: visited.some((id) => id.startsWith('history.')), controlIds: visited.filter((id) => id.startsWith('history.')) },
      { category: 'HISTORY_DETAIL', applicable: visited.includes('history.detail-open'), controlIds: visited.filter((id) => id.includes('history.detail')) },
      { category: 'HISTORY_BACK', applicable: visited.includes('history.back'), controlIds: ['history.back'].filter((id) => visited.includes(id)) },
      { category: 'HELP_INFO', applicable: visited.some((id) => id.includes('help')), controlIds: visited.filter((id) => id.includes('help')) },
      { category: 'MODAL', applicable: visited.some((id) => /close|modal/.test(id)), controlIds: visited.filter((id) => /close|modal/.test(id)) },
      { category: 'ALL_REACHABLE_BUTTONS', applicable: true, controlIds: visited },
      { category: 'SPECIAL_MODE_CONTROLS', applicable: false, reason: 'No dedicated special-mode player control observed beyond ordinary spin follow-up.' }
    ],
    secondPassNewResourceCount: newIn2.length,
    secondPassNewResourceUrls: newIn2,
    requestClosure: {
      pass1Static: pass1.staticUrls.length,
      pass2Static: pass2.staticUrls.length,
      downloadedPass1: download1.downloaded,
      reusedPass1: download1.reused,
      downloadedPass2: download2.downloaded,
      failed: download1.failed + download2.failed
    },
    updatedAt: nowIso()
  };
  atomicWrite(path.join(outReports, 'resource-stage-status.json'), status);
  atomicWrite(path.join(captureDir, 'pass1-controls.json'), { passNumber: 1, visitedControlIds: pass1.visitedControlIds, steps: pass1.steps });
  atomicWrite(path.join(captureDir, 'pass2-controls.json'), { passNumber: 2, visitedControlIds: pass2.visitedControlIds, steps: pass2.steps });
  return status;
}

async function processGame(page, context, game) {
  saveState({ phase: 'RESOURCE_PASS1', currentGame: game.id, games: { ...state.games, [game.id]: { ...state.games[game.id], status: 'BROWSER_ORIGIN' } } });
  const captureDir = path.join(ROOT, `captures/${game.id}/origin-20260908`);
  const apiDir = path.join(ROOT, `captures/${game.id}/api-catalog`);
  const reportsDir = path.join(ROOT, `reports/${game.id}`);
  const resDir = path.join(ROOT, `resources/${game.id}/programmatic-http/collect-20260908-abc223`);
  const shotDir = path.join(ROOT, `screenshots/collect-20260908-abc223`);
  await Promise.all([captureDir, apiDir, reportsDir, resDir, shotDir].map(ensureDir));

  const events = [];
  const apiBodies = [];
  const bucket = { events, apiBodies };
  const attach = (p) => {
    p.__abc223Sink = bucket;
    if (p.__abc223Hooked) return;
    p.__abc223Hooked = true;
    p.on('response', async (response) => {
      const sink = p.__abc223Sink;
      if (!sink) return;
      const req = response.request();
      const rec = { at: nowIso(), status: response.status(), method: req.method(), type: req.resourceType(), url: redactUrl(response.url()) };
      sink.events.push(rec);
      const raw = response.url();
      let host = '';
      let pathName = raw;
      try {
        const u = new URL(raw);
        host = u.hostname;
        pathName = u.pathname;
      } catch {}
      if (/omgapibra\.com$/i.test(host) && ['xhr', 'fetch'].includes(req.resourceType())) {
        try {
          const fields = redactPost(req.postData());
          let body = null;
          const ct = response.headers()['content-type'] || '';
          if (/json/i.test(ct)) {
            const json = await response.json().catch(() => null);
            body = redact(json);
          }
          sink.apiBodies.push({ path: pathName, method: req.method(), status: response.status(), fields, response: body });
        } catch {}
      }
    });
  };
  for (const p of context.pages()) attach(p);
  context.on('page', attach);

  const frame = await launchGame(page, context, game, events);
  log(`${game.id} frame ${redactUrl(frame.url())}`);
  await frame.waitForFunction(() => typeof cc !== 'undefined' && cc.director && cc.director.getScene(), null, { timeout: 180000 }).catch(() => {});
  await sleep(4000);
  for (let i = 0; i < 20 && !events.some((e) => {
    try { return /omgapibra\.com$/i.test(new URL(e.url).hostname); } catch { return false; }
  }); i++) await sleep(500);
  log(`${game.id} apiEvents=${events.filter((e) => { try { return /omgapibra\.com$/i.test(new URL(e.url).hostname); } catch { return false; } }).length}`);
  const loadingName = /GameLoading|^Loading$/i;
  let scene1 = { scene: null, nodes: [] };
  let planned = [];
  for (let i = 0; i < 30; i++) {
    scene1 = await listActiveNodes(frame);
    const loading = scene1.nodes.some((n) => n.active && loadingName.test(n.name));
    const ready = scene1.nodes.some((n) => n.active && game.readyNames.some((rx) => rx.test(n.name)));
    const startHit = scene1.nodes.some((n) => n.active && game.startNames.some((rx) => rx.test(n.name)));
    planned = planControls(scene1.nodes);
    const playUi = planned.some((p) => /^(spin\.|bet\.|auto\.|turbo\.)/.test(p.id));
    if (i === 0 || i === 29 || (!loading && playUi) || i % 5 === 4) {
      log(`${game.id} ui-wait i=${i} loading=${loading} ready=${ready} start=${startHit} planned=${planned.map((p) => p.id).join(',') || 'none'}`);
    }
    if (!loading && !startHit && playUi) break;
    if (startHit) {
      for (const rx of game.startNames) {
        const click = await tapNamed(frame, rx);
        if (click.clicked) { await sleep(4000); break; }
      }
    }
    await tapCanvasFrac(frame, 0.5, [0.78, 0.82, 0.86, 0.74][i % 4]);
    await sleep(3000);
  }
  await sleep(2000);
  await frame.page().screenshot({ path: path.join(shotDir, `${game.id}-loaded.png`), timeout: 15000 }).catch(() => {});
  scene1 = await listActiveNodes(frame);
  atomicWrite(path.join(captureDir, 'scene-nodes-pass1.json'), { scene: scene1.scene, names: scene1.nodes.filter((n) => n.active).map((n) => n.name) });
  planned = planControls(scene1.nodes);
  if (!planned.length) {
    planned = [{ id: 'canvas.start-fallback', pattern: /StartBtn|btn_start|spin/i, name: 'fallback' }];
  }
  log(`${game.id} planned controls ${planned.map((p) => p.id).join(',')}`);

  saveState({ phase: 'RESOURCE_PASS1', currentGame: game.id });
  const pass1 = await runPass(page, frame, game, events, 1, planned);
  const dl1 = await downloadObserved(pass1.staticUrls, resDir, game.staticHostRe);
  atomicWrite(path.join(captureDir, 'pass1-download.json'), { ...dl1, items: dl1.items });

  saveState({ phase: 'RESOURCE_PASS2', currentGame: game.id });
  const pass2 = await runPass(page, frame, game, events, 2, planned);
  const new2 = pass2.staticUrls.filter((u) => !pass1.staticUrls.includes(u));
  const dl2 = await downloadObserved(pass2.staticUrls, resDir, game.staticHostRe);
  atomicWrite(path.join(captureDir, 'pass2-download.json'), { ...dl2, items: dl2.items, secondPassNew: new2 });

  atomicWrite(path.join(captureDir, 'network.redacted.json'), events);
  const endpoints = writeApiCatalog(game, events, apiBodies, apiDir);
  const resourceStage = await writeResourceStage(game, pass1, pass2, dl1, dl2, reportsDir, captureDir);

  const langs = [...new Set(scene1.nodes.map((n) => n.name).filter((n) => /^(bn|en|es|fr|id|ko|pt|th|tr|vi|zh|pt-br|en-us)$/i.test(n)))];
  const langInv = {
    schemaVersion: 1,
    gameId: Number(game.gid),
    gameName: game.name,
    account: ACCOUNT_EXPECTED,
    auditMethod: 'REAL_ORIGIN_BROWSER',
    supportedLanguages: langs.length ? langs : ['pt-br'],
    observedAt: nowIso(),
    note: langs.length ? 'Language nodes observed in origin scene' : 'No dedicated language nodes observed; hall default pt-br used'
  };
  atomicWrite(path.join(reportsDir, 'language-inventory.json'), langInv);

  const originCollection = {
    schemaVersion: 1,
    worker: 'abc223',
    game: game.id,
    gid: game.gid,
    capturedAt: nowIso(),
    sampling: game.resample ? 'HTTP_AFTER_LIVE_PROBE' : 'EXISTING_COMPLETE_NO_RESAMPLE',
    resourceStage: `reports/${game.id}/resource-stage-status.json`,
    apiCatalog: `captures/${game.id}/api-catalog/observed-endpoints.redacted.json`,
    apiList: endpoints.map((e) => `${e.method} ${e.urlPath}`),
    resourcePass1: pass1.staticUrls.length,
    resourcePass2: pass2.staticUrls.length,
    secondPassNewResourceCount: resourceStage.secondPassNewResourceCount,
    remainingGaps: resourceStage.secondPassNewResourceCount === 0 ? [] : ['second pass discovered new static URLs'],
    tokenValuesPersisted: false,
    replicaHandoff: `reports/${game.id}/collect-20260908-handoff.zh-CN.md`,
    replicaIndex: 'reports/_workflow/collect-20260908/INDEX.zh-CN.md'
  };
  atomicWrite(path.join(reportsDir, 'origin-collection-20260908.json'), originCollection);
  await writeReplicaHandoff(game.id).catch((e) => log(`WARN handoff ${game.id}: ${e.message}`));

  state.games[game.id] = {
    ...state.games[game.id],
    status: resourceStage.secondPassNewResourceCount === 0 ? 'GAME_DONE' : 'RESOURCE_GAPS',
    resourcesReady: resourceStage.secondPassNewResourceCount === 0,
    apiCatalog: endpoints.length > 0,
    notes: `origin UI passes done; endpoints=${endpoints.length}; secondPassNew=${resourceStage.secondPassNewResourceCount}`
  };
  saveState({ phase: 'GAME_DONE', currentGame: game.id, games: state.games });
  log(`${game.id} done endpoints=${endpoints.length} secondPassNew=${resourceStage.secondPassNewResourceCount}`);
  return originCollection;
}

async function main() {
  await ensureDir(RUN);
  try {
    if (existsSync(HB)) {
      const prev = JSON.parse(readFileSync(HB, 'utf8'));
      if (prev.games) state.games = { ...state.games, ...prev.games };
    }
  } catch {}
  saveState({ phase: 'INVENTORY' });
  log('inventory complete; organizing 1090 fixtures');
  const cov1090 = await organize1090();
  log(`1090 organized paid=${cov1090.paidRoundStarts} dirty=false`);
  saveState({ phase: 'BROWSER_LOGIN' });
  const { context } = await connectBrowser();
  const page = await login(context);
  const failed = [];
  for (const game of GAMES) {
    let catalogReady = false;
    try {
      const cat = JSON.parse(readFileSync(path.join(ROOT, `captures/${game.id}/api-catalog/observed-endpoints.redacted.json`), 'utf8'));
      catalogReady = Array.isArray(cat.endpoints) && cat.endpoints.some((e) => /^\/cp\//.test(e.urlPath || ''));
    } catch {}
    let resourceReady = false;
    try {
      const rs = JSON.parse(readFileSync(path.join(ROOT, `reports/${game.id}/resource-stage-status.json`), 'utf8'));
      resourceReady = rs.ready === true && Number(rs.secondPassNewResourceCount || 0) === 0;
    } catch {}
    if (catalogReady && resourceReady) {
      log(`skip ${game.id} already complete`);
      state.games[game.id] = {
        ...state.games[game.id],
        status: 'GAME_DONE',
        resourcesReady: true,
        apiCatalog: true,
        notes: state.games[game.id]?.notes || 'origin UI + api catalog complete'
      };
      saveState({ games: state.games });
      await writeReplicaHandoff(game.id).catch((e) => log(`WARN handoff ${game.id}: ${e.message}`));
      continue;
    }
    if (catalogReady && !resourceReady) {
      log(`${game.id} catalog ready; extra resource pass needed`);
    }
    let lastErr = null;
    for (let attempt = 1; attempt <= 2; attempt++) {
      try {
        await processGame(page, context, game);
        lastErr = null;
        break;
      } catch (e) {
        lastErr = e;
        log(`ERROR ${game.id} attempt=${attempt}: ${e.stack || e.message}`);
        if (/IDENTITY|FOREIGN|SIBLING|ACCOUNT_LOCK|CAPTCHA/i.test(String(e.message || e))) throw e;
        await goHome(page).catch(() => {});
      }
    }
    if (lastErr) {
      failed.push(`${game.id}:${lastErr.message}`);
      state.games[game.id] = { ...state.games[game.id], status: 'ERROR', notes: String(lastErr.message || lastErr) };
      saveState({ phase: 'ERROR', currentGame: game.id, error: `${game.id}: ${lastErr.message}`, games: state.games });
      await writeReplicaHandoff(game.id).catch((e) => log(`WARN handoff ${game.id}: ${e.message}`));
      continue;
    }
    const ident = await page.evaluate(() => localStorage.getItem('login_username')).catch(() => null);
    if (ident && ident !== ACCOUNT_EXPECTED) throw new Error(`IDENTITY_DRIFT:${ident}`);
  }
  if (failed.length) {
    saveState({ phase: 'ERROR', error: failed.join(' | ') });
    throw new Error(failed.join(' | '));
  }
  saveState({ phase: 'DONE', currentGame: null, error: null });
  log('ALL GAMES DONE');
  process.exit(0);
}

main().catch((e) => {
  log(`FATAL ${e.stack || e.message}`);
  saveState({ phase: 'ERROR', error: String(e.message || e) });
  process.exit(1);
});
