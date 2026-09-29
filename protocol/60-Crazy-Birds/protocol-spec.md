# Crazy Birds（60）协议与状态机规格

- ID-name：`60-Crazy-Birds`
- 当前前端版本：`v1.5.10.250430`
- 规则哈希：`sha256:60crazybirds-v1510250430-weighted-b1`
- 能力来源：[game-capabilities.json](game-capabilities.json)
- 行为交接：[protocol-handoff.json](protocol-handoff.json)

规则只来自当前游戏赔表、帮助、`GameGlobalConfig`/`GameDataCommon` 和 abc225 原站抓包。

## 1. 入口与请求顺序

1. 大厅启动 `/60/?ai&btt&gid=60&l&language&sip=api.omgapibra.com&t`
2. `POST /cp/api/v1/auth/verify`（表单 `ai,btt,t,gid`）
3. `POST /cp/api/v1/crazy-birds/config`（表单 `t,gid`）
4. `POST /cp/api/v1/crazy-birds/spin`（表单 `bl,bs,t,gid`）
5. History：`log-list` 再 `log-view`

编码：`application/x-www-form-urlencoded`。成功封装 `{code:200,info:"ok",data:{...}}`。`web-token`/`game-id` 写入表单 `t`/`gid`。

## 2. Config

| 字段 | 当前值 |
|---|---|
| auto | `[10,30,50,100,500]` |
| bll | `1..10` |
| bsl | `[1,5,50]` |
| dbs | `1` |
| dbl | `50`（不在 bll，前端回退索引 0） |
| cc/cs | `BRL` / `R$` |
| spl | 见下 |
| last | 最近一局快照，含 `rskl,ss,fsn,nfsc,wmkl,wskl,pxl` |

最低押注 `bl=1, bs=1`，`ba=1`。

## 3. 牌面与中奖

6 轴 × 4 行，`rskl` 长度 24，reel-major：`index=reel*4+row`。坐标 `reel*10+row`（如 `13`）。4096 ways。

符号：`9,10,J,Q,K,A,S5,S4,S3,S2,S1,WILD,WILDX2,WILDX3,WILDX5,SC`。

左到右相邻轴 ways：S1–S5 至少 2 轴，低分符号至少 3 轴。ways = 各轴命中格数之积。WILD 系列替代普通符号。赔付：

`spl[symbol][reelCount] * ba * ways * Π(pxl on cells)`

`pxl` 为坐标到倍率的对象，来自 `WILDX2/3/5`。

Scatter：至少 3 个不同轴出现 `SC` 触发免费；3/4/5/6 个 Scatter 轴分别奖励 8/15/25/40 次。免费 Step 的 `gt=2`、`small_game_type=2`。`ss=0` 表示同 Round 还有后续 Step；`ss=1` 且 `fsn==nfsc` 或 `fsn=0` 为终局。免费中再次触发是本地策略 `DISABLED_BY_DEFAULT_POLICY`，不冒充原站规则事实。

## 4. Spin 字段

`ba,fsn,gt,nfsc,pb,pxl,rskl,rwa,small_game_type,ss,wa,wmkl,wskl`

`wmkl` 是 ways 分组数组，`wskl[i]` 是对应符号。

## 5. 生成规则

- Java 核心只接收完整牌面事实并计奖，不读取样本、不使用目标奖金反推牌面。生成器按显式基础权重逐格生成，约束为首轴禁 Wild、每轴最多一个 Scatter、每轴最多一个 Wild；付费阶段只允许普通 `WILD`，免费阶段只允许 `WILDX2/3/5`。
- 基础权重来自 `captures/60-Crazy-Birds/spin-index.jsonl` 的经验计数：付费阶段 961 屏/23064 格，免费阶段 433 屏/10392 格。它们不是官方 reel strip、概率或 RTP 证据。
- 正式默认尝试数 `100000000`、每批 `1000`。批次按“中性 -> 依 `weights.symbol-order` 逐牌单独放大 -> 中性”循环；每次只放大一张牌，默认各牌放大 3 倍，不累计。
- 候选先生成事实，再计奖、分类和过滤。倍率越界或免费中再次触发均计入尝试且不补足；禁止样本完整局模板、中奖组合模板和现场造局。

## 6. Redis Demo

Redis 连接只以 `generator/60-Crazy-Birds/dist/generator.properties` 为准。Demo 包括 0 倍在内只领取预生成完整局 ASCII member；空池返回 503，不回退到 Java 现场生成。

保持游戏 60 原有缓存映射：`redis.game-id=8000060`；普通完整局使用 `PerKeyList_008000060` 与 `BetLog:008000060:<6位倍率>`，免费完整局使用 `MaryKeyList_008000060` 与 `MaryLog:008000060:<6位倍率>`。核心只换算一次 `缓存倍率整数 = 整局实际倍数 ×100`；因此赔付表最低 `0.25` 倍得到 `25`，`1.25` 倍得到 `125`。生成器上下限直接填写缓存整数并直接用于过滤；倍率范围与缓存家族、类型和键名无关。当前用户配置为普通 `0..25000`、免费 `500..30000`，以后以 `generator.properties` 的整数值为准，禁止填写 `0.25` 或再次乘除。`0` 倍没有全局放行特例：当前普通范围包含 `0` 因而可入库，免费范围从 `500` 开始因而免费 `0` 倍必须拒绝。

member 不带任何固定前缀、版本号或 rulesHash。每个 Step 只用 24 个单字符保存完整牌面；普通局示例为 `47657F105465785215146159`，免费多 Step 仅用一个 `|` 分隔相邻牌面。旧 `CB60A1`、`CB60B1;rulesHash;...` 均拒绝。新版本不兼容旧缓存，部署时必须清空旧键并用新版 Loader 重新生成，且 Generator 与 Controller 必须成对更新。

Controller 先按普通/免费场景权重，再按六档实际倍率 `0、(0,5]、(5,20]、(20,50]、(50,100]、(100,10000]` 选择目标，只能在同一场景、同一档内向下寻找非空桶，禁止跨档或跨场景兜底。
