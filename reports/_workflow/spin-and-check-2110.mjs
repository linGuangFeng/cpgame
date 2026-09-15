const BASE = "http://127.0.0.1:50047";
const TOKEN = "playthrough-browser-hist-" + Date.now();

async function post(path, data) {
  const res = await fetch(BASE + path, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams(data).toString(),
  });
  const json = await res.json();
  return { status: res.status, json };
}

const health = await (await fetch(BASE + "/health")).text();
console.log("HEALTH", health);

let first = true;
let steps = 0;
do {
  const form = { token: TOKEN, gid: "2110", bet_gold: "0.02", level: "10" };
  if (first) form.playthrough_kind = "ORDINARY_WIN";
  const spin = await post("/cp/single_game.Game/gameResult", form);
  first = false;
  steps++;
  const d = spin.json?.data || {};
  console.log("SPIN", steps, "status", spin.status, "type", d.type, "tw", d.props?.tw ?? d.result?.tw, "st", d.frees?.st, "code", spin.json?.code, "msg", spin.json?.msg);
  if (spin.json?.code && spin.json.code !== 0) break;
  if (!d.frees || d.frees.st === 0) break;
  if (steps > 20) break;
} while (true);

const gold = await post("/cp/goldgame/single_game_user_gold_history", { token: TOKEN, gid: "2110" });
console.log("GOLD", gold.status, JSON.stringify(gold.json?.data));
const day = gold.json?.data?.list?.[0]?.day ?? "";
const hist = await post("/cp/goldgame/single_game_user_history", {
  token: TOKEN, gid: "2110", day: String(day), page: "1", page_size: "30",
});
const row = hist.json?.data?.list?.[0] || null;
console.log("HIST_STATUS", hist.status, "listLen", hist.json?.data?.list?.length);
if (!row) {
  console.log("NO_ROW", JSON.stringify(hist.json).slice(0, 1500));
  process.exit(1);
}
const s0 = row.results?.[0] || {};
const report = {
  token: TOKEN,
  parentKeys: Object.keys(row).sort(),
  parentExtend: row.extend || null,
  stepExtend: s0.extend || null,
  parentActId: row.extend?.act_id,
  stepActId: s0.extend?.act_id,
  spe_pos: s0.spe_pos,
  resultProp: s0.result?.prop,
  resultTw: s0.result?.tw,
  frees: s0.frees,
  type: s0.type,
  bet: row.bet,
  level: row.level,
  end_gold: row.end_gold,
  order_id: row.order_id,
  resultsLen: (row.results || []).length,
};
console.log(JSON.stringify(report, null, 2));
if (!row.extend || String(row.extend.act_id) !== "0") {
  console.error("FAIL parent extend");
  process.exit(2);
}
if (!s0.extend || String(s0.extend.act_id) !== "0") {
  console.error("FAIL step extend");
  process.exit(3);
}
if (!Array.isArray(s0.result?.prop) || s0.result.prop.length !== 15) {
  console.error("FAIL board prop");
  process.exit(4);
}
console.log("PASS_JSON");
console.log("TOKEN=" + TOKEN);
