import fs from "node:fs";
const t = fs.readFileSync(
  "D:/work/hd/cpgame/publish/2110-Bee-Workshop/assets/Game2110/index.1cbca.js",
  "utf8"
);
for (const name of [
  "reqHistory",
  "reqDayHistory",
  "Game2110HistoryView",
  "btn_history",
  "single_game_user_gold_history",
  "single_game_user_history",
  "ShowForm",
]) {
  let i = 0, n = 0;
  while ((i = t.indexOf(name, i)) >= 0 && n < 5) {
    n++;
    console.log("\n====", name, i);
    console.log(t.slice(Math.max(0, i - 80), i + 280));
    i += name.length;
  }
  if (!n) console.log("\n====", name, "NOT FOUND");
}
