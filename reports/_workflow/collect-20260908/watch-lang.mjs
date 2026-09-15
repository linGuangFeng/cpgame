import fs from 'node:fs';

const dir = 'D:/work/hd/cpgame/reports/_workflow/collect-20260908';
function read(name) {
  try { return JSON.parse(fs.readFileSync(`${dir}/${name}`, 'utf8')); } catch { return null; }
}
const a = read('lang-abc223.json');
const b = read('lang-abc224.json');
const stale = (hb) => {
  const t = Date.parse(hb?.updatedAt || 0);
  return !Number.isFinite(t) || Date.now() - t > 15 * 60 * 1000;
};
function progressing(hb) {
  return hb && hb.phase && !/^(DONE|ERROR)$/i.test(hb.phase) && !stale(hb);
}
if (progressing(a) || progressing(b)) process.exit(0);
if (a?.phase === 'ERROR' || b?.phase === 'ERROR') {
  process.stdout.write(`ACTION_REQUIRED: lang ERROR 223=${a?.phase}:${a?.error || ''} 224=${b?.phase}:${b?.error || ''}\n`);
  process.exit(0);
}
if (stale(a) && a && a.phase !== 'DONE') {
  process.stdout.write(`ACTION_REQUIRED: abc223 lang heartbeat stale phase=${a.phase}\n`);
  process.exit(0);
}
if (stale(b) && b && b.phase !== 'DONE') {
  process.stdout.write(`ACTION_REQUIRED: abc224 lang heartbeat stale phase=${b.phase}\n`);
  process.exit(0);
}
if (a?.phase === 'DONE' && b?.phase === 'DONE') {
  process.stdout.write('DONE: language first-load workers finished\n');
  process.exit(0);
}
process.exit(0);
