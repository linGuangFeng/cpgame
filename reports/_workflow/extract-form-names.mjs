import fs from "node:fs";
const t = fs.readFileSync(
  "D:/work/hd/cpgame/publish/2110-Bee-Workshop/assets/resources/import/06/0671ab352.10e6d.json",
  "utf8"
);
const i = t.indexOf("UIGameHistory");
// The form table is TSV inside a JSON string. Pull a chunk around game forms.
const start = t.lastIndexOf("UIName", i);
const chunk = t.slice(Math.max(0, i - 5000), i + 8000);
const names = [...chunk.matchAll(/\\n([A-Za-z0-9_]+)\\t/g)].map((m) => m[1]);
console.log("names-around-history", names.filter((n) => /2110|Hist|Detail|GameBet|Day/i.test(n)));
const all2110 = [...t.matchAll(/\\n(Game2110[A-Za-z0-9_]+)\\t/g)].map((m) => m[1]);
console.log("all Game2110 forms", all2110);
const allHist = [...t.matchAll(/\\n([A-Za-z0-9_]*[Hh]ist[A-Za-z0-9_]*)\\t/g)].map((m) => m[1]);
console.log("all *hist* forms", allHist);
