const list = await (await fetch('http://127.0.0.1:19223/json/list')).json();
const target = list.find((t) => t.type === 'page' && /hms-paddle/.test(t.url));
const ws = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((ok, no) => { ws.addEventListener('open', ok, { once: true }); ws.addEventListener('error', no, { once: true }); });
let id = 0;
const pending = new Map();
ws.addEventListener('message', (ev) => {
  const m = JSON.parse(String(ev.data));
  if (m.id && pending.has(m.id)) {
    const p = pending.get(m.id); pending.delete(m.id);
    m.error ? p.reject(new Error(JSON.stringify(m.error))) : p.resolve(m.result);
  }
});
const call = (method, params = {}, timeout = 30000) => new Promise((resolve, reject) => {
  const n = ++id; pending.set(n, { resolve, reject });
  ws.send(JSON.stringify({ id: n, method, params }));
  setTimeout(() => { if (pending.delete(n)) reject(new Error('timeout ' + method)); }, timeout);
});
const evalExpr = async (expression) => {
  const r = await call('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true, userGesture: true });
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.text || r.exceptionDetails.exception?.description);
  return r.result.value;
};
await call('Runtime.enable');
await call('Page.enable');

const info = await evalExpr(`(() => {
  const vis = (e) => { const r = e.getBoundingClientRect(); const s = getComputedStyle(e); return r.width>4 && r.height>4 && s.display!=='none' && s.visibility!=='hidden'; };
  const cp = [...document.querySelectorAll('*')].filter(e => vis(e) && e.childElementCount===0 && /^CP$/i.test((e.textContent||'').trim())).map(e => ({text:e.textContent.trim(), className:String(e.className).slice(0,160), rect:e.getBoundingClientRect().toJSON()}));
  const imgs = [...document.querySelectorAll('img,[data-src]')].filter(vis).slice(0,30).map(e => ({src:e.currentSrc||e.src||e.getAttribute('data-src'), className:String(e.className).slice(0,80)}));
  const jungle = [...document.querySelectorAll('*')].filter(e => /jungle\\s*fruit|50016|cyber\\s*go|sharpshooter/i.test(e.textContent||'') || /50016|cyber|sharp/i.test(e.getAttribute?.('data-src')||e.src||'')).slice(0,10).map(e => ({tag:e.tagName, text:(e.textContent||'').trim().slice(0,80), src:e.src||e.getAttribute('data-src')}));
  return { url: location.href, user: localStorage.getItem('login_username'), cp, imgCount: imgs.length, imgs, jungle, hasVier: !!document.querySelector('#vier_port') };
})()`);
console.log(JSON.stringify(info, null, 2));

// click CP leaf
const clicked = await evalExpr(`(() => {
  const vis = (e) => { const r = e.getBoundingClientRect(); const s = getComputedStyle(e); return r.width>4 && r.height>4 && s.display!=='none' && s.visibility!=='hidden'; };
  const nodes = [...document.querySelectorAll('*')].filter(e => vis(e) && e.childElementCount===0 && /^CP$/i.test((e.textContent||'').trim()));
  const el = nodes[0];
  if (!el) return { ok:false, reason:'no-cp' };
  el.click();
  return { ok:true, className:String(el.className), parent:String(el.parentElement?.className||'') };
})()`);
console.log('CLICK_CP', JSON.stringify(clicked));
await new Promise((r) => setTimeout(r, 5000));

const afterCp = await evalExpr(`(() => {
  const vis = (e) => { const r = e.getBoundingClientRect(); const s = getComputedStyle(e); return r.width>8 && r.height>8 && s.display!=='none' && s.visibility!=='hidden'; };
  const text = (document.body.innerText||'').slice(0,2500);
  const jungle = [...document.querySelectorAll('*')].filter(e => vis(e) && /Jungle\\s*Fruit/i.test(e.textContent||'') && e.childElementCount===0).slice(0,10).map(e => ({text:e.textContent.trim(), className:String(e.className).slice(0,120)}));
  return { url: location.href, text, jungle, cover50016: [...document.querySelectorAll('[data-src*="50016"],img[src*="50016"]')].length, cpCovers: [...document.querySelectorAll('[data-src*="/game/cp/"],img[src*="/game/cp/"]')].length };
})()`);
console.log('AFTER_CP', JSON.stringify(afterCp, null, 2).slice(0, 5000));
ws.close();
