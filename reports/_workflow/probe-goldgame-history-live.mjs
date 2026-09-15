const games = [
  { dir: "2110-Bee-Workshop", port: 50043, token: "demo", gid: "2110",
    gold: "/cp/goldgame/single_game_user_gold_history",
    hist: "/cp/goldgame/single_game_user_history" },
  { dir: "2060-Club-Goddess", port: 50034, token: "local-demo-launch", gid: "2060",
    gold: "/cp/goldgame/single_game_user_gold_history",
    hist: "/cp/goldgame/single_game_user_history" },
  { dir: "1830-Hotpot", port: 50035, token: "local-replay", gid: "1830",
    gold: "/cp/goldgame/single_game_user_gold_history",
    hist: "/cp/goldgame/single_game_user_history" },
  { dir: "2210-Wu-Kong", port: 50036, token: "local-replay", gid: "2210",
    gold: "/cp/goldgame/single_game_user_gold_history",
    hist: "/cp/goldgame/single_game_user_history" },
  { dir: "2410-Electro-Fiesta", port: 50033, token: "demo", gid: "2410",
    gold: "/cp/goldgame/single_game_user_gold_history",
    hist: "/cp/goldgame/single_game_user_history",
    altGold: "/cp/Goldgame/user_game_history" },
];

async function post(url, data) {
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams(data).toString(),
  });
  const text = await res.text();
  let json;
  try { json = JSON.parse(text); } catch { json = { _raw: text.slice(0, 400) }; }
  return { status: res.status, json };
}

function summarizeRow(row) {
  if (!row || typeof row !== "object") return { empty: true };
  const results = row.results || row.res || [];
  const step = results[0] || null;
  const result = (step && (step.result || step.props)) || row.result || row.props;
  const prop = result && (result.prop || result.props);
  return {
    keys: Object.keys(row).sort(),
    extend: row.extend ?? null,
    stepExtend: step?.extend ?? null,
    spe_pos_is_array: Array.isArray(row.spe_pos) || Array.isArray(step?.spe_pos),
    resultsLen: results.length,
    resultPropLen: Array.isArray(prop) ? prop.length : null,
    bet: row.bet,
    level: row.level,
    order_id: row.order_id || row.oid,
    bet_gold: row.bet_gold ?? row.bg,
    change_gold: row.change_gold ?? row.cg,
  };
}

const out = [];
for (const g of games) {
  const base = `http://127.0.0.1:${g.port}`;
  const item = { dir: g.dir, port: g.port };
  try {
    const health = await fetch(base + "/health");
    item.health = health.status;
    item.healthBody = (await health.text()).slice(0, 180);
  } catch (e) {
    item.health = "DOWN";
    item.error = String(e);
    out.push(item);
    continue;
  }
  const gold = await post(base + g.gold, { token: g.token, gid: g.gid });
  item.goldStatus = gold.status;
  item.goldDays = gold.json?.data?.list?.length ?? gold.json?.data?.res?.length ?? null;
  item.goldStats = gold.json?.data?.statistics || gold.json?.data?.totals || null;
  const day = gold.json?.data?.list?.[0]?.day ?? gold.json?.data?.list?.[0]?.d ?? "";
  const hist = await post(base + g.hist, {
    token: g.token, gid: g.gid, day: String(day), page: "1", page_size: "30",
  });
  item.histStatus = hist.status;
  const list = hist.json?.data?.list || hist.json?.data?.res || [];
  item.listLen = list.length;
  item.row = list[0] ? summarizeRow(list[0]) : null;
  if (g.altGold) {
    const alt = await post(base + g.altGold, { token: g.token, gid: g.gid });
    item.altStatus = alt.status;
    const altList = alt.json?.data?.list || alt.json?.data?.res || [];
    item.altLen = altList.length;
    item.altRow = altList[0] ? summarizeRow(altList[0]) : null;
  }
  out.push(item);
}
console.log(JSON.stringify(out, null, 2));
