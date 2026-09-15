import { createRequire } from 'node:module';
import fs from 'node:fs/promises';

const require = createRequire(import.meta.url);
const { chromium } = require('C:/Users/333/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright-core');

const out = 'D:/work/hd/cpgame/reports/_workflow/collect-20260908/probe-both-lobbies.json';

function vis(e) {
  const r = e.getBoundingClientRect();
  const s = getComputedStyle(e);
  return r.width > 4 && r.height > 4 && s.display !== 'none' && s.visibility !== 'hidden' && Number(s.opacity) !== 0;
}

async function probe(port, expected) {
  const browser = await chromium.connectOverCDP(`http://127.0.0.1:${port}`);
  const context = browser.contexts()[0];
  const page = context.pages().find((p) => /hms-paddle/.test(p.url())) || context.pages()[0];
  const identity = await page.evaluate(() => ({
    username: localStorage.getItem('login_username'),
    url: location.href.split('?')[0],
    hash: location.hash,
    title: document.title,
    textHead: (document.body?.innerText || '').replace(/\s+/g, ' ').slice(0, 1500),
  }));
  const surface = await page.evaluate(() => {
    const vis = (e) => {
      const r = e.getBoundingClientRect();
      const s = getComputedStyle(e);
      return r.width > 4 && r.height > 4 && s.display !== 'none' && s.visibility !== 'hidden' && Number(s.opacity) !== 0;
    };
    const buttons = [...document.querySelectorAll('button,div,span,a')].filter((e) => vis(e) && /^(Entrar|Registrar|Login|Pessoal|CP|PG|Início|Promoção|Pular|Fechar)$/i.test((e.innerText || '').trim())).slice(0, 40).map((e) => ({
      tag: e.tagName,
      className: String(e.className).slice(0, 180),
      text: (e.innerText || '').trim().slice(0, 40),
      rect: { x: Math.round(e.getBoundingClientRect().x), y: Math.round(e.getBoundingClientRect().y), w: Math.round(e.getBoundingClientRect().width), h: Math.round(e.getBoundingClientRect().height) },
    }));
    const inputs = [...document.querySelectorAll('input')].filter(vis).map((e) => ({
      type: e.type,
      placeholder: e.placeholder,
      className: String(e.className).slice(0, 120),
    }));
    const overlays = [...document.querySelectorAll('.popup_777,.van-popup,.van-overlay,.el-dialog,img,[class*=popup],[class*=modal]')].filter(vis).slice(0, 20).map((e) => ({
      tag: e.tagName,
      className: String(e.className).slice(0, 180),
      src: (e.getAttribute('src') || e.getAttribute('data-src') || '').slice(0, 120),
    }));
    const covers = [...document.querySelectorAll('img,[data-src],div')].map((e) => e.getAttribute('data-src') || e.getAttribute('src') || '').filter((s) => /50016|50052|51090|50045|50056|52300/.test(s));
    const gameTitles = [...document.querySelectorAll('.game_title,.game_title_left,.game_title_right,.tabs')].slice(0, 30).map((e) => ({
      className: String(e.className).slice(0, 160),
      text: (e.innerText || '').replace(/\s+/g, ' ').trim().slice(0, 80),
    }));
    return { buttons, inputs, overlays, covers, gameTitles };
  });
  await page.screenshot({ path: `D:/work/hd/cpgame/screenshots/collect-20260908-${expected}/probe-${port}.png`, timeout: 15000 }).catch(() => {});
  await browser.close().catch(() => {});
  return { port, expected, identity, surface };
}

const abc223 = await probe(19223, 'abc223');
const abc224 = await probe(19224, 'abc224');
const report = {
  capturedAt: new Date().toISOString(),
  abc223: {
    username: abc223.identity.username,
    url: abc223.identity.url,
    hash: abc223.identity.hash,
    match: abc223.identity.username === 'abc223',
    textHead: abc223.identity.textHead,
    buttons: abc223.surface.buttons,
    inputs: abc223.surface.inputs,
    overlays: abc223.surface.overlays,
    covers: abc223.surface.covers,
    gameTitles: abc223.surface.gameTitles,
  },
  abc224: {
    username: abc224.identity.username,
    url: abc224.identity.url,
    hash: abc224.identity.hash,
    match: abc224.identity.username === 'abc224',
    textHead: abc224.identity.textHead,
    buttons: abc224.surface.buttons,
    inputs: abc224.surface.inputs,
    overlays: abc224.surface.overlays,
    covers: abc224.surface.covers,
    gameTitles: abc224.surface.gameTitles,
  },
};
await fs.writeFile(out, `${JSON.stringify(report, null, 2)}\n`);
console.log(JSON.stringify({
  abc223: { user: report.abc223.username, url: report.abc223.url, match: report.abc223.match, buttons: report.abc223.buttons.map((b) => b.text), covers: report.abc223.covers.length, inputs: report.abc223.inputs },
  abc224: { user: report.abc224.username, url: report.abc224.url, match: report.abc224.match, buttons: report.abc224.buttons.map((b) => b.text), covers: report.abc224.covers.length, inputs: report.abc224.inputs },
}, null, 2));
