import fs from "node:fs";
import path from "node:path";

const ROOT = "D:/work/hd/cpgame";
const CAPTURE = path.join(ROOT, "captures/2300-Monster-Slayer/buy-modes");
const RES = path.join(ROOT, "generator/2300-Monster-Slayer/src/main/resources");
const MODES = [
  [3, "000002300", "buy-3"],
  [4, "100002300", "buy-4"],
  [5, "200002300", "buy-5"],
];

function asInt(v, fallback) {
  if (typeof v === "number" && Number.isFinite(v)) return v;
  const n = parseInt(v, 10);
  return Number.isFinite(n) ? n : fallback;
}

function animals(node) {
  if (!Array.isArray(node)) return [];
  return node.map((a, i) => ({
    bl: asInt(a?.bl, 0),
    iu: asInt(a?.iu, 0),
    t: asInt(a?.t, i + 1),
  }));
}

function locPairs(loc) {
  if (!loc || typeof loc !== "object" || Array.isArray(loc)) return [];
  return Object.entries(loc)
    .map(([k, v]) => [asInt(k, -1), asInt(v, 0)])
    .filter(([c]) => c >= 0)
    .sort((a, b) => a[0] - b[0]);
}

function innerLoc(feature) {
  const pairs = locPairs(feature.loc);
  if (pairs.length) return pairs;
  const roles = feature.r;
  if (!Array.isArray(roles)) return [];
  for (const role of roles) {
    if (!role || typeof role !== "object") continue;
    for (const body of Object.values(role)) {
      if (!body || typeof body !== "object") continue;
      const nested = locPairs(body.f?.loc) || locPairs(body.af);
      if (nested.length) return nested;
    }
  }
  return [];
}

function fixT(node, animalList) {
  if (Array.isArray(node)) {
    return node.map((item, i) => {
      if (item && typeof item === "object" && "t" in item) {
        const fallback = animalList[i]?.t ?? i + 1;
        const copy = { ...item, t: asInt(item.t, fallback) };
        return fixT(copy, animalList);
      }
      return fixT(item, animalList);
    });
  }
  if (node && typeof node === "object") {
    const out = {};
    for (const [k, v] of Object.entries(node)) {
      if (k === "t") out[k] = asInt(v, 1);
      else if (k === "a" && Array.isArray(v)) out[k] = fixT(v, animalList);
      else out[k] = fixT(v, animalList);
    }
    return out;
  }
  return node;
}

function encodeStep(data) {
  const feature = data.f || {};
  const ps = data.res?.ps || [];
  if (ps.length !== 15) throw new Error("board length");
  const board = ps.map((x) => String(asInt(x, 0))).join(",");
  const gt = asInt(feature.gt, 0);
  const nt = asInt(feature.nt, 0);
  const animalList = animals(feature.a);
  const h = Array.isArray(feature.h) ? feature.h.map((x) => asInt(x, 0)).join(",") : "";
  const loc = innerLoc(feature).map(([c, i]) => `${c}:${i}`).join(";");
  const a = animalList.map((x) => `${x.bl}.${x.iu}.${x.t}`).join(";");
  const rb = Array.isArray(feature.rbs) ? feature.rbs.map((x) => asInt(x, 0)).join(",") : "";
  let g = "";
  if (Array.isArray(feature.r) && feature.r.length) {
    const packed = JSON.stringify(fixT(feature.r, animalList));
    if (packed.includes("|") || packed.includes("/")) throw new Error("roles json contains splitter");
    if (![...packed].every((ch) => ch.charCodeAt(0) >= 32 && ch.charCodeAt(0) < 127)) {
      throw new Error("roles json not ascii");
    }
    g = packed;
  }
  return `${gt}.${nt}.${board}|H${h}|L${loc}|A${a}|R${rb}|G${g}`;
}

function loadRound(roundDir) {
  const steps = [];
  for (let i = 1; ; i++) {
    const p = path.join(roundDir, `step-${String(i).padStart(3, "0")}.response.json`);
    if (!fs.existsSync(p)) break;
    const payload = JSON.parse(fs.readFileSync(p, "utf8"));
    steps.push(encodeStep(payload.body.data));
  }
  if (!steps.length) throw new Error(roundDir);
  return steps.join("/");
}

fs.mkdirSync(RES, { recursive: true });
const counts = {};
for (const [type, prefix, name] of MODES) {
  const roundsRoot = path.join(CAPTURE, prefix, "rounds");
  const lines = fs
    .readdirSync(roundsRoot)
    .filter((n) => n.startsWith("round-"))
    .sort((a, b) => Number(a.split("-")[1]) - Number(b.split("-")[1]))
    .map((n) => loadRound(path.join(roundsRoot, n)));
  counts[name] = { rounds: lines.length, bytes: lines.join("\n").length };
  fs.writeFileSync(path.join(RES, `monster-slayer-${name}.txt`), lines.join("\n") + "\n");
}
console.log(JSON.stringify(counts, null, 2));
