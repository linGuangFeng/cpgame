# 61-Saci 协议与状态机规格（2026-09-10 按 3369 HTTP Step / 1779 完整局重核）

- 游戏 ID：`61` 名称：`Saci` 目录：`61-Saci`
- 入口版本：`v1.5.10.250821`
- rulesHash：`saci-v2-656af5aa-rebuild-20260910`
- API：`POST /cp/api/v1/auth/verify`，`/d-saci/config`，`/d-saci/spin`，`/d-saci/log-list`，`/d-saci/log-view`
- 表单 `application/x-www-form-urlencoded`；响应 `{code,data,info}`，成功 `code=200`
- 付费 `ba = bl * bs * 20`。最小注 `bl=1, bs=0.02, ba=0.4`

## 牌面

- 5 轴 × 3 行，列优先 `index = reel*3 + row`
- `rskl` 三字符 `CSm`：份数、符号、倍率；四字符及以上为 `CS` + 十进制倍率（免费/漩涡宝箱 10+）
- 普通 `1..8`，Wild `9`，Scatter `a`
- 付费起点 Scatter 只出现 `{0,1,3}`，单轴最多 1；Wild 整盘最多 2
- `syxl`：分裂格坐标 `reel*10+row`；`0` 表示坐标 `00`
- `wskl`：免费触发时 Scatter 所在轴号，贯穿免费全程

## Ways 与赔付（3368/3368 Step 独立复核通过）

- 从左连续匹配，普通至少 3 轴，Wild 自身奖 5 轴
- Wild 代普通不代 Scatter；路径上必须有至少一个非 Wild 的目标符号
- `copyWays` = 各轴匹配格 `copies` 之积
- 宝箱倍率：路径上 `mult>1` 的格子求和 `copies*mult`，没有则为 1
- `wa = bs * bl * payout * copyWays * cellFactor`（**不含** 20，20 已在 `ba` 里）

## Round 边界

完整 Round：一次 `ba>0` 的付费起点，直到 `ss=1 && fsn==nfsc && rsn==nrsc`。

| 模式 | 样本 | 要点 |
|---|---|---|
| 普通未中奖 | 1533 | 单 Step，`sgt=0,ss=1,wa=0,rwa=0` |
| 普通中奖连消 | 188 | 首 Step `sgt=0,ss=0`；中段 `ba=0,sgt=1`；终局 `wa=0,wmkl=[],ss=1` 一次性把 `rwa` 计入 `pb` |
| 免费 | 47 | 3 Scatter → `fsn=10`；触发 `gt/gm=1/1,sgt=2,ss=1`；免费 Delivery `gt/gm=2/2`；`afnl` 加次数；终局一次贷 `rwa` |
| 漩涡 | 11（SAMPLE_INSUFFICIENT，付费 1780<3000） | 能量到 6 后召唤；宣告 `rsn=3,nrsc=0,wn=0` 先贷连消 `rwa`；随后 `gt/gm=2/3` 三次漩涡可连消；`rsn==nrsc && ss=1` 再贷漩涡 `rwa`。运行时按 2290 同款：当前 `wn` 进 `ResultUtil.applyEnergyTransition`，普通局与漩涡尾部分开取 Redis，触发后拼成一局；三次漩涡是缓存新牌，宣告盘沿用连消结束盘 |

购买 `bm` 未捕获任何结果，实现拒绝。

## History

- `log-list.ll` 一行一个付费 Round，`gt=61`
- `log-view`：`bsl`=`gm=1`，`fsl`=`gm=2`，`rsl`=`gm=3`；仅 `bsl[0].ba` 为付费

## Redis

- Host `192.168.10.3:6379` db `15`
- `PerKeyList_000000061` / `BetLog:000000061:<百分之一倍率>`
- `MaryKeyList_000000061` / `MaryLog:000000061:<百分之一倍率>`
- member：`SACIA1;<rulesHash>;<step>~...` US-ASCII，禁止 JSON
- Demo 只 `LINDEX` 预生成完整局，禁止当场出牌
