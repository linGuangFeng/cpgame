import { createRequire } from 'node:module';
import fs from 'node:fs/promises';
const require = createRequire(import.meta.url);
const { chromium } = require('C:/Users/333/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright-core');
const out = 'D:/work/hd/cpgame/reports/_workflow/collect-20260908/lobby-probe.json';
const shot = 'D:/work/hd/cpgame/screenshots/collect-20260908-abc223';
const browser = await chromium.connectOverCDP('http://127.0.0.1:19223');
const context = browser.contexts()[0];
const page = context.pages().find(p => /hms-paddle/.test(p.url())) || context.pages()[0];
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function snap(name) {
  await page.screenshot({ path: `${shot}/${name}.png`, fullPage: false, timeout: 15000 }).catch(() => {});
}

const identity = await page.evaluate(() => ({
  username: localStorage.getItem('login_username'),
  url: location.href,
  hash: location.hash,
  text: (document.body?.innerText || '').slice(0, 4000)
}));
console.log('identity', identity.username, identity.url);

async function dismiss() {
  const clicks = [];
  const selectors = [
    '.popup_777_close', '.van-popup__close-icon', '.close', '.icon-close',
    '.notice-close', '.modal-close', '.dialog-close', '.pop-close'
  ];
  for (const sel of selectors) {
    const loc = page.locator(sel);
    const n = await loc.count().catch(() => 0);
    for (let i = 0; i < n; i++) {
      if (await loc.nth(i).isVisible().catch(() => false)) {
        await loc.nth(i).click({ force: true }).catch(() => {});
        clicks.push(sel);
        await sleep(400);
      }
    }
  }
  const texts = ['Pular', 'Fechar', '×', 'X'];
  for (const t of texts) {
    const loc = page.getByText(t, { exact: true });
    const n = await loc.count().catch(() => 0);
    for (let i = 0; i < Math.min(n, 8); i++) {
      if (await loc.nth(i).isVisible().catch(() => false)) {
        await loc.nth(i).click({ force: true }).catch(() => {});
        clicks.push(`text:${t}`);
        await sleep(400);
      }
    }
  }
  return clicks;
}

const clicks1 = await dismiss();
await snap('probe-after-dismiss-1');
if (!/#\/index/i.test(page.url())) {
  await page.evaluate(() => { location.hash = '#/index'; });
  await sleep(3000);
  await dismiss();
}
await snap('probe-home');

const surface = await page.evaluate(() => {
  const vis = (e) => {
    const r = e.getBoundingClientRect();
    const s = getComputedStyle(e);
    return r.width > 8 && r.height > 8 && s.display !== 'none' && s.visibility !== 'hidden' && s.opacity !== '0';
  };
  return {
    url: location.href,
    inputs: [...document.querySelectorAll('input')].filter(vis).map(e => ({ type: e.type, placeholder: e.placeholder, className: String(e.className).slice(0, 120) })),
    cpNodes: [...document.querySelectorAll('*')].filter(e => e.childElementCount === 0 && /^\s*CP\s*$/i.test((e.textContent || '').trim()) && vis(e)).slice(0, 20).map(e => ({ tag: e.tagName, className: String(e.className).slice(0, 200), text: e.textContent.trim(), rect: e.getBoundingClientRect().toJSON() })),
    searchish: [...document.querySelectorAll('input,button,a,i,div')].filter(e => vis(e) && /search|buscar|pesquis/i.test(`${e.placeholder || ''} ${e.className} ${e.getAttribute('aria-label') || ''} ${e.textContent || ''}`)).slice(0, 30).map(e => ({ tag: e.tagName, className: String(e.className).slice(0, 160), text: (e.textContent || '').trim().slice(0, 80), placeholder: e.placeholder })),
    titles: [...document.querySelectorAll('.game_title_left,.tabs,.provider,.game-item,.innerbox')].slice(0, 40).map(e => ({ tag: e.tagName, className: String(e.className).slice(0, 160), text: (e.innerText || '').trim().slice(0, 120) }))
  };
});

await fs.writeFile(out, JSON.stringify({ identity, clicks1, surface }, null, 2));
console.log(JSON.stringify({ username: identity.username, url: identity.url, clicks1, inputs: surface.inputs, cp: surface.cpNodes.length, searchish: surface.searchish.length, titles: surface.titles.length }, null, 2));
