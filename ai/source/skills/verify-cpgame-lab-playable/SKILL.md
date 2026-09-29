---
name: verify-cpgame-lab-playable
description: >-
  Use when verifying an CPGame remake is actually playable in the lab — required
  before claiming done. Opens /CPGame, clicks enter, checks Demo + 官方参考1, spins.
---

# Verify CPGame lab playability (click, not API-only)

This skill verifies the Demo portion only. Full remake acceptance also requires the Java core and evidence checks in `AGENTS.md`.

## Paths

If the workspace root is `cpgame`, admin is `admin`. Lab URL stays `http://127.0.0.1:8000/`.

## Required outcome

1. Open `http://127.0.0.1:8000/`
2. Click **启动试玩** / **进入游戏** for the target `data-directory`
3. Demo pane reaches main game scene (e.g. `BaseGame`), splash gone, cards visible
4. CPGame only has Demo + official reference1; no duplicate reference2 iframe or add button
5. Perform one spin (HUD SPIN, canvas spin, or game SpinBtn); confirm local spin path (`/api/spin` or equivalent) and/or reel/balance change
6. Save screenshots (e.g. `~/Desktop/nm-verify/` or `reports/{ID}/screenshots/`)
7. 参考1：退出再进入试玩，确认每次重新请求官方试玩链接并加载 iframe；刷新参考1也须重新获取。报告官方页面实际加载结果。接口失败不能回用旧链接，内嵌受限不能冒充成功或换成本地参考。

## Preferred method

Use the current environment's available browser control tool for real clicks. Playwright against Chromium is suitable when available. Target the current game's iframe; Use the actual CP game ID and observed iframe; never copy Night Market selectors.

## Fail conditions (do not mark done)

- Only `POST .../start` returned OK
- Only WebSocket handshake traced
- `connected: false` / endless Loading / blank reels
- Duplicate reference2 is still loaded in the CPGame player
- Official servers used as the local Demo backend

## Report back

Chinese summary + screenshot paths + whether spin count / API spin fired. State any unverified features. The local Demo is not independent official-result evidence; reference2 has been removed.


### 试玩与正式生成链路（硬约束）

遵守《复刻要求》第十三节：试玩配置必须指向 Controller 实际配置，与生成配置分开；Demo 只从 Redis 读正式生成器产生的完整局，使用同一 Java 核心，不得另写玩法、现场造局或由 AI 手工准备中奖结果供验收。生成按游戏规则和符号分布先生成事实再计奖，禁止按目标奖金反推牌面或用采集完整局当模板。交付正常生成运行记录及试玩实际取局 key / 哈希。试玩支持各场景及六档实际押注倍数概率（0、(0,5]、(5,20]、(20,50]、(50,100]、(100,10000]），抽目标后只向下找同场景最近有效桶，可跨档但不得换场景或造局。无数据明确报错。索引整数放大系数按游戏核实或用户指定；生成器倍率上下限配置直接填写最终写入 Redis 的整数缓存倍率，范围过滤、ZSET score 与列表 key 使用同一个整数，禁止配置后再次乘除；核心接口单位不得重复换算。


### 防样本模板假生成（验收硬约束）
遵守根目录《复刻要求》第十五节，覆盖每个普通/免费/转盘等场景：审查正式生成调用链，禁止抽采集局、拼接样本或固定样本中奖组合冒充规则生成；区分有证据的有限规则表与有限样本模板。逐场景建立规则允许/实现支持/配置启用/实际观测的可达范围表；用正式入口做显式小规模审计，按完整事实及结构统计入库前多样性、倍率分布和拒绝原因，分清生成次数、唯一局数、ZSET 倍率数及保留后 LLEN。只有随机调用、很多结果、迁移一致或页面可玩不能判合格；桶少也不能直接判假生成，更不能为凑桶造倍率。样本模板或无依据缩窄分支判 FAIL，证据不足标 PARTIAL / SAMPLE_INSUFFICIENT。报告写入 reports/{ID}/generation-audit.json 或等价文件，并链接协议/游戏规则；历史游戏不因新增规则自动通过。
