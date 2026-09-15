import { createRequire } from 'node:module';
import fs from 'node:fs/promises';
const require = createRequire(import.meta.url);
const { chromium } = require('C:/Users/333/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright-core');

const games = [
  { id: '16', url: process.argv[2] },
  { id: '45', url: process.argv[3] },
  { id: '56', url: process.argv[4] },
];
const outDir = 'D:/work/hd/cpgame/reports/_workflow/collect-20260908';
await fs.mkdir(outDir, { recursive: true });
const browser = await chromium.launch({ headless: true, channel: 'chrome' });
const results = [];
for (const game of games) {
  if (!game.url) continue;
  const page = await browser.newPage({ viewport: { width: 1280, height: 720 } });
  const apis = [];
  page.on('response', async (response) => {
    const url = response.url();
    if (!/\/cp\/|gameResult|auth\/verify|\/config|\/spin/i.test(url)) return;
    apis.push({ status: response.status(), path: url.replace(/^https?:\/\/[^/]+/, '').slice(0, 120) });
  });
  const rec = { id: game.id, url: game.url, error: null, canvas: false, apis };
  try {
    await page.goto(game.url, { waitUntil: 'domcontentloaded', timeout: 60000 });
    await page.waitForTimeout(8000);
    const canvas = page.locator('#GameCanvas, canvas').first();
    rec.canvas = await canvas.count() > 0;
    const box = rec.canvas ? await canvas.boundingBox().catch(() => null) : null;
    if (box) {
      for (const ny of [0.82, 0.72, 0.5, 0.88]) {
        await canvas.click({ force: true, position: { x: box.width * 0.5, y: box.height * ny } }).catch(() => {});
        await page.waitForTimeout(1500);
      }
    }
    rec.title = await page.title();
    rec.bodyHint = (await page.locator('body').innerText().catch(() => '')).slice(0, 200);
    await page.screenshot({ path: `${outDir}/play-${game.id}.png`, fullPage: false });
    rec.screenshot = `play-${game.id}.png`;
  } catch (error) {
    rec.error = error.message;
  }
  results.push(rec);
  await page.close().catch(() => {});
}
await browser.close();
await fs.writeFile(`${outDir}/play-browser-164556.json`, `${JSON.stringify(results, null, 2)}\n`);
console.log(JSON.stringify(results, null, 2));
