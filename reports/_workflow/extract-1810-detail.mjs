import fs from "node:fs";
const t = fs.readFileSync(
  "D:/work/hd/cpgame/publish/1810-Treasure-Hunt/assets/Game1810/index.c4ef8.js",
  "utf8"
);
for (const name of ["GameDetailView", "updateView", "extend.act_id", "results[", ".result.prop", "spe_pos"]) {
  let i = 0, n = 0;
  while ((i = t.indexOf(name, i)) >= 0 && n < 2) {
    console.log("\n====", name, i);
    console.log(t.slice(Math.max(0, i - 50), i + 280));
    n++;
    i += name.length;
  }
  if (!n) console.log("====", name, "NOT");
}
