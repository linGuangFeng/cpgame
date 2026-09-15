import json
import urllib.parse
import urllib.request

BASE = "http://127.0.0.1:50043"
TOKEN = "demo"


def post(url, data):
    body = urllib.parse.urlencode(data).encode()
    req = urllib.request.Request(url, data=body, method="POST")
    with urllib.request.urlopen(req, timeout=15) as r:
        return r.status, json.loads(r.read().decode())


print("HEALTH")
with urllib.request.urlopen(BASE + "/health", timeout=5) as r:
    print(r.status, r.read().decode()[:500])

print("\nGOLD")
st, gold = post(
    BASE + "/cp/goldgame/single_game_user_gold_history",
    {"token": TOKEN, "gid": "2110"},
)
print("status", st)
print(json.dumps(gold, ensure_ascii=False)[:2500])

print("\nDAY")
day = (gold.get("data", {}).get("list") or [{}])[0].get("day", "")
st, hist = post(
    BASE + "/cp/goldgame/single_game_user_history",
    {"token": TOKEN, "gid": "2110", "day": str(day), "page": "1", "page_size": "30"},
)
print("status", st)
data = hist.get("data") or {}
lst = data.get("list") or []
print("list_len", len(lst), "stats", data.get("statistics"))
if not lst:
    print("EMPTY_LIST")
    print(json.dumps(hist, ensure_ascii=False)[:2000])
else:
    row = lst[0]
    print("parent_keys", sorted(row.keys()))
    print("extend", row.get("extend"))
    print("spe_pos", row.get("spe_pos"), type(row.get("spe_pos")).__name__)
    result = row.get("result")
    print(
        "result_type",
        type(result).__name__,
        "result_keys",
        list(result.keys()) if isinstance(result, dict) else result,
    )
    print("results_len", len(row.get("results") or []))
    if row.get("results"):
        s0 = row["results"][0]
        print("step0_keys", sorted(s0.keys()))
        print("step0.extend", s0.get("extend"))
        print("step0.spe_pos", s0.get("spe_pos"), type(s0.get("spe_pos")).__name__)
        r0 = s0.get("result")
        print(
            "step0.result",
            type(r0).__name__,
            list(r0.keys()) if isinstance(r0, dict) else r0,
        )
        print("step0.frees", s0.get("frees"))
        print("step0.type", s0.get("type"))
        print(
            "parent.bet",
            row.get("bet"),
            "level",
            row.get("level"),
            "end_gold",
            row.get("end_gold"),
            "order_id",
            row.get("order_id"),
        )
    print("FULL_ROW")
    print(json.dumps(row, ensure_ascii=False)[:5000])
