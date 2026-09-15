import fs from "node:fs";
const files = [
  "D:/work/hd/cpgame/publish/2110-Bee-Workshop/assets/main/index.57fd2.js",
  "D:/work/hd/cpgame/publish/2110-Bee-Workshop/assets/resources/index.92d8c.js",
  "D:/work/hd/cpgame/publish/2110-Bee-Workshop/assets/Game2110/index.1cbca.js",
];
for (const file of files) {
  const t = fs.readFileSync(file, "utf8");
  console.log("\n####", file.split("/").slice(-2).join("/"), "len", t.length);
  for (const name of ["ShowForm=function", "ShowForm:function", "prototype.ShowForm", "OpenHistory", "LoadForm", "formPrefab", "Game2110HistoryView"]) {
    let i = t.indexOf(name);
    if (i < 0) {
      console.log(name, "NOT");
      continue;
    }
    let n = 0;
    while (i >= 0 && n < 2) {
      console.log("\n--", name, i);
      console.log(t.slice(Math.max(0, i - 60), i + 320));
      n++;
      i = t.indexOf(name, i + name.length);
    }
  }
}
