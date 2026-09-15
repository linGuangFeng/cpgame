import fs from "node:fs";
const t = fs.readFileSync(
  "D:/work/hd/cpgame/publish/2110-Bee-Workshop/assets/resources/import/06/0671ab352.10e6d.json",
  "utf8"
);
for (const name of [
  "Game2110GameDetailView",
  "Game2110DayHistory",
  "Game2110History",
  "prefab/Game2110",
  "Game2110HelpView",
  "Game2110Loading",
  "FormH",
  "Form/FormH",
]) {
  const i = t.indexOf(name);
  console.log(name, i, i >= 0 ? t.slice(i - 40, i + 120).replace(/\n/g, "\\n") : "");
}

const t2 = fs.readFileSync(
  "D:/work/hd/cpgame/publish/2110-Bee-Workshop/assets/Game2110/config.1cbca.json",
  "utf8"
);
console.log("\nconfig FormH", t2.indexOf("Form/FormH"));
const m = t2.match(/Form\/Form[HV][^"]*/g);
console.log("form files", m);
