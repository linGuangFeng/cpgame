async function post(url, data) {
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams(data).toString(),
  });
  return { status: res.status, json: await res.json().catch(async () => ({ _raw: await res.text() })) };
}

// 1830 row shape
{
  const base = "http://127.0.0.1:50049";
  const gold = await post(base + "/cp/goldgame/single_game_user_gold_history", { token: "local-replay", gid: "1830" });
  const day = gold.json?.data?.list?.[0]?.day;
  const hist = await post(base + "/cp/goldgame/single_game_user_history", { token: "local-replay", gid: "1830", day: String(day), page: "1", page_size: "5" });
  const row = hist.json?.data?.list?.[0];
  console.log("\n=== 1830 row ===");
  console.log(JSON.stringify({
    result: row?.result,
    results0: row?.results?.[0],
    gold: gold.json?.data,
  }, null, 2).slice(0, 2500));
}

// 1810 step
{
  const base = "http://127.0.0.1:50053";
  const gold = await post(base + "/cp/goldgame/single_game_user_gold_history", { token: "local-1810", gid: "1810" });
  const day = gold.json?.data?.list?.find((x) => x.bet_gold)?.day || gold.json?.data?.list?.[0]?.day;
  const hist = await post(base + "/cp/goldgame/single_game_user_history", { token: "local-1810", gid: "1810", day: String(day), page: "1", page_size: "2" });
  const row = hist.json?.data?.list?.[0];
  console.log("\n=== 1810 row ===");
  console.log(JSON.stringify({
    result: row?.result,
    results0: row?.results?.[0],
    extend: row?.extend,
  }, null, 2).slice(0, 2500));
}

// 2410 routes + spin + history
{
  const base = "http://127.0.0.1:50051";
  const health = await (await fetch(base + "/health")).text();
  console.log("\n=== 2410 health ===", health);
  const spin = await post(base + "/cp/single_game.Game/gameResult", { token: "hist-2410", gid: "2410", bet: "0.8", level: "1", bet_gold: "0.8" });
  console.log("spin", spin.status, spin.json?.code, spin.json?.msg, Object.keys(spin.json?.data || {}));
  const gold = await post(base + "/cp/goldgame/single_game_user_gold_history", { token: "hist-2410", gid: "2410" });
  console.log("gold", JSON.stringify(gold.json?.data)?.slice(0, 800));
  const hist = await post(base + "/cp/goldgame/single_game_user_history", { token: "hist-2410", gid: "2410", day: String(gold.json?.data?.list?.[0]?.day || ""), page: "1", page_size: "10" });
  console.log("hist list", hist.json?.data?.list?.length, JSON.stringify(hist.json?.data)?.slice(0, 800));
  const alt = await post(base + "/cp/Goldgame/user_game_history", { token: "hist-2410", gid: "2410" });
  console.log("alt", alt.status, JSON.stringify(alt.json)?.slice(0, 800));
}

// 2210 routes
{
  const base = "http://127.0.0.1:50050";
  const health = await (await fetch(base + "/health")).text();
  console.log("\n=== 2210 health ===", health);
  for (const p of [
    "/cp/goldgame/single_game_user_gold_history",
    "/cp/goldgame/single_game_user_history",
    "/cp/Goldgame/user_game_history",
    "/cp/single_game.Game/gameResult",
    "/cp/single_game.Game/initRoom",
    "/goldgame/single_game_user_gold_history",
    "/goldgame/single_game_user_history",
  ]) {
    const r = await post(base + p, { token: "local-replay", gid: "2210", bet_gold: "0.8", level: "1" });
    console.log(p, r.status, r.json?.code, r.json?.msg || r.json?._raw?.slice?.(0, 80));
  }
}
