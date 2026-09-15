# 游戏复刻固定手册（工作流 × 1809 × 1407）

下一款游戏按这份做。不要再为小数倍率、JSON member、手填权重、空 grids、Controller 先于规则改一轮。

对照三份：

1. **工作流**：`smb://192.168.10.3/agent-ai` 的 `GAME_REPLICATION` v22 + 仓库 `reports/_workflow/workflow-v2.md`
2. **1809**：人工验收通过的 `1809-mac-Freedom-Day`（Demo 像原站；Redis 以当前 Loader 为准）
3. **1407**：人工验收通过的 `1407-mac-Coin-Master-GO` Demo；Redis 后来按 1809 合同改过整数倍率和极简 member

人工通过的只有两个 mac 复刻 Demo。原目录默认只读。Redis 灌库以 1809 当前实现为模板。

---

## 0. 一句话

工作流负责**目录和 Redis 裁剪合同**。  
1809 负责**工程分层和 Redis 写入/还原合同**。  
当前游戏自己负责**可见字段、权重、0 倍构造、member 里多出来的那几字节**。

禁止：把 1809 的 30 格、13 个符号、空 grids 当默认盘面。  
禁止：把 1407 的 `totalWin/betAmount` 当默认倍率。  
禁止：工作流先写 Controller、用 JAR 文件名当验收。

---

## 1. 三份对照（出过的问题都写上）

### 1.1 顺序

| | 工作流实际 | 1809 做成什么样才过 | 1407 |
|---|---|---|---|
| 节点 | capture → publish → protocol → **Controller** → 规则/Redis | 统计可见字段 → Java 出牌+判奖 → Demo 看盘 → Redis | 先走工作流翻车，后补成 1809 顺序 |
| 人先看见什么 | `controller.jar`、200/200/30、JSON 键 | 原页面叠牌、框、连消 | 盘面多轮 v18 才像 |
| 停写条件 | 硬校验文件齐就过 | 盘面不像就停，不准先 Redis | 同左，后补 |

### 1.2 可见状态

| | 工作流 | 1809 | 1407 |
|---|---|---|---|
| 协议写法 | `grids.supported=true` 即可 | 必须写出出现率 | 必须写出银/金出现率 |
| 生成器 | 空数组也算合法 | 中间 4 列几乎每页都有 2–4 格 `grids`，`gf`/`sl` 引用整组 | 开局 Wild=0；合资格牌要么在 `gfl`（金）要么显式银 |
| 典型翻车 | 全单格小图标，报告 PASS | 早期空 grids；后来 JSON member 太长，平台还原丢占格 | `gfl 没有就算银`；开局乱抽 Wild；级联补牌套开局金银比 |

### 1.3 权重

| | 工作流 | 1809 | 1407 |
|---|---|---|---|
| 来源 | 常手填 8,8,9,9… | 1276 个自然付费第一页，43384 格 | 501 个付费起点，12525 格 |
| 入口 | 一套权重打天下 | 普通开局 / 玛丽开局两套计数；级联堆叠高度另有分布 | 开局符号、开局银金、级联补牌（2429/2429 全银）三套 |
| 禁止 | — | 购买/摆牌局不进权重样本 | 开局权重不套到级联补牌 |

### 1.4 Redis

| | 工作流 V2 | 1809 现在 | 1407 现在 |
|---|---|---|---|
| 倍率 | 正倍数按实际写入；禁止 min/max 追逐 | `intValueExact()`，`%06d`；普通 min=1 特殊 min=100 max=20000 | 曾 `totalWin/betAmount` 出 `0.1`；已改 `totalWin/(betSize×betLevel)` |
| 0 倍 | 不写 Redis | 构造器有，Demo 用；Loader `ratio<=0` 跳过 | 自然抽到 0 就跳过；独立构造器没接 Loader |
| member | 极简、不可反推事实 | 68 字符/页：34 符号 + `Z` + 20 占格框 + 补齐；`|` 分 Spin | 40 字符/步：25 符号 + 15 位金牌掩码；`|` 分 BASE/FREE |
| 写入 | 一批 MULTI：ZADD+RPUSH+LTRIM | 同左 | 同左 |
| 入口 | 未写死 | 每 1000 次切换普通/特殊开局；特殊只把 Scatter×10 | 每 1000 次切换；特殊只把 SC×10 |
| 购买 | 易混进生成 | `bet_type=3` 只在 Demo；Redis 自然路径不用摆 4 Scatter | 购买不进 Redis |

### 1.5 Demo 和 Redis 不是同一条用户路径

- **Demo**：Controller 当场出牌，吐完整协议 JSON（1809 的 `prop/grids/gf/sl`）。本机能玩 ≠ Redis 能还原。
- **Redis**：Loader 写极简 member → 平台 LPOP → Codec 还原 → 再投协议。member 太长（JSON）或太短（只留符号、丢掉占格），还原就会坏，Demo 却仍正常。

1809 翻过两次：先空 grids（Demo 就不像）；后 JSON member（Demo 仍像，Redis 还原不像）。

### 1.6 工作流硬校验看什么、不看什么

看：`index.html`、没有 `_hosts`、`controller.jar` 文件名、`generator.properties` 键、RPUSH+LTRIM、200/200/30、handoff JSON 形状。  
不看：随机 20 局 grids/gfl 出现率、空数组是不是原站常态、原页面盘面像不像。

所以工作流 PASS 不能当人工验收。

---

## 2. 目录与身份（所有游戏相同）

```
captures/{ID}-{Name}/                 只读。spin-response / round-index / step-index
publish/{ID}-{Name}/                  原前端。禁止改任何 js/css/html/图/音频
resources/{ID}-{Name}/                原始归档。禁止改
protocol/{ID}-mac-{Name}/             可见字段、出现率、赔表、状态机
generator/{ID}-mac-{Name}/            唯一 Java：出牌、判奖、Factory、Codec、Loader
generator/{ID}-mac-{Name}/dist/       只交 jar + generator.properties + start-redis-loader.cmd
server-api/{ID}-mac-{Name}/           同一 Core 的 Demo Controller
server-api/{ID}-mac-{Name}/dist/      controller.jar + demo-controller.properties
reports/{ID}-mac-{Name}/              缺口必须写真的
```

- 业务身份：raw `gameId={ID}`。Redis `redis.game-id` 默认就是这个，平台要命名空间再改（1809 dist 用过 8001809）。
- 页面 `gid` 只是前端兼容（1809=2260，1407=55），不当目录名、不当 Redis 身份。
- Mac 前缀只为隔离。原 `{ID}-{Name}` 默认只读。不要平行维护两套会漂的规则。
- `rulesVersion` / `rulesHash`：HTTP 和 Redis Loader 打同一对。对不上即失败。

Controller 合同：`java -jar dist/controller.jar --port 5xxxx --config dist/controller.properties --publish publish/{Mac目录}`，端口 50000–59999，监听 `0.0.0.0`。前端要什么类型就给什么类型（1809 普通局 `frees` 是 `false` 不是 `{}`）。

---

## 3. 固定流水线（四道门，跳过即失败）

### 门 A — 证据（不准写 Java）

读不少于 30 个真实付费 `spin-response`（优先 `captures/.../spin-index.jsonl` 指向的文件），再读 publish 里的游戏 JS。

必须写进 `protocol/{Mac目录}/`，每条带抓包路径或 JS 位置：

1. 前端画格子用的**全部**字段。
2. 每个字段在真实页上的出现率、合法取值、空是不是常态、哪些符号不能进。
3. 开局 / 级联补牌 / 免费（或重转）补牌 三套计数。样本分母写死。
4. Ways 还是 Payline。独立 0 倍怎么阻断。
5. 赔表：用 init 的 `prop_odds` 或规则页，不用过期 mock。
6. `frees` / `type` / `m` / `ss` / `gt` 的类型。
7. 相邻页：page0→page1 只允许中奖格按规则消或变；幸存格不许换符号。

**出现率答不出，不准写 Java。** `supported=true` 不算答出。

计数方法（1809/1407 用过的，换游戏照做）：

- 只计**付费自然局第一页**（或免费 Spin 第一页）。连消续页、购买/摆牌局剔除。
- 1809：每页 30 `prop` + 4 `trl` = 34 格。普通 1276 局 → 43384；玛丽 2330 个免费第一页 → 79220。
- 1407：每起点 25 transport 格。501 局 → 12525。银金只数 2–4 轴合资格牌（7310 张：银 5737 金 1573）。级联补牌另数（2429 全银）。
- 权重就是原始计数。概率 = 该符号计数 / 合计。注释里写分母和百分比。不宣称原厂 RTP。

### 门 B — 一份 Java（不准先 Controller）

按 1809 分层建类，名字按游戏改，职责不许混：

| 层 | 1809 | 1407 | 做什么 |
|---|---|---|---|
| 出牌 | `FreedomDayBoardGenerator` | `GameRuleCore` 抽符号 + 银金 | 只出当前游戏可见状态 |
| 0 倍 | `FreedomDayIndependentLossGenerator` | 应有独立构造器（现漏在 Redis 路径） | Ways：阻断第 3 轴可匹配符号；Payline：逐线阻断。Wild 当万能。触发牌不得超过触发个数。同一 ResultUtil 复核 |
| 判奖 | `FreedomDayResultUtil` | `CoinMasterResultUtil` | 纯函数。禁止第二套 Python/JS 判奖 |
| 整局 | `CompleteRoundFactory` | `generateRuntimeRound` | 一次从付费到连消/免费结束 |
| 极简 | `CompleteRoundCodec` | `MinimalFactCodec` | 只存不能反推的事实；decode 后可见状态还在 |
| Demo | `FreedomDayController` | 同 Core 的 API | 当场出牌，吐原协议 |
| Redis | `RedisDirectLoader` | 同名 | 见第 4 节 |

生成器必须按门 A 的出现率造状态。Codec「允许 grids 存在」≠ 生成器造出 grids。

禁止运行时读 captures/fixtures/history/demo-script。禁止固定牌面骗试玩。1407 的 `lossBoard()` 那种写死盘只能当测试夹具，不能当正式 runtime。

### 门 C — Demo 人工看盘（不准先 Redis）

启动 Controller，用原 `publish` 打开。并排 20 局生成 vs 20 局抓包，逐项看：

- 1809：叠牌高度、金银框、连消变形、普通局 `frees:false`、购买另测
- 1407：金牌框、银牌、开局无 Wild、金牌中奖变 Wild、级联补银

只截 Get Started / 能 Spin = 没验收。不像就改生成器，不改前端。

### 门 D — Redis（盘面过了才做）

`dist/start-redis-loader.cmd` 或：

```
java -jar 游戏-redis-loader.jar generator.properties
```

Redis 必须已在 `redis.host:redis.port` 监听。连不上就失败并打印 host:port，不要当规则 bug 改。

Loader 边生成边写。普通/特殊配额写满后退出。

---

## 4. Redis 合同（所有游戏抄 1809，不许再发明）

### 4.1 连接与配置键

```
redis.host / redis.port / redis.username / redis.password
redis.database / redis.ssl
redis.connect-timeout-ms / redis.socket-timeout-ms
redis.game-id
generation.normal-count / generation.special-count     各最多 1e8
generation.batch-size                                  默认 100
generation.max-members-per-multiplier                  默认 300
generation.max-consecutive-wins                        默认 10
generation.normal-min-win-multiplier                   默认 1
generation.normal-max-win-multiplier                   默认 20000
generation.mary-min-win-multiplier 或 special-min      默认 100
generation.mary-max-win-multiplier 或 special-max      默认 20000
generation.symbol.{id 或名}.{normal|mary|base}-weight  抓包计数
```

工作流原文禁止倍率区间。1809/1407 按平台要求加了 min/max：**只丢弃超范围整局，禁止为了凑倍率反复搜牌、禁止改牌硬凑**。

没有 0 倍概率配置。0 倍由规则自然算出，Loader 代码固定不写。

### 4.2 Key

```
普通索引  PerKeyList_%09d
特殊索引  MaryKeyList_%09d
普通列表  BetLog:0%08d:%06d
特殊列表  MaryLog:%09d:%06d
```

`%06d` 是**整数**倍率。禁止 `0000.1`。

倍率怎么算：

- 1809：`pay × ways × ballMultiplier`，`intValueExact()`
- 1407：`totalWin / (betSize × betLevel)` = `pay × ways × rpx`，禁止 `totalWin / betAmount`（betAmount 含 20 线，会出小数）
- 下一款：先看赔表是不是整数 pay，倍率必须能 `intValueExact()`。不能整除就先查规则，不准四舍五入进 Redis

### 4.3 写入

一批 `MULTI/EXEC`：

1. `ZADD` 索引，score=倍率，member=倍率字符串
2. `RPUSH` 列表，member=极简 ASCII
3. `LTRIM key -N -1`，N=`max-members-per-multiplier`

容量只计**本次进程内存**，不读 Redis 已有长度。满了跳过该倍率。溢出删最旧，留最新 N 条。

### 4.4 普通 / 特殊

- 两个开局入口，**每生成 1000 次（含丢弃）换入口**。
- 特殊入口：只把开局触发牌权重 ×10（1809 Scatter 下标 11；1407 `SC`）。其余符号与普通入口相同。硬编码，不另加配置。
- 写入按**实际结果**分类：1809 `spins.size()>1` 进玛丽；1407 `freeStepCount>0` 进玛丽。不是按入口分类。
- 购买/摆 4 Scatter / 摆 3 SC：**不是**这条路径。

### 4.5 member（极简，且能还原）

只存不能从规则反推的事实。不存 `totalWin`、`wa`、`type`、余额、session。

还原后必须还能画出玩家看见的东西：

- **1809**：符号 34 格不够。级联后相邻同符号不一定是同一组，所以每页再加占格/框块。格式：68 字符/页（34 + `Z` + 20 + 补齐到 34 的倍数），Spin 之间 `|`。旧 34 字符仍能解。验收：member 不以 `{` `[` 开头，每段 `length % 34 == 0`，decode 后 `grids/gf/sl` 还在，ResultUtil 倍率一致。
- **1407**：25 格符号 + 15 位金牌掩码（2–4 轴 5 行）= 40 字符/步；Delivery 之间 `|`。银牌 = 合资格且不在 gfl。验收：不以 `{` 开头；rebuild 后 gfl 一致、倍率一致。
- **下一款**：先问「哪些状态不能从符号唯一推断」。那些必须进 member。能推断的不要存。禁止整份 JSON。禁止为了短把可见状态丢掉。

decode 后用**同一** ResultUtil 重算倍率和连消。对不上整局丢弃，不准改牌硬过。

### 4.6 0 倍

- Demo：必须有独立 LOSS 构造器（Ways 堵轴 / Payline 堵线），ResultUtil 复核 0 倍且不触发免费。1809 已有。1407 Redis 路径漏了，下一款不要跟漏。
- Redis：0 倍不写。平台若要输局奖池，再单独开 `BetLog:…:000000`，那是新合同，不要偷偷改「0 倍跳过」。

---

## 5. 测试门（下一款至少这些，禁止只 roundtrip 自己）

1. 配置能加载；未知键拒绝；权重合计为正；WILD/开局禁则按游戏写死。
2. 随机 N 局，可见状态出现率相对抓包不能差一个数量级（1809 grids 接近每页都有）。
3. 用真实抓包的 page0→page1 做连消连续性，不用 Factory 输出当唯一预期。
4. 独立 LOSS：10 万张，首次成功率 ≥ 90%。Ways/Payline 不许混用。
5. Redis Fake：一批里有 ZADD、RPUSH、LTRIM，然后 EXEC。
6. member 极简：不是 JSON；倍率整数；decode 后可见状态和倍率还在。
7. 特殊入口只放大触发牌权重，其余不变；切换间隔 1000。
8. Redis 连不上：日志带 host:port，提示改 `generator.properties`，不当规则失败去改出牌。

预期数字必须能指到 captures 文件或 publish JS。指不到就不是验收。

---

## 6. 红线

- 改原目录或任何前端文件
- Mac 与原版 `index.html` / 主游戏 JS 哈希不一致
- 报告 PASS，页面全是 1 格小图标 / 没框 / 没金牌
- Redis member 是 JSON，或还原后丢掉 grids/gfl
- 倍率带小数
- 权重是 8,8,9,9,10… 或「参考 1809 凑的」
- 运行时读 captures/fixtures
- 购买逻辑写进自然 Redis Loader
- 本机没 Redis 却去改生成器
- 用自己 encode/decode 当唯一「还原验收」，不看协议字段

---

## 7. 下一款开场（整段复制）

```
按 reports/_workflow/game-replication-from-1809-1407.zh-CN.md 做。
不要启动 agent-ai GAME_REPLICATION。
游戏ID：{ID}
Mac 目录：{ID}-mac-{英文名}
原目录：{ID}-{英文名}（只读）
前端不能动。分层抄 generator/1809-mac-Freedom-Day，盘面不要抄。
Redis 合同抄手册第 4 节。
先统计可见字段出现率，答不出不准写 Java。
先 Demo 看盘，不像不准写 Redis。
```

---

## 8. 填空示例（证明「统计」长什么样）

### 1809

- 画格子：`prop[30]` + `trl[4]` + `grids` + `gf` + `sl`
- 普通第一页 1276×34=43384 格。Ball 466，Scatter 754，Wild 200，其余约 9.3%–10.2%
- 玛丽第一页 2330×34=79220。Scatter 1573，Wild 482
- 购买局、4 Scatter 摆牌：不进上述样本，也不进 Redis 自然路径
- 独立 LOSS：Ways，堵第 3 轴（含 trl[1]）；Scatter<4

### 1407

- 画格子：25 transport + `gfl`；非 gfl 合资格牌必须银
- 开局 501×25=12525。WILD=0。SC=316
- 开局银金 5737:1573。级联补牌 2429 全银
- 倍率 = pay×ways×rpx，不是 win/ba
- 3 SC → 12 免费，+1 SC → +2
