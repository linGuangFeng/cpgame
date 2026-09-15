# 旧诊断核对（资源/样本）

旧诊断不是最新结论。按下盘现状处理。

| 游戏 | 旧诊断 | 现状 | 动作 |
|---|---|---|---|
| 52 Cyber GO | 缺原始 index.html | 各语言 `resources/52-Cyber-GO/*/static.cpgame.io/52/index.html` 已有（215789B）；spin-index 1462 | **跳过** |
| 2300 Monster Slayer | 生成器拼盘随机、缺 gt=4、购买证据不全 | 购买三桶各 20（type 3/4/5）；gt=4 已在 `buy-modes/100002300/rounds/round-007` 出现；index.html 已归档。生成器规则不属资源/样本 | **跳过生成器**；购买/gt=4/入口已齐 |
| 1380 Hidden Realm | 缺完整 Spin、入口、History detail | 入口 HTML 已齐；Spin 900 已停；History `day=1788825600` page1 30 条非空明细（oid/props） | **已补** |
| 1780 Glacier Treasure | 缺完整 Round 原始归档 | 入口 HTML 已齐；Spin 900 已停（LOSS 725 / WIN 122）；盘上 25 段完整免费；History 日明细 30 条 | **已补** |
| 1940 Beach Fun | 缺入口 HTML 与运行时静态 | 入口 HTML 已拉并复制到各语言树；旧报告 9 个 404 懒加载资源已从 `static.cpgame.io/v2/1940/` 拉齐写入 resources + publish | **资源已补**；样本按旧诊断已有 1865 round 则跳过 |
| 2060 Club Goddess | 缺免费懒加载 10 个 native | 已从 `static.cpgame.io/v2/2060/assets/Game2060/native/` 拉齐并写入 resources + publish | **已补** |
| 2110 Bee Workshop | 中奖 101/200、完整免费 1/30 | 入口 HTML 已齐；WIN 120 / completeSpecial 252 / paid 1929 已停；History 日明细 30 条 | **已补** |
