import { readdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';

const fixtureRoot = 'fixtures/2300-Monster-Slayer/spin';
const dirs = (await readdir(fixtureRoot, {withFileTypes:true}))
  .filter(entry => entry.isDirectory()).map(entry => entry.name).sort().slice(0, 20);
const captured = [];
for (const dir of dirs) {
  const envelope = JSON.parse(await readFile(path.join(fixtureRoot, dir, 'step-001.response.json'), 'utf8'));
  captured.push({ref:`${dir}/step-001.response.json`, data:envelope.body.data});
}

const endpoint = 'http://127.0.0.1:52300/cp/single_game.Game/gameResult';
const token = 'visual-compare-20';
const request = async type => {
  const response = await fetch(endpoint, {method:'POST',headers:{'content-type':'application/x-www-form-urlencoded'},body:new URLSearchParams({gid:'2300',token,type:String(type),bet:'0.2',level:'10'})});
  const envelope = await response.json();
  if (envelope.code !== 0) throw new Error(envelope.msg);
  return envelope.data;
};
const generated = [];
for (let i=0;i<20;i++) {
  const first = await request(1); generated.push(first);
  let current = first;
  while (current.f.nt !== 0) current = await request(2);
}

const shape = data => ({
  boardLength:data.res.ps.length,
  boardIntegers:data.res.ps.every(Number.isInteger),
  awardsArray:Array.isArray(data.res.wa),
  winningCellsArray:Array.isArray(data.res.tws),
  featureFields:['a','gt','h','loc','nt','r','rbs','tw'].every(key=>key in data.f),
  roundFields:['b','bg','cg','eg','l','o','oid','sg','small_game_type','t','tw','twy','u'].every(key=>key in data)
});
const rows = captured.map((item,index)=>{
  const capturedShape=shape(item.data),generatedShape=shape(generated[index]);
  return {index:index+1,capturedRef:item.ref,captured:capturedShape,generated:generatedShape,pass:Object.values(capturedShape).every(v=>v===true||v===15)&&Object.values(generatedShape).every(v=>v===true||v===15)};
});
const report={schemaVersion:1,gameId:2300,comparison:"20 captured paid first pages versus 20 independently generated Redis-backed paid first pages",rows,pass:rows.every(row=>row.pass),completedAt:new Date().toISOString()};
await writeFile('reports/2300-Monster-Slayer/visual-compare-20.json',JSON.stringify(report,null,2));
process.stdout.write(JSON.stringify({rows:rows.length,pass:report.pass}));
