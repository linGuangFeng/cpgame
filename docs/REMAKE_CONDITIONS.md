# 完整复刻条件（REMAKE CONDITIONS）

更新时间：2026-09-07 11:56:40 Asia/Shanghai

> **USER MANDATE 2026-09-07**：Remake is NOT complete without enter-room verification APIs + documentation.

## 总清单 Checklist（全部满足才可 `remakeReady=true`）

1. **REAL multilingual resources**
   - `resources/<gid>-<Name>/` 含真实多语言资源（非占位/非伪造）
   - `REMAKE_READY.json` → `resourcesREAL.ok` 或 `resources.ok` = true
2. **Enter-room API fixtures（进房鉴权/初始化）**
   - 路径：`fixtures/<gid>-<Name>/enter/`
   - 必须来自 **ORIGINAL browser network**（禁止臆造 URL）
   - 常见形态（按游戏族实际捕获，不强制同名）：
     - 旧族：`auth-verify` + `config`（+ 可选 `report-timing`）
     - 新族/v2：`initialData` + `getUserInfo` + `initRoom`（+ 可选 timing）
   - 每个子目录含 `request.json` + `response.json`；`t`/`token`/`btt` 已脱敏
   - `enter/INDEX.md` 列出 URL + purpose
   - `REMAKE_READY.json` → `enterApis=true` **仅在已落盘后**
3. **Spin fixtures**
   - `LOSS200 + WIN200 + special30`（或文档注明 unreachable）
   - `spin/` + `coverage.json` 证据
4. **HIS light**
   - `history-list` + `history-view` 样例齐备
5. **Per-game notes**
   - `fixtures/<gid>-<Name>/REMAKE_CONDITIONS.md`
6. **Docs**
   - 本文件：`docs/REMAKE_CONDITIONS.md`
   - 各游戏 REMAKE_CONDITIONS.md

## 目标 GID（本轮优先）

| gid | Name | enter 族（实测） |
|-----|------|------------------|
| 16 | Jungle Fruit | auth-verify + config |
| 32 | Jungle Treasure | auth-verify + config |
| 45 | Rio Carnival | auth-verify + config |
| 52 | Cyber GO | auth-verify + config |
| 56 | Crazy Piggy | auth-verify + config |
| 61 | Saci | auth-verify + config |
| 1090 | Sharpshooter | initRoom + initialData + getUserInfo |
| 1380 | Hidden Realm | initRoom + initialData + getUserInfo |
| 2010 | EDM Mania | initRoom + initialData + getUserInfo |
| 2060 | Club Goddess | initRoom + initialData + getUserInfo |
| 2110 | Bee Workshop | initRoom + initialData + getUserInfo |
| 2350 | Curupira | initRoom + initialData + getUserInfo |

## Hands-off / Exclude（勿动）

- Hands-off: 33/42/43/54/60/1670/1810/1830/1910/2210/2290/2470/2410
- No resource redo: 41/58；exclude 1809/1407
- Box `:9225` Glacier Treasure(1780) spin/watchdog：勿停
- Mac 1400：已 STOP（见 STOP_NOTE_1400）；勿强行 unblock

## remakeReady 判定

```
remakeReady = resourcesREAL && enterApis && spin.ok && his.ok && docsPresent
```

`enterApis` 必须在 `fixtures/.../enter/` 已保存后才可置 true。
