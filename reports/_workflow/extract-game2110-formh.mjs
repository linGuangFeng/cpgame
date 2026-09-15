import fs from "node:fs";
const cfg = JSON.parse(
  fs.readFileSync(
    "D:/work/hd/cpgame/publish/2110-Bee-Workshop/assets/Game2110/config.1cbca.json",
    "utf8"
  )
);
const entries = Object.entries(cfg.paths);
const formEntries = entries.filter(([, v]) => String(v[0] || v).includes("Form/Form"));
console.log("form entries", JSON.stringify(formEntries, null, 2));
console.log("uuids sample keys", Object.keys(cfg).slice(0, 20));
if (cfg.uuids) console.log("uuid0", cfg.uuids[0]);
if (cfg.types) console.log("types", cfg.types?.slice?.(0, 5));
// dump config keys
console.log("cfg keys", Object.keys(cfg));
// versions?
if (cfg.versions) console.log("versions keys", Object.keys(cfg.versions).slice(0, 10));
