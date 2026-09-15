from pathlib import Path

p = Path(r"D:\work\hd\cpgame\reports\2110-Bee-Workshop\js-extract\Game2110DayHistoryItem.js")
t = p.read_text(encoding="utf-8")
for name in ["SetItemData", "OnClick", "ShowForm", "Game2110GameDetailView"]:
    i = 0
    found = 0
    while True:
        i = t.find(name, i)
        if i < 0:
            break
        found += 1
        print("---", name, "at", i)
        print(t[max(0, i - 60) : i + 350])
        print()
        i += len(name)
    if found == 0:
        print("---", name, "NOT FOUND")
