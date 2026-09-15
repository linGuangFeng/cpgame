import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const { chromium } = require('C:/Users/333/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright-core');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const browser = await chromium.connectOverCDP('http://127.0.0.1:19224');
const page = browser.contexts()[0].pages().find((p) => /hms-paddle/.test(p.url())) || browser.contexts()[0].pages()[0];
const shot = async (name) => {
  await page.screenshot({ path: `D:/work/hd/cpgame/screenshots/collect-20260908-abc224/${name}.png`, timeout: 15000 }).catch(() => {});
};

let ident = await page.evaluate(() => localStorage.getItem('login_username'));
if (ident === 'abc223') throw new Error('FOREIGN_SESSION:abc223');
if (ident === 'abc224') {
  console.log(JSON.stringify({ ok: true, already: true }));
  process.exit(0);
}

const modalUser = page.locator('.van-popup input.inp:not([type="password"]), .van-popup input[placeholder="Digite a conta"]').first();
const modalPass = page.locator('.van-popup input[type="password"]').first();
if (await modalUser.isVisible().catch(() => false)) {
  await modalUser.fill('abc224');
  await modalPass.fill('112233');
}
const submit = page.locator('.van-popup .lg-btn, .lg-btn').first();
await submit.waitFor({ state: 'visible', timeout: 8000 });
const loginWait = page.waitForResponse((r) => /\/api\/user\/login/i.test(r.url()), { timeout: 20000 }).catch(() => null);
await submit.click({ force: true });
const resp = await loginWait;
let loginMeta = null;
if (resp) {
  const body = await resp.json().catch(() => null);
  loginMeta = { http: resp.status(), code: body?.code, msg: body?.msg, user: body?.data?.username || body?.data?.userinfo?.username || null };
}
await sleep(8000);
ident = await page.evaluate(() => localStorage.getItem('login_username'));
await shot('login-done');
console.log(JSON.stringify({ ident, loginMeta, url: page.url() }));
if (ident !== 'abc224') throw new Error(`LOGIN_IDENTITY:${ident || 'none'}:${loginMeta && JSON.stringify(loginMeta)}`);
process.exit(0);
