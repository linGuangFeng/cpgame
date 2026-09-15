import fs from "node:fs";
import path from "node:path";
const cfg = JSON.parse(
  fs.readFileSync(
    "D:/work/hd/cpgame/publish/2110-Bee-Workshop/assets/Game2110/config.1cbca.json",
    "utf8"
  )
);
for (const idx of [505, 506]) {
  const uuid = cfg.uuids[idx];
  const ver = cfg.versions?.import?.[idx] || cfg.versions?.import?.[String(idx)];
  console.log("idx", idx, "uuid", uuid, "ver", ver, "path", cfg.paths[idx] || cfg.paths[String(idx)]);
  // uuid can be compressed. Try import dir.
  const raw = String(uuid).replace(/[^a-zA-Z0-9]/g, "");
  const prefix = raw.slice(0, 2).toLowerCase();
  const importDir = "D:/work/hd/cpgame/publish/2110-Bee-Workshop/assets/Game2110/import";
  function walk(d, acc = []) {
    for (const n of fs.readdirSync(d)) {
      const p = path.join(d, n);
      const st = fs.statSync(p);
      if (st.isDirectory()) walk(p, acc);
      else acc.push(p);
    }
    return acc;
  }
  const files = walk(importDir);
  const hits = files.filter((f) => f.toLowerCase().includes(raw.slice(0, 8).toLowerCase()) || f.includes(String(uuid).slice(0, 8)));
  console.log("hits", hits.slice(0, 10));
}
console.log("uuid505", cfg.uuids[505]);
console.log("uuid506", cfg.uuids[506]);
console.log("versions.import type", typeof cfg.versions.import, Array.isArray(cfg.versions.import) ? "array len " + cfg.versions.import.length : Object.keys(cfg.versions.import).length);
if (Array.isArray(cfg.versions.import)) {
  console.log("import ver 505", cfg.versions.import[505], cfg.versions.import[506]);
} else {
  console.log("import ver 505", cfg.versions.import[505], cfg.versions.import["505"]);
}
