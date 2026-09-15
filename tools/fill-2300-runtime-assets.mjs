import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import path from 'node:path';

const reportPath = process.argv[2];
if (!reportPath) throw new Error('usage: node tools/fill-2300-runtime-assets.mjs <browser-report.json>');
const report = JSON.parse(await readFile(reportPath, 'utf8'));
const root = 'D:/work/hd/cpgame/publish/2300-monster-slayer';
const allow = ['/assets/', '/src/', '/cocos-js/'];
const requested = [...new Set((report.notFound ?? []).map(value => new URL(value).pathname))]
  .filter(value => allow.some(prefix => value.startsWith(prefix)));
const failed = [];
let downloaded = 0;
let reused = 0;
for (const pathname of requested) {
  const destination = path.join(root, pathname.replace(/^\//, ''));
  if (existsSync(destination)) {
    reused++;
    continue;
  }
  try {
    const response = await fetch(`https://static.cpgame.io/v2/2300${pathname}`);
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    await mkdir(path.dirname(destination), { recursive: true });
    await writeFile(destination, Buffer.from(await response.arrayBuffer()));
    downloaded++;
  } catch (error) {
    failed.push({ pathname, error: String(error.message ?? error) });
  }
}
process.stdout.write(JSON.stringify({ requested: requested.length, downloaded, reused, failedCount: failed.length, failed }, null, 2));
