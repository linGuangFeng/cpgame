import { createRequire } from 'node:module';
const require = createRequire(import.meta.url);
const { chromium } = require('C:/Users/333/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright-core');

async function inspect(port) {
  const browser = await chromium.connectOverCDP(`http://127.0.0.1:${port}`);
  const context = browser.contexts()[0];
  const pages = context.pages();
  const out = [];
  for (const page of pages) {
    const info = await page.evaluate(() => ({
      url: location.href.split('?')[0],
      user: localStorage.getItem('login_username'),
      title: document.title,
      text: (document.body?.innerText || '').replace(/\s+/g, ' ').slice(0, 400),
      frames: [...document.querySelectorAll('iframe')].map((f) => (f.src || '').split('?')[0]).filter(Boolean),
      visibleInputs: [...document.querySelectorAll('input')].filter((e) => {
        const r = e.getBoundingClientRect();
        const s = getComputedStyle(e);
        return r.width > 4 && r.height > 4 && s.display !== 'none' && s.visibility !== 'hidden' && Number(s.opacity) !== 0;
      }).map((e) => ({ type: e.type, placeholder: e.placeholder, className: String(e.className).slice(0, 80) })),
      loginBtn: !!document.querySelector('.login-btn'),
    })).catch((e) => ({ error: e.message, url: page.url() }));
    const frameUrls = page.frames().map((f) => f.url().split('?')[0]);
    out.push({ ...info, playwrightFrames: frameUrls });
  }
  await browser.close().catch(() => {});
  return out;
}

const abc223 = await inspect(19223);
const abc224 = await inspect(19224);
console.log(JSON.stringify({ abc223, abc224 }, null, 2));
