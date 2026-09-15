import fs from "node:fs";
const t = fs.readFileSync(
  "D:/work/hd/cpgame/reports/2110-Bee-Workshop/js-extract/Game2110DayHistoryItem.js",
  "utf8"
);
for (const name of ["SetItemData", "OnClick", "ShowForm", "Game2110GameDetailView", "this.data"]) {
  let i = 0;
  let found = 0;
  while ((i = t.indexOf(name, i)) >= 0) {
    found += 1;
    console.log("---", name, "at", i);
    console.log(t.slice(Math.max(0, i - 60), i + 350));
    console.log();
    i += name.length;
  }
  if (!found) console.log("---", name, "NOT FOUND");
}
