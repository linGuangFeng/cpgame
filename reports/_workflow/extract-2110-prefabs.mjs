import fs from "node:fs";
const cfg = JSON.parse(
  fs.readFileSync(
    "D:/work/hd/cpgame/publish/2110-Bee-Workshop/assets/Game2110/config.1cbca.json",
    "utf8"
  )
);
const paths = Object.values(cfg.paths || {}).map((x) => (Array.isArray(x) ? x[0] : x));
const hits = paths.filter((p) => /hist|detail|record|bet_/i.test(String(p)));
console.log("hist-like", hits);
console.log("prefab-like", paths.filter((p) => /prefab|view|form/i.test(String(p))).slice(0, 80));

const t = fs.readFileSync(
  "D:/work/hd/cpgame/publish/2110-Bee-Workshop/assets/Game2110/index.1cbca.js",
  "utf8"
);
for (const name of ["RegisterForm", "form[", "FormPath", "bundleName", "AddForm", "GetTableDict"]) {
  const i = t.indexOf(name);
  console.log(name, i < 0 ? "NOT" : t.slice(Math.max(0, i - 40), i + 180));
}

// Game2110 click history
const i = t.indexOf('ShowForm("Game2110HistoryView")');
console.log("\nclick hist", t.slice(i - 200, i + 120));
