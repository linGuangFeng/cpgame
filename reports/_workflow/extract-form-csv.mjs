import fs from "node:fs";
const t = fs.readFileSync(
  "D:/work/hd/cpgame/publish/2110-Bee-Workshop/assets/resources/import/06/0671ab352.10e6d.json",
  "utf8"
);
console.log("len", t.length);
for (const name of ["Game2110HistoryView", "Game2110GameDetailView", "Game2110DayHistoryView", "UIGameHistory", "History"]) {
  let i = 0, n = 0;
  while ((i = t.indexOf(name, i)) >= 0 && n < 3) {
    console.log("\n====", name, i);
    console.log(t.slice(Math.max(0, i - 80), i + 200));
    n++;
    i += name.length;
  }
  if (!n) console.log("====", name, "NOT");
}
