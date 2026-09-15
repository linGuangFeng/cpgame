const fs = require("fs");
const http = require("http");
const { WebSocket } = require("C:/Users/333/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/ws");
function get(url){return new Promise((res,rej)=>{http.get(url,r=>{let d="";r.on("data",c=>d+=c);r.on("end",()=>res(JSON.parse(d)));}).on("error",rej);});}
(async()=>{
  const list = await get("http://127.0.0.1:19223/json/list");
  const target = list.find(t => t.type === "page" && /hms-paddle/.test(t.url));
  if (!target) { console.log("NO_PAGE"); process.exit(2); }
  const u = String(target.url||"");
  const hash = (u.match(/#\/[^?]*/)||["?"])[0];
  console.log("HASH="+hash+" TITLE="+target.title);
  const ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((ok,no)=>{ws.on("open",ok);ws.on("error",no);});
  let id=0; const pending=new Map();
  ws.on("message", ev => { const m=JSON.parse(String(ev.data)); if(m.id && pending.has(m.id)){ const p=pending.get(m.id); pending.delete(m.id); m.error?p.reject(new Error(JSON.stringify(m.error))):p.resolve(m.result);} });
  const call=(method,params={})=>new Promise((resolve,reject)=>{ const n=++id; pending.set(n,{resolve,reject}); ws.send(JSON.stringify({id:n,method,params})); setTimeout(()=>{ if(pending.delete(n)) reject(new Error("timeout "+method)); }, 30000); });
  await call("Page.enable");
  const shot = await call("Page.captureScreenshot", {format:"png", fromSurface:true});
  fs.writeFileSync(process.argv[1], Buffer.from(shot.data, "base64"));
  const info = await call("Runtime.evaluate", {expression:`(() => {
    const vis = (e) => { const r=e.getBoundingClientRect(); const s=getComputedStyle(e); return r.width>4 && r.height>4 && s.display!=="none" && s.visibility!=="hidden"; };
    const text = (document.body && document.body.innerText || "").slice(0,500);
    const cp = [...document.querySelectorAll(".tabs,.tab_text")].filter(vis).map(e=>({cls:String(e.className).slice(0,40), t:(e.textContent||"").trim().slice(0,20)}));
    const covers = [...document.querySelectorAll("[data-src*='50016'],img[src*='50016'],[data-src*='50052'],img[src*='50052'],[data-src*='51090'],img[src*='51090']")].length;
    const jungle = [...document.querySelectorAll("*")].filter(e => vis(e) && /Jungle\\s*Fruit/i.test(e.textContent||"") && e.childElementCount===0).slice(0,5).map(e=>(e.textContent||"").trim());
    return {hash:location.hash, covers, jungle, cp:cp.slice(0,12), text};
  })()`, returnByValue:true});
  console.log(JSON.stringify(info.result.value, null, 2));
  ws.close();
})().catch(e=>{ console.error("ERR", e.message); process.exit(1); });
