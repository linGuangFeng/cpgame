const list = await (await fetch('http://127.0.0.1:19223/json/list')).json();
const target = list.find((t) => t.type === 'page' && /hms-paddle/.test(t.url));
if (!target) throw new Error('no hall page: ' + JSON.stringify(list.map((t) => ({ type: t.type, url: t.url, title: t.title }))));
const ws = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((ok, no) => { ws.addEventListener('open', ok, { once: true }); ws.addEventListener('error', no, { once: true }); });
let id = 0;
const pending = new Map();
ws.addEventListener('message', (ev) => {
  const m = JSON.parse(String(ev.data));
  if (m.id && pending.has(m.id)) {
    const p = pending.get(m.id); pending.delete(m.id);
    m.error ? p.reject(new Error(m.error.message)) : p.resolve(m.result);
  }
});
const call = (method, params = {}) => new Promise((resolve, reject) => {
  const n = ++id; pending.set(n, { resolve, reject });
  ws.send(JSON.stringify({ id: n, method, params }));
  setTimeout(() => { if (pending.delete(n)) reject(new Error('timeout ' + method)); }, 20000);
});
await call('Runtime.enable');
await call('Page.enable');
const before = await call('Runtime.evaluate', { expression: `({url:location.href, user:localStorage.getItem('login_username'), text:(document.body&&document.body.innerText||'').slice(0,500)})`, returnByValue: true });
console.log('BEFORE', JSON.stringify(before.result.value));
await call('Runtime.evaluate', { expression: `location.hash='#/index'; true`, returnByValue: true });
await new Promise((r) => setTimeout(r, 4000));
const after = await call('Runtime.evaluate', { expression: `({url:location.href, user:localStorage.getItem('login_username'), text:(document.body&&document.body.innerText||'').slice(0,1500)})`, returnByValue: true });
console.log('AFTER', JSON.stringify(after.result.value));
ws.close();
