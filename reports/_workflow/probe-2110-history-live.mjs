const BASE = "http://127.0.0.1:50043";
const TOKEN = "demo";

async function post(url, data) {
  const body = new URLSearchParams(data).toString();
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body,
  });
  const text = await res.text();
  let json;
  try {
    json = JSON.parse(text);
  } catch {
    json = { _raw: text.slice(0, 500) };
  }
  return { status: res.status, json };
}

const health = await fetch(BASE + "/health");
console.log("HEALTH", health.status, await health.text());

const gold = await post(BASE + "/cp/goldgame/single_game_user_gold_history", {
  token: TOKEN,
  gid: "2110",
});
console.log("GOLD", gold.status);
console.log(JSON.stringify(gold.json, null, 2).slice(0, 2500));

const day = gold.json?.data?.list?.[0]?.day ?? "";
const hist = await post(BASE + "/cp/goldgame/single_game_user_history", {
  token: TOKEN,
  gid: "2110",
  day: String(day),
  page: "1",
  page_size: "30",
});
console.log("DAY", hist.status);
const data = hist.json?.data || {};
const lst = data.list || [];
console.log("list_len", lst.length, "stats", data.statistics);
if (!lst.length) {
  console.log("EMPTY_LIST");
  console.log(JSON.stringify(hist.json).slice(0, 2000));
} else {
  const row = lst[0];
  console.log("parent_keys", Object.keys(row).sort());
  console.log("extend", row.extend);
  console.log("spe_pos", row.spe_pos, Array.isArray(row.spe_pos));
  console.log(
    "result_type",
    typeof row.result,
    row.result && typeof row.result === "object" ? Object.keys(row.result) : row.result
  );
  console.log("results_len", (row.results || []).length);
  if (row.results?.[0]) {
    const s0 = row.results[0];
    console.log("step0_keys", Object.keys(s0).sort());
    console.log("step0.extend", s0.extend);
    console.log("step0.spe_pos", s0.spe_pos, Array.isArray(s0.spe_pos));
    console.log(
      "step0.result",
      typeof s0.result,
      s0.result && typeof s0.result === "object" ? Object.keys(s0.result) : s0.result
    );
    console.log("step0.frees", s0.frees);
    console.log("step0.type", s0.type);
    console.log("parent.bet", row.bet, "level", row.level, "end_gold", row.end_gold, "order_id", row.order_id);
  }
  console.log("FULL_ROW");
  console.log(JSON.stringify(row).slice(0, 5000));
}
