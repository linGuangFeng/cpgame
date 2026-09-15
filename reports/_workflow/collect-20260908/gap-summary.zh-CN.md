# collect-20260908 缺口摘要

检查时间：2026-09-13（本轮定时监控）。  
两工人心跳均为 `phase=DONE`（abc223 `2026-09-11T16:30:01.875Z`，abc224 `2026-09-11T16:29:50.058Z`），`currentGame=null`，`error=null`。终态心跳不算卡住。本轮**不再拉新采集**，未开浏览器、未重开工人、未启动 Java。

## 结论：无必须重采的采集缺口

| 游戏 | 工人 | 完整局 | 接口目录 | 资源 | 购买桶 |
|---|---|---|---|---|---|
| 16 Jungle Fruit | abc223 | 未中奖 926 / 中奖 200 / scatter 36、mary 164 | 5 条（含 jungle-fruit/config、spin） | READY | 不适用 |
| 52 Cyber GO | abc223 | 1205 / 203 / FREE_SPINS 54 | 4 条（go-cyber/config、spin） | READY | 不适用 |
| 1090 Sharpshooter | abc223 | 1235 / 200 / special_sgt_2 633（配额 50/40/15 已超） | 直播 4 条 `/cp/`；`Shooter/gameResult` 在 fixtures | READY | 本轮不采购买 |
| 45 Rio Carnival | abc224 | 1153 / 205 / FREE_SPINS 48 | 7 条 | READY | 不适用 |
| 56 Crazy Piggy | abc224 | 1068 / 200 / BOOSTER_WHEEL 37（配额 50/40/15 已超） | 8 条，含 `crazy-piggy/config` | READY | 不适用 |
| 2300 Monster Slayer | abc224 | 普通 1164 / 200（不重采） | 7 条，含 `Game/gameResult` | READY | 三桶各 20 局已分存 |

## 2300 购买三桶（不准用普通 special 顶）

目录：`captures/2300-Monster-Slayer/buy-modes/`。本轮复检：每桶 `spin-index.jsonl` 20 行、`rounds/` 20 局。

| 前缀 | MaryLog | PerKeyListt | 请求 type | 完整局 |
|---|---|---|---:|---:|
| `000002300` | `MaryLog:000002300` | `PerKeyListt_0` | 3 | 20 |
| `100002300` | `MaryLog:100002300` | `PerKeyListt_1` | 4 | 20 |
| `200002300` | `MaryLog:200002300` | `PerKeyListt_2` | 5 | 20 |

## 说明（不是重采理由）

- 1090 直播目录 4 条（无 `gameResult`）。`captures/1090-Sharpshooter/api-catalog/from-fixtures.redacted.json` 仍有 `POST /cp/single_game.Shooter/gameResult`。完整局已超配额，不要再打原站 spin。
- 56 目录里的 `rio-carnival/config` 是大厅上一局 iframe 残留，不要当成 Crazy Piggy 协议。
- 2300 普通特殊完整链样本不足不纳入本轮配额；购买三桶已齐。
- 令牌未写入本摘要。

复刻入口：`reports/_workflow/collect-20260908/INDEX.zh-CN.md`。
