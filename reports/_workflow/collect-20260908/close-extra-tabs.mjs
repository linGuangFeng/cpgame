const port = Number(process.argv[2] || 19223);
const list = await fetch(`http://127.0.0.1:${port}/json/list`).then((r) => r.json());
let closed = 0;
let kept = 0;
for (const t of list) {
  if (t.type !== 'page') continue;
  const url = String(t.url || '');
  const keep = /hms-paddle\.com/i.test(url);
  if (keep) {
    kept += 1;
    continue;
  }
  try {
    await fetch(`http://127.0.0.1:${port}/json/close/${t.id}`);
    closed += 1;
  } catch {}
}
console.log(JSON.stringify({ port, keptHallPages: kept, closedExtraPages: closed }));
