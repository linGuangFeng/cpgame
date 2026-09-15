import fs from 'node:fs';
import path from 'node:path';

const hbPath = 'D:/work/hd/cpgame/reports/_workflow/collect-20260908/worker-abc223.json';
const statePath = 'D:/work/hd/cpgame/reports/_workflow/collect-20260908/abc223-state.json';
const stopPath = 'D:/work/hd/cpgame/reports/_workflow/collect-20260908/abc223-stop.flag';

function atomicWrite(file, obj) {
  const tmp = `${file}.tmp-${process.pid}`;
  fs.writeFileSync(tmp, `${JSON.stringify(obj, null, 2)}\n`);
  fs.renameSync(tmp, file);
}

function tick() {
  if (fs.existsSync(stopPath)) process.exit(0);
  let hb = {
    worker: 'abc223',
    alive: true,
    phase: 'INVENTORY',
    currentGame: '16-Jungle-Fruit',
    error: null,
    updatedAt: new Date().toISOString(),
    games: {}
  };
  try {
    if (fs.existsSync(statePath)) hb = { ...hb, ...JSON.parse(fs.readFileSync(statePath, 'utf8')) };
    else if (fs.existsSync(hbPath)) hb = { ...hb, ...JSON.parse(fs.readFileSync(hbPath, 'utf8')) };
  } catch {}
  hb.worker = 'abc223';
  hb.alive = true;
  hb.updatedAt = new Date().toISOString();
  atomicWrite(hbPath, hb);
}

tick();
setInterval(tick, 90000);
process.stdout.write('heartbeat-loop started\n');
