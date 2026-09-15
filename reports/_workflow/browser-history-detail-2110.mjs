import fs from "node:fs";
import path from "node:path";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const { chromium } = require(
  "C:/Users/333/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright-core"
);

const PORT = process.env.GAME_PORT || "50047";
const TOKEN = process.env.GAME_TOKEN;
if (!TOKEN) {
  console.error("GAME_TOKEN required");
  process.exit(2);
}
const BASE = `http://127.0.0.1:${PORT}`;
const URL =
  `${BASE}/fixed/?ai=luck_single_10229&gid=2110&l=pt&language=pt-br` +
  `&token=${encodeURIComponent(TOKEN)}&sip=127.0.0.1%3A${PORT}`;
const OUT = "D:/work/hd/cpgame/screenshots/2110-Bee-Workshop/history-fix-20260909";
const REPORT = "D:/work/hd/cpgame/reports/2110-Bee-Workshop/history-browser-verify-20260909.json";
fs.mkdirSync(OUT, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function shot(page, name) {
  const file = path.join(OUT, name + ".png");
  await page.screenshot({ path: file, timeout: 15000 });
  return file;
}

const EVAL_CLICK = `(name) => {
  const canvas = cc.find("Canvas");
  const hits = [];
  if (canvas && cc.Button) {
    for (const b of canvas.getComponentsInChildren(cc.Button)) {
      const n = (b.node && b.node.name) || "";
      const active = !!(b.node && b.node.activeInHierarchy);
      if (n === name) {
        hits.push({ n, active });
        if (active) {
          b.node.emit("click");
          b.node.emit(cc.Node.EventType.TOUCH_END);
          return { ok: true, hits };
        }
      }
    }
  }
  return { ok: false, hits };
}`;

const EVAL_STATE = `() => {
  const canvas = window.cc && cc.find && cc.find("Canvas");
  const buttons = [];
  if (canvas && cc.Button) {
    for (const b of canvas.getComponentsInChildren(cc.Button)) {
      if (b.node && b.node.activeInHierarchy) buttons.push(b.node.name);
    }
  }
  const bundles = [];
  if (window.cc && cc.assetManager && cc.assetManager.bundles) {
    cc.assetManager.bundles.forEach((b, n) => bundles.push(n || (b && b.name)));
  }
  let formKeys = [];
  try {
    const form = app.FormManager().form;
    formKeys = Object.keys(form).filter((k) => /2110|Hist|Detail|Day/i.test(k));
  } catch (e) {
    formKeys = ["err:" + e];
  }
  let show = [];
  try { show = app.FormManager().GetShowFormList() || []; } catch {}
  return {
    bundles,
    buttons: buttons.slice(0, 80),
    formKeys,
    show,
    children: canvas && canvas.children ? canvas.children.map((c) => c.name) : [],
  };
}`;

const EVAL_CLICK_LI = `(nth) => {
  const canvas = cc.find("Canvas");
  const lis = [];
  if (canvas && cc.Button) {
    for (const b of canvas.getComponentsInChildren(cc.Button)) {
      if (b.node && b.node.activeInHierarchy && (b.node.name === "li" || /history/i.test(b.node.name))) {
        lis.push(b.node.name);
      }
    }
    let i = 0;
    for (const b of canvas.getComponentsInChildren(cc.Button)) {
      if (b.node && b.node.activeInHierarchy && b.node.name === "li") {
        if (i === nth) {
          b.node.emit("click");
          const comps = b.node.getComponents(cc.Component);
          for (const c of comps) {
            if (typeof c.OnClick === "function") {
              try { c.OnClick("li"); } catch (e) { return { ok: false, error: String(e), lis }; }
            }
          }
          return { ok: true, lis };
        }
        i++;
      }
    }
  }
  return { ok: false, lis };
}`;

const EVAL_DETAIL = `() => {
  const canvas = cc.find("Canvas");
  const stack = canvas ? [canvas] : [];
  const found = [];
  while (stack.length) {
    const node = stack.pop();
    const comps = node.getComponents ? node.getComponents(cc.Component) : [];
    for (const c of comps) {
      const js = c.JS_Name || "";
      if (/History|Detail|2110/i.test(js) || /History|Detail/i.test(node.name || "")) {
        found.push({ js, node: node.name, active: !!(node && node.activeInHierarchy) });
      }
      if (js === "Game2110GameDetailView") {
        return {
          found,
          detail: {
            js,
            pageIdx: c.pageIdx,
            extend: c.data && c.data.extend,
            resultsLen: c.data && c.data.results && c.data.results.length,
            order_id: c.data && c.data.order_id,
            bet: c.data && c.data.bet,
            level: c.data && c.data.level,
            symbolChildren: c.symbolParent && c.symbolParent.children && c.symbolParent.children.length,
            payoutChildren: c.payoutContent && c.payoutContent.children && c.payoutContent.children.length,
            title: c.titleLab && c.titleLab.string,
            profit: c.profitLab && c.profitLab.string,
            price: c.priceLab && c.priceLab.string,
            time: c.timeLab && c.timeLab.string,
            order: c.orderIdLab && c.orderIdLab.string,
          },
        };
      }
    }
    if (node.children) for (const ch of node.children) stack.push(ch);
  }
  return { found, detail: null };
}`;

const EVAL_ENTER = `() => {
  for (const el of document.querySelectorAll("canvas")) {
    const r = el.getBoundingClientRect();
    for (const ny of [0.72, 0.76, 0.80, 0.84]) {
      const x = r.left + r.width * 0.5;
      const y = r.top + r.height * ny;
      for (const type of ["pointerdown", "mousedown", "pointerup", "mouseup", "click"]) {
        el.dispatchEvent(new MouseEvent(type, { bubbles: true, clientX: x, clientY: y, view: window }));
      }
    }
  }
  try {
    const canvas = cc.find("Canvas");
    if (canvas && cc.Button) {
      for (const b of canvas.getComponentsInChildren(cc.Button)) {
        if ((b.node && b.node.name) === "btnEnter" && b.node.activeInHierarchy) b.node.emit("click");
      }
    }
  } catch {}
  return true;
}`;

const browser = await chromium.launch({
  headless: true,
  executablePath: "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
});
const context = await browser.newContext({ viewport: { width: 1280, height: 720 } });
const page = await context.newPage();
const events = [];
page.on("console", (msg) => {
  if (["error", "warning"].includes(msg.type())) {
    events.push({ type: "console-" + msg.type(), text: msg.text().slice(0, 300) });
  }
});
page.on("pageerror", (error) => events.push({ type: "pageerror", text: String(error.message).slice(0, 400) }));
page.on("response", (response) => {
  const url = response.url();
  if (url.includes("/cp/") || response.status() >= 400) {
    events.push({ type: "http", status: response.status(), url: url.slice(0, 220) });
  }
});

await page.goto(URL, { waitUntil: "domcontentloaded", timeout: 60000 });
let state = {};
for (let i = 0; i < 30; i++) {
  await sleep(1000);
  state = await page.evaluate(`(${EVAL_STATE})()`).catch((e) => ({ error: String(e) }));
  if ((state.bundles || []).includes("Game2110") && (state.buttons || []).includes("btn_menu")) break;
}
await shot(page, "01-load");
for (let i = 0; i < 8; i++) {
  await page.evaluate(`(${EVAL_ENTER})()`);
  await sleep(800);
  state = await page.evaluate(`(${EVAL_STATE})()`).catch((e) => ({ error: String(e) }));
  if ((state.buttons || []).includes("btn_start")) break;
}
await shot(page, "02-board");

const canvasBox = await page.locator("#GameCanvas, canvas").first().boundingBox();
const menu = await page.evaluate(`(${EVAL_CLICK})("btn_menu")`);
if (canvasBox) {
  await page.mouse.click(canvasBox.x + canvasBox.width * 0.86, canvasBox.y + canvasBox.height * 0.90);
}
await sleep(2500);
await shot(page, "03-menu");
let histBtn = { ok: false };
for (let i = 0; i < 8; i++) {
  histBtn = await page.evaluate(`(${EVAL_CLICK})("btn_history")`);
  if (histBtn.ok) break;
  await sleep(400);
}
if (canvasBox && !histBtn.ok) {
  await page.mouse.click(canvasBox.x + canvasBox.width * 0.72, canvasBox.y + canvasBox.height * 0.62);
}
await sleep(2500);
await shot(page, "04-history");
const afterHist = await page.evaluate(`(${EVAL_STATE})()`).catch((e) => ({ error: String(e) }));
const li0 = await page.evaluate(`(${EVAL_CLICK_LI})(0)`);
await sleep(2500);
await shot(page, "05-day");
const li1 = await page.evaluate(`(${EVAL_CLICK_LI})(0)`);
await sleep(2000);
await shot(page, "06-detail");

const gold = await fetch(`${BASE}/cp/goldgame/single_game_user_gold_history`, {
  method: "POST",
  headers: { "Content-Type": "application/x-www-form-urlencoded" },
  body: new URLSearchParams({ token: TOKEN, gid: "2110" }).toString(),
}).then((r) => r.json());
const day = gold?.data?.list?.[0]?.day ?? "";
const hist = await fetch(`${BASE}/cp/goldgame/single_game_user_history`, {
  method: "POST",
  headers: { "Content-Type": "application/x-www-form-urlencoded" },
  body: new URLSearchParams({ token: TOKEN, gid: "2110", day: String(day), page: "1", page_size: "30" }).toString(),
}).then((r) => r.json());
const row = hist?.data?.list?.[0] || null;

const injected = await page.evaluate(`(async (row) => {
  const info = { hasRow: !!row, extend: row && row.extend, err: null, show: null };
  try {
    const r = await app.FormManager().ShowForm("Game2110GameDetailView", row);
    info.show = !!r;
    info.ctor = r && r.JS_Name;
  } catch (e) { info.err = String(e && e.stack || e); }
  return info;
})(${JSON.stringify(row)})`);
await sleep(2000);
await shot(page, "07-detail-injected");
let inspect = await page.evaluate(`(${EVAL_DETAIL})()`).catch((e) => ({ error: String(e) }));
inspect.injected = injected;
inspect.rowKeys = row ? Object.keys(row) : [];
inspect.rowExtend = row && row.extend;

const report = {
  url: URL.replace(TOKEN, "<token>"),
  menu, histBtn, li0, li1, afterHist, inspect,
  events: events.slice(0, 100),
  testedAt: new Date().toISOString(),
};
fs.writeFileSync(REPORT, JSON.stringify(report, null, 2));
console.log(JSON.stringify({
  menu, histBtn, li0, li1,
  formKeys: afterHist.formKeys,
  show: afterHist.show,
  buttonsAfterHist: (afterHist.buttons || []).filter((n) => /hist|li|back|detail|day/i.test(n)),
  inspect,
  httpCp: events.filter((e) => String(e.url || "").includes("/cp/gold") || String(e.url || "").includes("history")),
  pageerrors: events.filter((e) => e.type === "pageerror").slice(0, 8),
}, null, 2));

await context.close();
await browser.close();

const detail = inspect && inspect.detail;
if (!detail || !detail.symbolChildren || detail.symbolChildren < 15) {
  console.error("FAIL board not rendered");
  process.exit(1);
}
if (detail.payoutChildren == null || detail.payoutChildren < 1) {
  console.error("FAIL payout empty");
  process.exit(1);
}
console.log("PASS_BROWSER");
