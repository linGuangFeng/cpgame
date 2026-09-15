import { createRequire } from 'node:module';
import fs from 'node:fs/promises';
const require = createRequire(import.meta.url);
const { chromium } = require('C:/Users/333/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright-core');

const port = Number(process.argv[2] || 0);
const account = process.argv[3] || '';
const password = process.argv[4] || '';
if (!port || !account) {
  console.error('usage: node hall-fix.mjs <cdpPort> <account> [password]');
  process.exit(2);
}
const shotDir = `D:/work/hd/cpgame/screenshots/collect-20260908-${account}`;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const visible = async (locator) => {
  const n = await locator.count().catch(() => 0);
  for (let i = 0; i < n; i++) if (await locator.nth(i).isVisible().catch(() => false)) return locator.nth(i);
  return null;
};

const browser = await chromium.connectOverCDP(`http://127.0.0.1:${port}`);
const context = browser.contexts()[0];
if (!context) throw new Error('NO_CONTEXT');
const page = context.pages().find((p) => /hms-paddle/.test(p.url())) || context.pages()[0];
await fs.mkdir(shotDir, { recursive: true });
const snap = async (name) => {
  const file = `${shotDir}/fix-${name}.png`;
  await page.screenshot({ path: file, timeout: 15000 }).catch(() => {});
  return file;
};

async function dismiss() {
  const skip = await visible(page.getByText(/^(Pular|Fechar)$/i, { exact: true }));
  if (skip) await skip.click({ force: true }).catch(() => {});
  const promo = await visible(page.locator('.popup_777_close'));
  if (promo) await promo.click({ force: true }).catch(() => {});
}

await snap('before');
const ident0 = await page.evaluate(() => localStorage.getItem('login_username'));
console.log(JSON.stringify({ port, account, url: page.url(), ident0 }));

for (let i = 0; i < 6; i++) {
  const text = await page.locator('body').innerText().catch(() => '');
  if (!/Suporte online|Notificações|Painel de rolagem|Cupons|Introduza o código/i.test(text)) break;
  const back = await visible(page.locator('.van-nav-bar__left, .van-icon-arrow-left, .van-nav-bar__arrow'))
    || await visible(page.locator('.van-nav-bar').locator('i,svg,button').first());
  if (back) await back.click({ force: true }).catch(() => {});
  else await page.evaluate(() => { location.hash = '#/index'; }).catch(() => {});
  await sleep(1200);
  await snap(`leave-overlay-${i}`);
}

await page.evaluate(() => { location.hash = '#/index'; });
await sleep(1500);
await dismiss();
const inicio = await visible(page.locator('.footer_item').filter({ hasText: /Início|Inicio/i }));
if (inicio) { await inicio.click({ force: true }); await sleep(1500); }
await dismiss();
await snap('home');

let ident = await page.evaluate(() => localStorage.getItem('login_username'));
if (ident && ident !== account) {
  console.log(JSON.stringify({ error: `FOREIGN_SESSION:${ident}` }));
  process.exit(3);
}

if (ident !== account) {
  const blocked = /Suporte online|Cupons|Introduza o código/i.test(await page.locator('body').innerText().catch(() => ''));
  if (blocked) throw new Error('OVERLAY_STILL_OPEN');
  const headerEntrar = page.locator('.topRight .login-btn, .login-btn, button:has-text("Entrar")').first();
  const entrar = await visible(page.getByRole('button', { name: /^Entrar$/i }))
    || await visible(headerEntrar)
    || await visible(page.getByText(/^Entrar$/i, { exact: true }));
  if (!entrar) {
    await snap('no-entrar');
    throw new Error('ENTRAR_NOT_FOUND');
  }
  const box = await entrar.boundingBox();
  console.log(JSON.stringify({ entrarBox: box }));
  await entrar.click({ force: true });
  await sleep(1500);
  const pass = page.locator('input[type="password"]').first();
  await pass.waitFor({ state: 'visible', timeout: 15000 });
  const user = page.locator('input.inp:not([type="password"]), input[placeholder="Digite a conta"]').first();
  await user.waitFor({ state: 'visible', timeout: 10000 });
  await user.fill(account);
  await pass.fill(password);
  const submit = await visible(page.locator('.lg-btn'));
  if (!submit) throw new Error('LOGIN_SUBMIT_NOT_FOUND');
  await submit.click({ force: true });
  await sleep(10000);
  await page.evaluate(() => { location.hash = '#/index'; });
  await sleep(2500);
  const inicio = await visible(page.locator('.footer_item').filter({ hasText: /Início|Inicio/i }));
  if (inicio) await inicio.click({ force: true }).catch(() => {});
  await sleep(1500);
  ident = await page.evaluate(() => localStorage.getItem('login_username'));
  await snap('after-login');
  console.log(JSON.stringify({ identAfter: ident }));
  if (ident !== account) throw new Error(`LOGIN_IDENTITY:${ident || 'none'}`);
}

await dismiss();
const cp = await visible(page.locator('.tabs').filter({ hasText: /^\s*CP\s*$/i }))
  || await visible(page.locator('.tab_text').filter({ hasText: /^\s*CP\s*$/i }));
if (!cp) {
  await snap('cp-missing');
  throw new Error('CP_TAB_NOT_FOUND');
}
await cp.click({ force: true });
await sleep(4000);
await snap('cp-tab');
const more = await visible(page.locator('#game3 .game_title_right .all'))
  || await visible(page.getByText(/^(Todos|Mais|Ver todos)/i));
if (more) { await more.click({ force: true }); await sleep(3000); await snap('cp-all'); }

const catalog = await page.evaluate(() => {
  const vis = (e) => {
    const r = e.getBoundingClientRect();
    const s = getComputedStyle(e);
    return r.width > 20 && r.height > 20 && s.display !== 'none';
  };
  const imgs = [...document.querySelectorAll('img,[data-src]')].filter(vis).map((e) => ({
    src: e.getAttribute('src') || '',
    dataSrc: e.getAttribute('data-src') || '',
    alt: e.getAttribute('alt') || '',
    text: (e.parentElement?.innerText || '').trim().slice(0, 80),
  })).filter((e) => /cp\/|500\d+|game\//i.test(`${e.src} ${e.dataSrc}`)).slice(0, 80);
  const names = [...document.querySelectorAll('.innerbox,.game-item,.game_name,p,span,div')]
    .filter((e) => vis(e) && e.childElementCount <= 2)
    .map((e) => (e.textContent || '').trim())
    .filter((t) => t && t.length < 40 && /jungle|cyber|sharp|rio|piggy|monster|carnival|fruit|shooter|slayer/i.test(t))
    .slice(0, 40);
  return { url: location.href, imgs, names };
});
await fs.writeFile(`D:/work/hd/cpgame/reports/_workflow/collect-20260908/hall-fix-${account}.json`, `${JSON.stringify(catalog, null, 2)}\n`);
console.log(JSON.stringify({ ok: true, ident, url: page.url(), imgCount: catalog.imgs.length, names: catalog.names }));
process.exit(0);
