import fs from 'node:fs';
import path from 'node:path';

const ROOT = 'D:/work/hd/cpgame';
const EXCLUDE = new Set([
  '1407-mac-Coin-Master-GO',
  '1407-Coin-Master-GO',
  '1809-mac-Freedom-Day',
  '1809-Freedom-Day',
]);

const HISTORY_RE = /\/(?:cp\/)?(?:goldgame|Goldgame|goldGame)\/[A-Za-z0-9_./-]*(?:history|History)[A-Za-z0-9_./-]*/g;
const LOG_RE = /\/(?:cp\/)?(?:api\/v1\/)?[A-Za-z0-9_-]+\/(?:log-list|log-view|log_list|log_view)/g;
const ORDER_RE = /\/(?:cp\/)?order\/(?:log_list|log_view)/g;
const GET_USER_RE = /\/(?:cp\/)?[A-Za-z0-9_./-]*getUserHistory[A-Za-z0-9_./-]*/g;
const JAVA_ROUTE_RE = /["'`](\/(?:cp\/)?[A-Za-z0-9_./-]*(?:history|History|log-list|log-view|log_list|log_view)[A-Za-z0-9_./-]*)["'`]/g;

function walk(dir, pred, acc = []) {
  if (!fs.existsSync(dir)) return acc;
  const st = fs.statSync(dir);
  if (!st.isDirectory()) {
    if (pred(dir)) acc.push(dir);
    return acc;
  }
  let entries = [];
  try { entries = fs.readdirSync(dir, { withFileTypes: true }); } catch { return acc; }
  for (const e of entries) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) {
      if (e.name === 'node_modules' || e.name === 'target' || e.name === '.git') continue;
      walk(p, pred, acc);
    } else if (pred(p)) acc.push(p);
  }
  return acc;
}

function readText(p, max = 8_000_000) {
  try {
    const st = fs.statSync(p);
    if (st.size > max) return fs.readFileSync(p, { encoding: 'utf8', flag: 'r' }).slice(0, max);
    return fs.readFileSync(p, 'utf8');
  } catch {
    return '';
  }
}

function unique(arr) {
  return [...new Set(arr.filter(Boolean))];
}

function scanJsEndpoints(dir) {
  const files = walk(path.join(ROOT, 'publish', dir), (p) => /\.(js|json)$/i.test(p));
  const found = new Set();
  for (const f of files) {
    const t = readText(f);
    for (const re of [HISTORY_RE, LOG_RE, ORDER_RE, GET_USER_RE]) {
      const m = t.match(re);
      if (m) m.forEach((x) => found.add(x));
    }
  }
  return [...found].sort();
}

function scanJavaRoutes(dir) {
  const files = walk(path.join(ROOT, 'server-api', dir, 'src'), (p) => p.endsWith('.java'));
  const found = new Set();
  let hasExtend = false;
  let hasResults = false;
  let hasRes = false;
  for (const f of files) {
    const t = readText(f);
    if (/\bextend\b/.test(t) && /history/i.test(t)) hasExtend = true;
    if (/\bresults\b/.test(t) && /history/i.test(t)) hasResults = true;
    if (/\.put\("res"|\.set\("res"|put\("res"/.test(t) && /history/i.test(t)) hasRes = true;
    let m;
    const re = new RegExp(JAVA_ROUTE_RE.source, 'g');
    while ((m = re.exec(t))) found.add(m[1]);
    const mapping = t.matchAll(/@(?:Post|Request)Mapping\([^)]*(?:history|log-list|log-view)[^)]*\)/gi);
    for (const hit of mapping) found.add(hit[0].replace(/\s+/g, ' ').slice(0, 180));
  }
  return { routes: [...found].sort(), hasExtend, hasResults, hasRes, javaFiles: files.length };
}

function countHistoryAssets(dir) {
  const pub = path.join(ROOT, 'publish', dir);
  const files = walk(pub, (p) => /hist|record|Record|bet_record|DayHistory|His/i.test(path.basename(p)));
  const langs = [];
  for (const candidate of ['i18n', 'language', 'languages', 'lang', 'locale', 'assets/resources/i18n']) {
    const p = path.join(pub, candidate);
    if (fs.existsSync(p) && fs.statSync(p).isDirectory()) {
      langs.push({ root: candidate, children: fs.readdirSync(p).slice(0, 40) });
    }
  }
  return { count: files.length, samples: files.slice(0, 25).map((f) => path.relative(pub, f).replaceAll('\\', '/')), langs };
}

function fixtures(dir) {
  const base = path.join(ROOT, 'fixtures', dir);
  if (!fs.existsSync(base)) return [];
  return walk(base, (p) => /hist/i.test(path.basename(p)) || /hist/i.test(p)).map((f) => path.relative(base, f).replaceAll('\\', '/')).slice(0, 20);
}

const reports = fs.readdirSync(path.join(ROOT, 'reports'), { withFileTypes: true })
  .filter((e) => e.isDirectory())
  .map((e) => e.name);

const passed = [];
for (const dir of reports) {
  const meta = path.join(ROOT, 'reports', dir, 'lab-metadata.json');
  if (!fs.existsSync(meta)) continue;
  try {
    const j = JSON.parse(fs.readFileSync(meta, 'utf8'));
    if (j.manualAcceptanceStatus === 'PASSED' && !EXCLUDE.has(dir)) passed.push(dir);
  } catch {}
}
passed.sort();

const out = [];
for (const dir of passed) {
  const frontend = scanJsEndpoints(dir);
  const java = scanJavaRoutes(dir);
  const assets = countHistoryAssets(dir);
  const fx = fixtures(dir);
  out.push({
    dir,
    frontendHistoryUrls: frontend,
    javaHistoryRoutes: java.routes,
    javaHasExtend: java.hasExtend,
    javaHasResults: java.hasResults,
    javaHasRes: java.hasRes,
    javaFiles: java.javaFiles,
    historyAssetCount: assets.count,
    historyAssetSamples: assets.samples,
    languageRoots: assets.langs,
    fixtures: fx,
  });
}

const dest = path.join(ROOT, 'reports/_workflow/history-audit-passed-20260909.json');
fs.mkdirSync(path.dirname(dest), { recursive: true });
fs.writeFileSync(dest, JSON.stringify({ at: new Date().toISOString(), games: out }, null, 2));
console.log(JSON.stringify({ count: out.length, dirs: passed, dest }, null, 2));
for (const g of out) {
  console.log('\n==', g.dir, '==');
  console.log(' frontend', g.frontendHistoryUrls.join(' | ') || '(none)');
  console.log(' java    ', g.javaHistoryRoutes.join(' | ') || '(none)');
  console.log(' flags   ', JSON.stringify({ extend: g.javaHasExtend, results: g.javaHasResults, res: g.javaHasRes, assets: g.historyAssetCount, fixtures: g.fixtures.length }));
}
