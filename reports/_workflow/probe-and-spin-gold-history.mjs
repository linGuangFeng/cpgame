const games = [
  { dir: "2060-Club-Goddess", port: 50048, token: "local-demo-launch", gid: "2060",
    spin: "/cp/single_game.Game/gameResult", extra: { bet_gold: "0.02", level: "10" } },
  { dir: "1830-Hotpot", port: 50049, token: "local-replay", gid: "1830",
    spin: "/cp/single_game.Game/gameResult", extra: { bet_gold: "0.8", level: "1" } },
  { dir: "2210-Wu-Kong", port: 50050, token: "local-replay", gid: "2210",
    spin: "/cp/single_game.Game/gameResult", extra: { bet_gold: "0.8", level: "1" } },
  { dir: "2410-Electro-Fiesta", port: 50051, token: "demo", gid: "2410",
    spin: "/cp/single_game.Game/gameResult", extra: { bet: "0.8", level: "1" } },
  { dir: "1910-Churrasco", port: 50052, token: "local-1910", gid: "1910",
    spin: "/cp/single_game.Game/gameResult", extra: { bet_gold: "0.8", level: "1" } },
  { dir: "1810-Treasure-Hunt", port: 50053, token: "local-1810", gid: "1810",
    spin: "/cp/single_game.Game/gameResult", extra: { bet: "0.8", level: "1" } },
  { dir: "2290-Samba-Sensation", port: 50054, token: "local-replay", gid: "2290",
    spin: "/cp/single_game.Game/gameResult", extra: { bet_gold: "0.8", level: "1" } },
];

async function post(url, data) {
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams(data).toString(),
  });
  const text = await res.text();
  let json;
  try { json = JSON.parse(text); } catch { json = { _raw: text.slice(0, 300) }; }
  return { status: res.status, json };
}

function summarize(row) {
  if (!row) return null;
  const results = row.results || row.res || [];
  const step = results[0] || null;
  const result = (step && (step.result || step.props)) || row.result || row.props;
  const prop = result && (result.prop || result.board || result.props);
  return {
    keys: Object.keys(row).sort(),
    extend: row.extend ?? null,
    stepExtend: step?.extend ?? null,
    resultsLen: results.length,
    propLen: Array.isArray(prop) ? prop.length : (prop ? "obj" : null),
    order_id: row.order_id || row.oid,
    bet: row.bet ?? row.bet_gold ?? row.bg,
    change: row.change_gold ?? row.cg,
  };
}

const out = [];
for (const g of games) {
  const base = `http://127.0.0.1:${g.port}`;
  const item = { dir: g.dir, port: g.port };
  try {
    const h = await fetch(base + "/health");
    item.health = h.status;
    item.healthBody = (await h.text()).slice(0, 160);
  } catch (e) {
    item.health = "DOWN";
    item.error = String(e);
    out.push(item);
    continue;
  }
  const boot = await post(base + "/cp/account/getUserInfo", { token: g.token, gid: g.gid });
  const token = boot.json?.data?.token || g.token;
  item.boot = boot.status;
  item.tokenUsed = token === g.token ? "same" : "bootstrap";

  async function spinUntilDone() {
    let first = true;
    for (let i = 0; i < 12; i++) {
      const body = { token, gid: g.gid, ...g.extra };
      const r = await post(base + g.spin, body);
      item.lastSpin = { i, status: r.status, code: r.json?.code, msg: r.json?.msg };
      const d = r.json?.data || {};
      const st = d.frees?.st ?? d.free_times ?? 0;
      if (r.status !== 200 || (r.json?.code && r.json.code !== 0)) return false;
      first = false;
      if (!st) return true;
    }
    return true;
  }

  let gold = await post(base + "/cp/goldgame/single_game_user_gold_history", { token, gid: g.gid });
  item.goldStatus = gold.status;
  let list = gold.json?.data?.list || [];
  const hasBet = list.some((x) => Number(x.bet_gold || x.bg || 0) > 0);
  if (!hasBet) {
    item.spun = await spinUntilDone();
    gold = await post(base + "/cp/goldgame/single_game_user_gold_history", { token, gid: g.gid });
    list = gold.json?.data?.list || [];
  }
  item.goldDays = list.length;
  const day = list.find((x) => Number(x.bet_gold || x.bg || 0) > 0)?.day
    || list.find((x) => Number(x.bet_gold || x.bg || 0) > 0)?.d
    || list[0]?.day
    || list[0]?.d
    || "";
  const hist = await post(base + "/cp/goldgame/single_game_user_history", {
    token, gid: g.gid, day: String(day), page: "1", page_size: "30",
  });
  item.histStatus = hist.status;
  const rows = hist.json?.data?.list || hist.json?.data?.res || [];
  item.listLen = rows.length;
  item.row = summarize(rows[0]);
  if (!rows.length) {
    const alt = await post(base + "/cp/Goldgame/user_game_history", { token, gid: g.gid });
    item.altStatus = alt.status;
    const altRows = alt.json?.data?.list || alt.json?.data?.res || [];
    item.altLen = altRows.length;
    item.altRow = summarize(altRows[0]);
  }
  out.push(item);
}
console.log(JSON.stringify(out, null, 2));
