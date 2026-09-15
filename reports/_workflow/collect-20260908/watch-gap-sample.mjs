import fs from 'node:fs';

const dir = 'D:/work/hd/cpgame/reports/_workflow/collect-20260908';
function read(n) {
  try { return JSON.parse(fs.readFileSync(`${dir}/${n}`, 'utf8')); } catch { return null; }
}
const a = read('gap-sample-abc223.json');
const b = read('gap-sample-abc224.json');
const stale = (hb) => {
  const t = Date.parse(hb?.updatedAt || 0);
  return !Number.isFinite(t) || Date.now() - t > 20 * 60 * 1000;
};
const gameList = (hb) => Object.values(hb?.games || {});
const progressing = (hb) => {
  if (!hb || stale(hb)) return false;
  if (hb.currentGame && !/^(DONE)$/i.test(hb.phase || '')) return true;
  if (hb.phase && /SAMPLE|STARTING|LAUNCH|SAMPLING/i.test(hb.phase)) return true;
  return false;
};
const allDone = (hb) => hb?.phase === 'DONE'
  && gameList(hb).length > 0
  && gameList(hb).every((g) => /SAMPLE_DONE|READY|SKIP/i.test(g?.status || ''));
const hasError = (hb) => {
  if (progressing(hb) || allDone(hb)) return false;
  return hb?.phase === 'ERROR'
    || Boolean(hb?.error)
    || gameList(hb).some((g) => /ERROR/i.test(g?.status || ''));
};

if (progressing(a) || progressing(b)) process.exit(0);
if (hasError(a) || hasError(b)) {
  const note = (hb) => hb?.error || gameList(hb).map((g) => `${g.status}:${g.notes || ''}`).join(',') || hb?.phase;
  process.stdout.write(`FAILED: gap-sample 223=${note(a)} 224=${note(b)}\n`);
  process.exit(1);
}
if (stale(a) && a && !allDone(a)) {
  process.stdout.write(`ACTION_REQUIRED: abc223 gap-sample stale ${a.phase}\n`);
  process.exit(0);
}
if (stale(b) && b && !allDone(b)) {
  process.stdout.write(`ACTION_REQUIRED: abc224 gap-sample stale ${b.phase}\n`);
  process.exit(0);
}
if (allDone(a) && allDone(b)) {
  process.stdout.write('DONE: gap-sample workers finished with SAMPLE_DONE\n');
  process.exit(0);
}
process.exit(0);
