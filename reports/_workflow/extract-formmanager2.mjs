import fs from "node:fs";
const t = fs.readFileSync(
  "D:/work/hd/cpgame/publish/2110-Bee-Workshop/assets/main/index.57fd2.js",
  "utf8"
);
const names = [
  "Form.csv not find",
  "creatingFormDict",
  "UIGameHistory",
  "customHistory",
  "prototype.ShowForm = function",
];
for (const name of names) {
  let i = 0, n = 0;
  while ((i = t.indexOf(name, i)) >= 0 && n < 3) {
    console.log("\n====", name, i);
    console.log(t.slice(Math.max(0, i - 120), i + 500));
    n++;
    i += name.length;
  }
  if (!n) console.log("\n====", name, "NOT");
}

// FormManager ShowForm around LoadForm
const load = t.indexOf("LoadForm Form.csv not find");
console.log("\n==== around LoadForm", load);
console.log(t.slice(load - 1500, load + 1800));
