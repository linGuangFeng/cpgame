import { Buffer } from 'node:buffer';

const LAB = 'http://127.0.0.1:8000';

async function postForm(url, fields) {
  const body = new URLSearchParams(fields).toString();
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  });
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch {}
  return { status: res.status, json, text: text.slice(0, 400) };
}

async function startGame(dir) {
  const res = await fetch(`${LAB}/games/${encodeURIComponent(dir)}/start`, { method: 'POST', body: '' });
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch {}
  return { status: res.status, json, text };
}

function keys(obj, max = 20) {
  if (!obj || typeof obj !== 'object') return typeof obj;
  return Object.keys(obj).slice(0, max);
}

function list0(data) {
  const list = data?.list || data?.ll || data?.log_list || [];
  return Array.isArray(list) ? list[0] : null;
}

const jobs = [
  {
    dir: '2410-Electro-Fiesta',
    token: 'hist-2410',
    spin: (base) => postForm(`${base}/cp/single_game.Game/gameResult`, { token: 'hist-2410', type: '1', bet: '0.8', level: '1', gid: '2410' }),
    after: async (base) => {
      const gold = await postForm(`${base}/cp/goldgame/single_game_user_gold_history`, { token: 'hist-2410', gid: '2410', page: '1', page_size: '30' });
      const day = gold.json?.data?.list?.[0]?.d || gold.json?.data?.list?.[0]?.day;
      const daily = await postForm(`${base}/cp/goldgame/single_game_user_history`, { token: 'hist-2410', gid: '2410', day: String(day || 0), page: '1', page_size: '30' });
      const range = await postForm(`${base}/cp/Goldgame/user_game_history`, { token: 'hist-2410', gid: '2410', start: '0', end: String(Math.floor(Date.now() / 1000) + 10), page: '1', page_size: '30' });
      return { gold, daily, range };
    },
  },
  {
    dir: '2060-Club-Goddess',
    token: 'hist-2060',
    spin: (base) => postForm(`${base}/cp/single_game.Game/gameResult`, { token: 'hist-2060', bet_gold: '0.01', level: '10', gid: '2060' }),
    after: async (base) => {
      const gold = await postForm(`${base}/cp/goldgame/single_game_user_gold_history`, { token: 'hist-2060', gid: '2060' });
      const day = gold.json?.data?.list?.[0]?.day;
      const daily = await postForm(`${base}/cp/goldgame/single_game_user_history`, { token: 'hist-2060', gid: '2060', day: String(day || 0), page: '1', page_size: '30' });
      return { gold, daily };
    },
  },
  {
    dir: '1830-Hotpot',
    token: 'hist-1830',
    spin: (base) => postForm(`${base}/cp/single_game.Game/gameResult`, { token: 'hist-1830', bet_gold: '0.02', level: '1', gid: '1830' }),
    after: async (base) => {
      const gold = await postForm(`${base}/cp/goldgame/single_game_user_gold_history`, { token: 'hist-1830', gid: '1830' });
      const day = gold.json?.data?.list?.[0]?.day;
      const daily = await postForm(`${base}/cp/goldgame/single_game_user_history`, { token: 'hist-1830', gid: '1830', day: String(day || 0), page: '1', page_size: '30' });
      return { gold, daily };
    },
  },
  {
    dir: '2210-Wu-Kong',
    token: 'hist-2210',
    spin: (base) => postForm(`${base}/cp/single_game.black_myth_wukong/gameResult`, { token: 'hist-2210', bet_gold: '1', gid: '2210' }),
    after: async (base) => {
      const gold = await postForm(`${base}/cp/goldgame/wukong_user_gold_history`, { token: 'hist-2210', gid: '2210' });
      const day = gold.json?.data?.list?.[0]?.day;
      const daily = await postForm(`${base}/cp/goldgame/wukong_user_history`, { token: 'hist-2210', gid: '2210', day: String(day || 0), page: '1', page_size: '30' });
      return { gold, daily };
    },
  },
];

const report = [];
for (const job of jobs) {
  const start = await startGame(job.dir);
  const port = start.json?.port;
  const row = { dir: job.dir, startOk: start.json?.ok, port, startMsg: start.json?.message || start.text.slice(0, 180) };
  if (!port) { report.push(row); continue; }
  const base = `http://127.0.0.1:${port}`;
  await new Promise((r) => setTimeout(r, 1500));
  try {
    const spin = await job.spin(base);
    row.spinStatus = spin.status;
    row.spinCode = spin.json?.code;
    row.spinMsg = spin.json?.msg || spin.text.slice(0, 160);
    const hist = await job.after(base);
    for (const [name, res] of Object.entries(hist)) {
      const data = res.json?.data;
      const first = list0(data);
      row[name] = {
        status: res.status,
        code: res.json?.code,
        dataKeys: keys(data),
        listN: (data?.list || data?.ll || data?.log_list || []).length,
        firstKeys: keys(first),
        hasResult: !!(first && (first.result || first.res || first.results)),
        hasExtend: !!(first && (first.extend || first.ext)),
        totals: data?.totals || data?.statistics,
        err: res.json?.msg,
      };
    }
  } catch (e) {
    row.error = String(e);
  }
  report.push(row);
}

console.log(JSON.stringify(report, null, 2));
