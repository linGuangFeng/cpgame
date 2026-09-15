#!/usr/bin/env python3
"""Build BuyCorpus.java from captured buy-mode complete rounds. Generator-time only."""
from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(r"D:\work\hd\cpgame")
CAPTURE = ROOT / "captures" / "2300-Monster-Slayer" / "buy-modes"
OUT = ROOT / "generator" / "2300-Monster-Slayer" / "src" / "main" / "java" / "com" / "cpgame" / "monsterslayer" / "generator" / "BuyCorpus.java"

MODES = [
    (3, "000002300", "BUY1"),
    (4, "100002300", "BUY2"),
    (5, "200002300", "BUY3"),
]


def loc_pairs(loc):
    if not isinstance(loc, dict):
        return []
    pairs = []
    for k, v in loc.items():
        try:
            pairs.append((int(k), int(v)))
        except (TypeError, ValueError):
            continue
    pairs.sort()
    return pairs


def animals(node):
    out = []
    if not isinstance(node, list):
        return out
    for i, a in enumerate(node):
        if not isinstance(a, dict):
            continue
        bl = int(a.get("bl") or 0)
        iu = int(a.get("iu") or 0)
        raw = a.get("t")
        try:
            t = int(raw)
        except (TypeError, ValueError):
            t = i + 1
        out.append((bl, iu, t))
    return out


def hearts(node):
    if not isinstance(node, list):
        return []
    return [int(x) for x in node]


def rbs(node):
    if not isinstance(node, list):
        return []
    return [int(x) for x in node]


def inner_loc(feature):
    loc = feature.get("loc")
    pairs = loc_pairs(loc)
    if pairs:
        return pairs
    roles = feature.get("r") or []
    if isinstance(roles, list):
        for role in roles:
            if not isinstance(role, dict):
                continue
            for body in role.values():
                if not isinstance(body, dict):
                    continue
                nested = body.get("f") or {}
                pairs = loc_pairs(nested.get("loc"))
                if pairs:
                    return pairs
                pairs = loc_pairs(body.get("af"))
                if pairs:
                    return pairs
    return []


def encode_step(data: dict) -> str:
    feature = data.get("f") or {}
    ps = data.get("res", {}).get("ps") or []
    if len(ps) != 15:
        raise ValueError("board length")
    board = ",".join(str(int(x)) for x in ps)
    gt = int(feature.get("gt") or 0)
    nt = int(feature.get("nt") or 0)
    h = ",".join(str(x) for x in hearts(feature.get("h")))
    loc = ";".join(f"{c}:{i}" for c, i in inner_loc(feature))
    a = ";".join(f"{bl}.{iu}.{t}" for bl, iu, t in animals(feature.get("a")))
    rb = ",".join(str(x) for x in rbs(feature.get("rbs")))
    return f"{gt}.{nt}.{board}|H{h}|L{loc}|A{a}|R{rb}"


def load_round(round_dir: Path) -> str:
    steps = []
    i = 1
    while True:
        path = round_dir / f"step-{i:03d}.response.json"
        if not path.exists():
            break
        payload = json.loads(path.read_text(encoding="utf-8"))
        data = payload["body"]["data"]
        steps.append(encode_step(data))
        i += 1
    if not steps:
        raise ValueError(round_dir)
    return "/".join(steps)


def java_string(s: str) -> str:
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def main() -> None:
    blocks = []
    counts = {}
    for buy_type, prefix, name in MODES:
        lines = []
        rounds_root = CAPTURE / prefix / "rounds"
        for round_dir in sorted(rounds_root.glob("round-*"), key=lambda p: int(p.name.split("-")[1])):
            lines.append(load_round(round_dir))
        counts[name] = len(lines)
        body = ",\n            ".join(java_string(x) for x in lines)
        blocks.append(f"    static final String[] {name} = {{\n            {body}\n    }};")
    text = """package com.cpgame.monsterslayer.generator;

/** Captured buy complete-round templates. Built from buy-modes wire evidence, not runtime fixtures. */
final class BuyCorpus {
""" + "\n".join(blocks) + """
    private BuyCorpus() {}
}
"""
    OUT.write_text(text, encoding="utf-8")
    print("wrote", OUT, counts)


if __name__ == "__main__":
    main()
