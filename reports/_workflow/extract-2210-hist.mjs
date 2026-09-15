import fs from "node:fs";
const t = fs.readFileSync(
  "D:/work/hd/cpgame/publish/2210-Wu-Kong/assets/Game2210/index.64954.js",
  "utf8"
);
for (const name of [
  "wukong_user",
  "single_game_user",
  "gold_history",
  "user_history",
  "GameDetail",
  "extend.act_id",
  "ShowForm",
]) {
  let i = 0, n = 0;
  while ((i = t.indexOf(name, i)) >= 0 && n < 3) {
    console.log("\n====", name, i);
    console.log(t.slice(Math.max(0, i - 70), i + 220));
    n++;
    i += name.length;
  }
  if (!n) console.log("====", name, "NOT");
}
