# collect-20260908 原站采集 → 复刻入口

本轮只做原站资源 / 接口目录 / 已有完整局盘点，**没有**启动 Java、生成器、Controller、Redis。  
复刻会话不要从聊天记录里的 `static.cpgame.io` 直链进游戏，大厅只用 `https://hms-paddle.com/`。

把下面「新会话第一条」贴给复刻执行人，只改花括号。完整流程：`reports/_workflow/cp-game-replication-full-runbook.zh-CN.md`。

## 分游戏说明（复刻先读这个）

| raw id | 目录 | 本轮状态 | 说明文档 |
|---:|---|---|---|
| 16 | `16-Jungle-Fruit` | 完整局已够；本轮补齐 UI + 接口目录 | `reports/16-Jungle-Fruit/collect-20260908-handoff.zh-CN.md` |
| 52 | `52-Cyber-GO` | 完整局已够；本轮补齐 UI + 接口目录 | `reports/52-Cyber-GO/collect-20260908-handoff.zh-CN.md` |
| 1090 | `1090-Sharpshooter` | 完整局已够；本轮补齐 UI + 接口目录 | `reports/1090-Sharpshooter/collect-20260908-handoff.zh-CN.md` |
| 45 | `45-Rio-Carnival` | 完整局已够；本轮补齐 UI + 接口目录 | `reports/45-Rio-Carnival/collect-20260908-handoff.zh-CN.md` |
| 56 | `56-Crazy-Piggy` | 完整局已够；本轮补齐 UI + 接口目录 | `reports/56-Crazy-Piggy/collect-20260908-handoff.zh-CN.md` |
| 2300 | `2300-Monster-Slayer` | 普通局已够；购买三桶已按观测 type 分桶存好 | `reports/2300-Monster-Slayer/collect-20260908-handoff.zh-CN.md` |
| 1380 | `1380-Hidden-Realm` | Spin 900 已停；History 日明细 30 条 | `reports/1380-Hidden-Realm/collect-20260908-handoff.zh-CN.md` |
| 1780 | `1780-Glacier-Treasure` | Spin 900 已停；History 日明细 30 条 | `reports/1780-Glacier-Treasure/collect-20260908-handoff.zh-CN.md` |
| 2110 | `2110-Bee-Workshop` | 中奖 120 已够；History 日明细 30 条 | `reports/2110-Bee-Workshop/collect-20260908-handoff.zh-CN.md` |

机器可读索引：`reports/_workflow/collect-20260908/game-handoff-index.json`。

## 新会话第一条（模板）

```
按 reports/_workflow/cp-game-replication-full-runbook.zh-CN.md 做。
不要启动、不要模仿 agent-ai 的 GAME_REPLICATION 工作流。直接在本仓库执行。

先读 reports/{DIR}/collect-20260908-handoff.zh-CN.md，再读 origin-collection-20260908.json。

游戏 raw id：{ID}
英文名：{Name}
Mac 目录：{ID}-mac-{Name}
原目录：{ID}-{Name}（已有则只读，禁止改前端和已有 Java）
```

## 本轮约束（复刻也要守）

- 已有完整局达标的，**不要重采普通 spin**。
- 令牌不准进 git / 命令行 / 未脱敏 JSON。
- 2300 购买三种模式必须分开存（`MaryLog:000002300` / `100002300` / `200002300` 以及 `PerKeyListt_0` / `PerKeyListt_1` / `PerKeyListt_2`），**不准用普通 special 顶购买桶**。本轮已观测购买 `type=3/4/5`，三桶各 20 局已写入 `captures/2300-Monster-Slayer/buy-modes/`。
- 56 的接口目录里曾混入 `rio-carnival/config`，那是大厅里上一局 iframe 残留，不要当成 Crazy Piggy 协议。
- 缺口摘要：`reports/_workflow/collect-20260908/gap-summary.zh-CN.md`。本轮不再拉新采集。
