import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const { chromium } = require('C:/Users/333/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright-core');

const urls = process.argv.slice(2);
const browser = await chromium.launch({ headless: true, channel: 'chrome' });
const out = [];
for (const url of urls) {
  const page = await browser.newPage({ viewport: { width: 900, height: 1600 } });
  const spins = [];
  page.on('response', async (response) => {
    const u = response.url();
    if (!/\/spin(\?|$)/i.test(u) && !/gameResult/i.test(u)) return;
    let body = '';
    try { body = (await response.text()).slice(0, 240); } catch {}
    spins.push({ status: response.status(), path: u.replace(/^https?:\/\/[^/]+/, '').slice(0, 80), body });
  });
  await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await page.waitForTimeout(7000);
  const canvas = page.locator('#GameCanvas, canvas').first();
  const box = await canvas.boundingBox();
  if (box) {
    await canvas.click({ force: true, position: { x: box.width * 0.5, y: box.height * 0.86 } });
    await page.waitForTimeout(4000);
    await canvas.click({ force: true, position: { x: box.width * 0.5, y: box.height * 0.82 } });
    await page.waitForTimeout(4000);
  }
  const id = (url.match(/play\/([^/]+)/) || [])[1];
  out.push({ id, spinCount: spins.length, spins });
  await page.close();
}
await browser.close();
console.log(JSON.stringify(out, null, 2));
