import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const { chromium } = require('C:/Users/333/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright-core');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const browser = await chromium.connectOverCDP('http://127.0.0.1:19224');
const page = browser.contexts()[0].pages().find((p) => /hms-paddle/.test(p.url())) || browser.contexts()[0].pages()[0];

const info = await page.evaluate(() => ({
  url: location.href,
  iframes: [...document.querySelectorAll('iframe')].map((f) => ({ src: f.src, w: f.offsetWidth, h: f.offsetHeight, cls: f.className })),
  titleNodes: [...document.querySelectorAll('*')].filter((e) => (e.childElementCount === 0) && /^\s*Suporte\s*$/i.test(e.textContent || '')).slice(0, 8).map((e) => ({
    tag: e.tagName, cls: String(e.className).slice(0, 120), text: e.textContent.trim(),
    rect: e.getBoundingClientRect().toJSON(),
  })),
}));
console.log(JSON.stringify(info, null, 2));

await page.keyboard.press('Escape').catch(() => {});
await sleep(400);

for (const frame of page.frames()) {
  const text = await frame.locator('body').innerText().catch(() => '');
  if (!/Suporte|Notificações/i.test(text)) continue;
  console.log('frame', frame.url().slice(0, 120), 'len', text.length);
  const left = frame.locator('.van-nav-bar__left, .van-icon-arrow-left, .back');
  const n = await left.count().catch(() => 0);
  console.log('backCount', n);
  if (n) await left.first().click({ force: true }).catch((e) => console.log('clickFail', e.message));
  await sleep(500);
}

const titles = info.titleNodes;
if (titles[0]) {
  const r = titles[0].rect;
  await page.mouse.click(Math.max(8, r.x - 40), r.y + r.height / 2);
  await sleep(800);
}

await page.screenshot({ path: 'D:/work/hd/cpgame/screenshots/collect-20260908-abc224/close-support.png', timeout: 15000 }).catch(() => {});
const after = await page.evaluate(() => ({ url: location.href, text: (document.body.innerText || '').slice(0, 200) }));
console.log(JSON.stringify(after));
process.exit(0);
