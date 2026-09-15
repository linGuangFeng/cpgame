import fs from 'node:fs';
import path from 'node:path';

const dir = process.argv[2];
function walk(d, acc = []) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    if (e.isDirectory()) walk(p, acc);
    else if (p.endsWith('.json')) acc.push(p);
  }
  return acc;
}
const files = walk(dir);
const langs = {};
for (const f of files) {
  const t = fs.readFileSync(f, 'utf8');
  const m = t.match(/\[0,"([a-z]{2}(?:-[a-z0-9]+)?)",\{/i);
  if (!m) continue;
  const lang = m[1].toLowerCase();
  const hist = t.match(/"History_[^"]+"/g) || [];
  const rec = t.match(/"Record_[^"]+"/g) || [];
  langs[lang] = langs[lang] || { files: [], historyKeys: 0, recordKeys: 0 };
  langs[lang].files.push(path.relative(dir, f).replaceAll('\\', '/'));
  langs[lang].historyKeys += hist.length;
  langs[lang].recordKeys += rec.length;
}
console.log(JSON.stringify(langs, null, 2));
