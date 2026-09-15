import { writeFile } from 'node:fs/promises';
const [origin='http://127.0.0.1:52300', label='api', roundArg='10'] = process.argv.slice(2);const targetRounds=Number(roundArg);
const token=`browser-${label}`; const sleep=ms=>new Promise(r=>setTimeout(r,ms));
const targets=await(await fetch('http://127.0.0.1:9230/json')).json();const target=targets.find(x=>x.type==='page');if(!target)throw new Error('no page');
const ws=new WebSocket(target.webSocketDebuggerUrl);await new Promise((ok,bad)=>{ws.addEventListener('open',ok,{once:true});ws.addEventListener('error',bad,{once:true});});let id=0;const pending=new Map();const network=[];const errors=[];
ws.addEventListener('message',event=>{const m=JSON.parse(typeof event.data==='string'?event.data:Buffer.from(event.data).toString());if(m.id&&pending.has(m.id)){const p=pending.get(m.id);pending.delete(m.id);m.error?p.reject(new Error(m.error.message)):p.resolve(m.result);return;}if(m.method==='Network.responseReceived')network.push({url:m.params.response.url,status:m.params.response.status,mime:m.params.response.mimeType});if(m.method==='Network.loadingFailed')errors.push({type:'network',url:m.params.requestId,error:m.params.errorText});if(m.method==='Runtime.exceptionThrown')errors.push({type:'exception',text:m.params.exceptionDetails.exception?.description??m.params.exceptionDetails.text});});
function cdp(method,params={}){return new Promise((resolve,reject)=>{const n=++id;pending.set(n,{resolve,reject});ws.send(JSON.stringify({id:n,method,params}));setTimeout(()=>{if(pending.delete(n))reject(new Error('timeout '+method));},15000).unref();});}
async function click(x,y){await cdp('Input.dispatchMouseEvent',{type:'mousePressed',x,y,button:'left',buttons:1,clickCount:1});await cdp('Input.dispatchMouseEvent',{type:'mouseReleased',x,y,button:'left',clickCount:1});}
async function shot(name){const r=await cdp('Page.captureScreenshot',{format:'png',fromSurface:true});const p=`D:/work/hd/cpgame/screenshots/2300-Monster-Slayer/${name}.png`;await writeFile(p,Buffer.from(r.data,'base64'));return p;}
await cdp('Page.enable');await cdp('Network.enable');await cdp('Runtime.enable');
const api='127.0.0.1:52300';const url=`${origin}/?ai=luck_single_10229&btt=1&gid=2300&l=en&language=en-us&sip=${encodeURIComponent(api)}&t=${token}&token=${token}`;await cdp('Page.navigate',{url});await sleep(18000);const before=await shot(`${label}-before-start`);
await click(375,1120);await sleep(18000);const ready=await shot(`${label}-ready`);
async function getHistory(){return fetch(`http://127.0.0.1:52300/cp/goldgame/single_game_user_history`,{method:'POST',headers:{'content-type':'application/x-www-form-urlencoded'},body:new URLSearchParams({gid:'2300',token,page_size:'100'})}).then(r=>r.json());}
let completedClicks=0;
for(let wanted=1;wanted<=targetRounds;wanted++){
  const deadline=Date.now()+180000;
  while(Date.now()<deadline){
    await click(365,1015);
    for(let poll=0;poll<3;poll++){const h=await getHistory();if((h.data?.list?.length??0)>=wanted){completedClicks=wanted;break;}await sleep(1000);}
    if(completedClicks>=wanted)break;
  }
  if(completedClicks<wanted)break;
  await sleep(6500);
}
await sleep(3000);const after=await shot(`${label}-after-clicks`);const history=await getHistory();
const unique404=[...new Set(network.filter(x=>x.status===404).map(x=>x.url))];
const result={schemaVersion:1,gameId:2300,label,url,originalCanvas:await cdp('Runtime.evaluate',{expression:"document.querySelectorAll('#GameCanvas').length",returnByValue:true}).then(x=>x.result.value),screenshots:[before,ready,after],requests:network.length,non404:network.filter(x=>x.status!==404).length,notFound:unique404,apiCalls:network.filter(x=>x.url.includes('/cp/')).map(x=>({url:x.url,status:x.status})),exceptions:errors,completedCanvasClicks:completedClicks,historyRows:history.data?.list?.length??0,historySpecialRows:(history.data?.list??[]).filter(x=>(x.results?.length??0)>1).length,allSpecialTerminal:(history.data?.list??[]).filter(x=>(x.results?.length??0)>1).every(x=>x.results.at(-1)?.f?.nt===0),completedAt:new Date().toISOString()};
await writeFile(`D:/work/hd/cpgame/reports/2300-Monster-Slayer/browser-${label}-validation.json`,JSON.stringify(result,null,2));process.stdout.write(JSON.stringify(result,null,2));ws.close();
