import fs from 'node:fs';

const dir = 'D:/work/hd/cpgame/reports/_workflow/collect-20260908';
const a = JSON.parse(fs.readFileSync(`${dir}/worker-abc223.json`, 'utf8'));
const b = JSON.parse(fs.readFileSync(`${dir}/worker-abc224.json`, 'utf8'));
function catalogHasCp(id) {
  try {
    const j = JSON.parse(fs.readFileSync(`D:/work/hd/cpgame/captures/${id}/api-catalog/observed-endpoints.redacted.json`, 'utf8'));
    return (j.endpoints || []).some((e) => /^\/cp\//.test(e.urlPath || e.path || ''));
  } catch {
    return false;
  }
}
const gaps = [];
if (!catalogHasCp('52-Cyber-GO')) gaps.push('52-apiCatalog');
if (!catalogHasCp('1090-Sharpshooter')) gaps.push('1090-apiCatalog');
if (!catalogHasCp('16-Jungle-Fruit')) gaps.push('16-apiCatalog');
function buyBucketsFilled() {
  const prefixes = ['000002300', '100002300', '200002300'];
  try {
    return prefixes.every((p) => {
      const text = fs.readFileSync(`D:/work/hd/cpgame/captures/2300-Monster-Slayer/buy-modes/${p}/spin-index.jsonl`, 'utf8');
      return text.trim().split('\n').filter(Boolean).length >= 20;
    });
  } catch {
    return false;
  }
}
if (b.games?.['2300-Monster-Slayer']?.status === 'ERROR' || !buyBucketsFilled()) {
  gaps.push('2300-buy');
}
if (b.games?.['45-Rio-Carnival']?.status === 'ERROR') gaps.push('45');
if (b.games?.['56-Crazy-Piggy']?.status === 'ERROR') gaps.push('56');
const stale = (hb) => {
  const t = Date.parse(hb.updatedAt || 0);
  return !Number.isFinite(t) || (Date.now() - t > 12 * 60 * 1000);
};
function progressing(hb) {
  return hb && hb.phase && !/^(DONE|ERROR|GAME_DONE)$/i.test(hb.phase) && !stale(hb);
}
if (progressing(a) || progressing(b)) process.exit(0);
if (a.phase === 'ERROR' || b.phase === 'ERROR') {
  process.stdout.write(`ACTION_REQUIRED: phase ERROR 223=${a.phase}:${a.error || ''} 224=${b.phase}:${b.error || ''}\n`);
  process.exit(0);
}
if (stale(a) && a.phase !== 'DONE') {
  process.stdout.write(`ACTION_REQUIRED: abc223 heartbeat stale phase=${a.phase}\n`);
  process.exit(0);
}
if (stale(b) && b.phase !== 'DONE') {
  process.stdout.write(`ACTION_REQUIRED: abc224 heartbeat stale phase=${b.phase}\n`);
  process.exit(0);
}
const bothDone = a.phase === 'DONE' && (b.phase === 'DONE' || b.phase === 'GAME_DONE');
if (bothDone && gaps.length) {
  process.stdout.write(`ACTION_REQUIRED: workers stopped with gaps ${gaps.join(',')}\n`);
  process.exit(0);
}
if (bothDone && !gaps.length) {
  process.stdout.write('DONE: collect-20260908 no remaining collection gaps\n');
  process.exit(0);
}
process.exit(0);
