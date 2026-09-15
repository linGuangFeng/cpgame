# CP 游戏复刻全流程（给新会话直接跑，不走工作流）

对齐当前 GAME_REPLICATION **v23** 合同，但**不要启动工作流**。  
把第 0 节贴成第一条，只改花括号。若对方读不到本文件，把第 0 节到文末整份贴进去。

不要把接口路径、字段名、语言列表、分桶写成上一款游戏的默认值。  
先看当前游戏真实请求/配置，再对照仓库已有 `captures/`、`resources/` 的落盘格式。  
采样够了或 `paidStarts` 到 3000 就停。稀有奖励在前 1000 个付费局从未出现即视为不存在，不再为凑特殊局卡死。

和旧稿冲突时以本文为准，尤其是：

- Demo **禁止当场出牌**，只从 Redis 缓存取预生成完整局
- 0 倍写入**未中奖池**（不是旧的「0 倍不写」）
- History **轻量**：每种已确认结果各一次，不用 his 凑 200/200/30
- 先资源，再探索规则，再按规则拉完整局
- 购买是不是独立玩法，听本游戏探索，不准预先拆成四套互斥桶

---

## 0. 新会话第一条

```
按 reports/_workflow/cp-game-replication-full-runbook.zh-CN.md 做。
不要启动、不要模仿 agent-ai 的 GAME_REPLICATION 工作流。直接在本仓库执行。

游戏 raw id：{ID}
英文名：{Name}
Mac 目录：{ID}-mac-{Name}
原目录：{ID}-{Name}（已有则只读，禁止改前端和已有 Java）

顺序不能乱：
1. 先拉齐所有语言静态资源（每种语言进主界面，并且 Spin / History / 赔表以及本游戏实际存在的购买、免费、活动、弹窗各走一次）
2. 摊成 publish，静态 HTTP 打开，主 JS 不 404；禁止改资源文件
3. 先读规则说明和真实请求，写清本游戏怎么结束一局、未中奖/中奖/特殊(mali)/购买分别是什么
4. 再按探索结果采完整局：普通未中奖 200、普通中奖 100、每种已确认特殊 30
5. 付费起点最多 3000，到上限无论是否凑齐都停，记 SAMPLE_INSUFFICIENT；稀有奖励在前 1000 个付费局从未出现则记为不存在
6. 落盘格式对齐本仓库已有 captures/resources（先读 1809、1407 和当前游戏目录）；已有脏数据先清再写
7. History 每种已确认结果各一次列表+详情即可，不要为每局 spin 补全量 his
8. 分层抄 generator/1809-mac-Freedom-Day 的职责，盘面自己从抓包分析
9. 完整局预写入 Redis 192.168.10.3:6379 db=15（未中奖也要能抽到）
10. Controller 只从该缓存取结果，禁止当场出牌；原页面功能完整且盘面像抓包再交付

令牌不准明文进 git/命令行/未脱敏 JSON。
```

---

## 1. 你是谁，先记住什么

你是 `/Volumes/hd/cpgame`（Windows：`D:\work\hd\cpgame`）的游戏复刻执行人。  
不启动工作流，不把 JAR 文件名、10 个进程、节点 JSON 形状当成第一验收。

### 1.1 红线（一条即失败）

- 改了原 `{ID}-{Name}` 的 js/css/html/图/音频，或给 publish 打补丁/注入脚本
- Demo 当场出牌、本地随机、读 fixtures/captures/历史响应顶上
- 缓存空还声称试玩完成
- 报告 PASS，页面全是单格小图标 / 没框 / 没金牌 / 可见状态空数组但抓包几乎不空
- Redis member 是 JSON，或以 `{` `[` 开头
- 倍率 Key 带小数
- 权重手填成 8,8,9,9… 或直接抄 1809 的 30 格 / 13 符号 / 空 grids，或抄 1407 的 `gfl` / `totalWin/betAmount`
- 规则没写清就按「输 / 赢 / 特殊 / 购买」四套互斥桶堆样本或写生成器
- 购买在本游戏转出来就是 mali，却拆成两套配额、两套生成
- 明文 token / JWT 进 jsonl、properties、命令行、git
- 运行时第二套 Python/JS 判奖

### 1.2 固定顺序（跳过即失败）

```
资源（语言 + spin/his/其它入口各触发一次）
→ publish 静态验收
→ 探索规则（怎么结束一局、分桶、购买是否就是 mali）
→ 按规则采完整局（LOSS 200 / WIN 100 / 各已确认特殊 30，paidStarts ≤ 3000；稀有奖励 1000 局未出现即不存在）
→ 协议：出现率、空值是否常态、三入口权重、整数倍率
→ 一份 Java：出牌 → 独立 0 倍构造 → ResultUtil → 整局 Factory → Codec → Loader
→ 预写入 Redis 192.168.10.3:6379 db=15（未中奖池 + 普通正倍 + 特殊/mali）
→ Controller 只读缓存；原页面功能完整 + 并排看盘
→ 交付
```

不准：先写 Controller 再写规则；不准：Demo 能转就先交付再补 Redis。

---

## 2. 开工前

### 2.1 身份和目录

目录约定见 `reports/_workflow/workflow-v2.md`。业务身份永远是 raw `{ID}`。  
页面里的 `gid` 从启动 URL / 静态配置自己读，不当目录名，不当 Redis 身份。

| 路径 | 用途 |
|---|---|
| `resources/{ID}-{Name}/` | 原始抓取归档，保留 Host 路径，不可直接上传，禁止改文件内容 |
| `publish/{ID}-{Name}/` | 唯一静态包。根部直接有 `index.html` + `publish-manifest.json`，没有 `_hosts` |
| `captures/{ID}-{Name}/` | 原站 spin / step / 轻量 history 原文 + 索引 |
| `protocol/{ID}-mac-{Name}/` | 本游戏字段、出现率、状态机、handoff |
| `generator/{ID}-mac-{Name}/` | 唯一 Java Core、Factory、Codec、Loader |
| `generator/{ID}-mac-{Name}/dist/` | 一个带依赖 jar + `generator.properties` + 启动脚本 |
| `server-api/{ID}-mac-{Name}/dist/` | `controller.jar` + `demo-controller.properties` |
| `reports/{ID}-mac-{Name}/` | 缺口写真的；`current-status.json` 是唯一有效状态 |

原 `{ID}-{Name}` 下如果已经有 Java / publish：只读。Mac 前缀只为隔离复刻，不要平行维护两套会漂的规则。  
`rulesVersion` / `rulesHash`：HTTP 投影和 Redis Loader 打同一对，对不上即失败。

先扫一遍是否已有 `resources/`、`publish/`、`captures/`、`protocol/`、`generator/`、`server-api/`。  
有则打开看格式，后面按同样形状写。没有则在 `1809-Freedom-Day` 与 `1407-Coin-Master-GO` 最近一次正式采集里**选一套**，禁止混两套格式。

### 2.2 先清脏数据

写新样本前，把当前游戏 captures 里不能当原站证据的清掉或隔离：

- 不完整局（付费开始了，连消/免费没采到结束）
- 本地模拟、fixture、demo-script 冒充 `REAL_PROVIDER`
- 购买/摆牌和自然普通混在一个计数桶
- 索引指到不存在的文件、sha256 对不上
- 明文 token / JWT
- 空的 `spin-index.jsonl` / `round-index.jsonl` 占着位置
- quarantine 里的局被算进 200/200/30

能修索引就修；不能修的挪到 `*-quarantine.jsonl` 或标 `complete:false`，从配额剔除。  
不要删还没判断过的原文 body，除非确认是模拟数据。

---

## 3. 所有国家/语言的静态资源

本阶段**只做资源**，不做 200/200/30。  
「已经能进游戏」不等于资源完成。

### 3.1 语言列表自己查，不要写死

从启动页、`versionconfig`、主配置 JSON、前端语言菜单、已有 `publish-manifest.json` 收集。  
平台别名（例如入口 `pt-br` 实际资源 `pt-pt`）两种都要有入口，资源按实际加载的那套存。

### 3.2 每种语言至少做完这些

1. 用该语言打开官方/静态入口（带对的 `language` / `l`）。
2. 进主界面一次，确保语言图集被请求到。
3. 用 **Spin、History**，以及规则里实际存在的其它入口各触发一次：赔表滚到底、购买、免费、活动、弹窗。这里经常漏隐藏图（1809 赔表漏过 5 张）。
4. 把这次加载到的 js/css/图/音频/字体/配置落到 `resources/…/<locale>/`，**保留原始 Host 路径**（现有做法是 `static.cpgame.io/fixed/…` 这种镜像结构）。

不需要每种语言打 200 局。音效、免费、购买动画一般是共享路径：用一种语言把模式走一遍拉齐共享资源，再复制进各语言目录（或做 `all-languages` 并集）。每种语言仍要自己进一次主界面，否则会缺该语言独有图集。

只进主界面、没走过 spin/his 的，资源不算齐。

### 3.3 怎么下

浏览器只负责真实点击，拿到启动链和**实际出现过的 URL 清单**。  
批量按已观测真实 URL 用程序化 HTTP 下载，校验状态、重定向、哈希。  
禁止猜 URL、禁止模型逐文件下、禁止卡在浏览器下载弹窗。浏览器下载被拒时改程序化下载，不要反复要同类权限。  
用户明确拒绝资源下载本身才停。

令牌落盘必须脱敏。禁止改资源文件内容。

输出：`language-inventory.json`、已触发请求清单、`supportedLanguages.codes`。

---

## 4. 摊到 publish

`publish/{ID}-{Name}/` 根部：

- `index.html`
- `publish-manifest.json`（gameId、languages、entry、sourceArchive、文件数、字节数、哈希、缺失项）
- **没有** `_hosts`，没有查询串目录层

动态 API 不要打进静态包。页面用 `sip` 指自己的服务。

必须对照语言清单以及 spin / his / 赔表 / 购买 / 免费等触发过的请求，确认这些静态文件都在 publish 里。缺的回到第 3 节补抓，**不准自己补改 js/css/html/图/音频**。

先用普通静态 HTTP 打开 `index.html`。壳能出来、主 JS 不 404 再往下。不要靠 Java 路由补静态文件。

---

## 5. 先探索规则，再按规则采完整局

### 5.1 探索（不准写 Java 凑答案）

打开官方局和规则/赔表，看 Network 和真实 JSON。必须先写清：

- 一局怎么合法结束
- 未中奖 / 中奖 / 特殊(mali) / 购买分别是什么
- 购买转出来是不是就是 mali。若是：购买不是独立互斥玩法，后面采集、生成、Demo 都按特殊/mali 处理
- 若购买是摆牌或另一套结果：才单独成类，**不要算进普通 200/200**

接口路径不要预设。常见有两套，以本游戏为准：

- `initRoom` / `gameResult` + gold history
- `auth/verify` + `{slug}/config` + `{slug}/spin` + `log-list` / `log-view`

字段名自己从响应推断（有的看 `wa` / `total_win` / `change_gold`，有的看是否进免费）。不要套死 1809 或 1407。

最小押注从 init/config 读真实档位，不要猜。

### 5.2 配额（硬）

| 桶 | 完整局 |
|---|---|
| 普通未中奖 | 200 |
| 普通中奖 | 100 |
| 每种**已确认**特殊（免费、重触发等；购买是否单列听 5.1） | 各 30 |

- **一局** = 一次付费开始 + 所有后续连消/重转/免费/奖励，直到合法结束。连消页、免费步不是新的付费局。
- `paidStarts`：每点一次付费 +1（含后来丢掉的不完整局）。
- `paidStarts` 到 **3000** 必须停。即使某特殊还没到 30：标记 `SAMPLE_INSUFFICIENT`，带着已有样本继续后面阶段。
- 适用桶都满了提前停，不必打满 3000。
- 稀有奖励在前 **1000** 次付费起点从未出现：标记 `NOT_APPLICABLE_RARE_REWARD_NOT_OBSERVED_WITHIN_1000_PAID_ROUNDS`，视为不存在，不再继续为它采样。

### 5.3 采集循环

```
清脏数据、按探索结果建空配额
paidStarts = 0
while 配额未齐 and paidStarts < 3000:
    打一局并跟到结束（跟不到结束 → 不计入配额，但 paidStarts+1）
    按本游戏规则分类
    该桶未满则收下并写文件/索引
    该桶已满则这条可丢弃或只作额外样本，不要为已满桶继续硬凑
停
写采集摘要：各桶数量、paidStarts、是否 SAMPLE_INSUFFICIENT、特殊缺哪种
```

中途崩溃：从索引 resume，不要把半截局标 `complete`。

### 5.4 History 轻量

每种已确认结果各一次：列表 + 能打开的详情。用来对字段和拉 his 资源。

- 不要为每局 spin 补全量 history
- 不要用 his 去凑 200 LOSS / 100 WIN / 30 特殊
- 进配额的完整局仍应能对上同一订单号/transfer；对不上的进 quarantine，不进配额

### 5.5 落盘格式（对齐仓库已有，不要发明第三套）

先读：

- `captures/1809-Freedom-Day/`（body 文件 + 索引指路径）
- `captures/1407-Coin-Master-GO/`（spin/round/step jsonl + history jsonl）
- 当前游戏 `captures/` 里已经在用的那套

然后沿用同一套：

| 文件 | 内容 |
|---|---|
| `bodies/` 或 `rounds/NNNN/spin-response.json` 等 | 每步完整 HTTP JSON |
| `spin-index.jsonl` | 一行一个付费起点，指向原文，`complete`，来源种类 |
| `round-index.jsonl` | 一行一个完整局，steps 按顺序指向文件 |
| `step-index.jsonl` | 若本游戏按 scene/step 记（1407 有） |
| `history-round-index.jsonl` + 轻量 history 原文 | 每种结果各一次，能对上字段 |
| `source-*-quarantine.jsonl` | 脏数据/对不上的 |

索引里要能分清：自然普通、自然特殊、购买（若单独成类）。要有 sha256 或等价校验。`sourceType` 必须是原站。  
把原文请求/响应存盘，令牌脱敏。

写 `scenario-coverage.json` / `current-status.json.roundSampleCoverage`：

- `ordinaryLossTarget=200`，`ordinaryWinTarget=100`
- `specialTargetPerCategory=30`
- `rareRewardAbsencePaidRoundThreshold=1000`
- `maxPaidRounds=3000`
- `paidRoundStarts`、`completedRoundCount`
- 逐类别 `completeRoundCount` / `status` / `stopReason`

---

## 6. 协议（答不出出现率不准写 Java）

读真实 spin JSON + 前端 JS。写到 `protocol/{ID}-mac-{Name}/`。  
至少还要有 `game-capabilities.json`、`protocol-spec`、`protocol-handoff.json`。

handoff 顶层：`schemaVersion`、`gameId`、`rulesHash`、`evidenceInventory`、`behaviorContracts`、`unresolvedBehaviors`。  
CONFIRMED 行为必须有实现契约和独立验收契约；多步骤必须有相邻步骤原始证据。  
测试预期禁止用实现自己生成的结果当唯一答案。`usesImplementationGeneratedExpected=true` 即失败。

### 6.1 必须能回答（用本游戏字段名，每条带抓包路径或 JS 位置）

- 前端画格子的**全部**字段；名字、类型、长度
- 每个可见字段出现率：`pagesWithField / pagesCounted`
- 空数组/空值是不是原站常态
- 开局 / 连消补牌 / 免费补牌是否同一套分布（分开数，不要假设相同）
- Ways 还是 Payline（或 Cluster/其它）；固定线游戏写 `paylineCount` 和证据，无线玩法标不适用
- 倍率怎么从牌面算成**整数**（必须能 `intValueExact()`）
- 免费怎么触发；购买是不是摆牌、是不是 mali
- 相邻两页幸存格会不会换符号
- 哪些符号不能进某状态（例如开局能不能出 Wild）

出现率分母太小就说样本不够，不要用 `supported=true` 空过。  
不要抄 1809 的 30 格、13 符号，也不要抄 1407 的 `gfl`，除非本游戏抓包就是那样。

计数方法：

- 只计**付费自然局第一页**（或免费 Spin 第一页）。连消续页、购买/摆牌剔除（除非探索结论就是要单独统计购买）
- 权重就是原始计数。概率 = 该符号计数 / 合计。注释写分母和百分比。不宣称原厂 RTP

### 6.2 1809 / 1407 只当对照，证明「统计」长什么样

**1809**

- 画格子：`prop[30]` + `trl[4]` + `grids` + `gf` + `sl`
- 普通第一页 1276×34=43384 格。中间 4 列高度 2–4 合并；银中奖变金、金中奖去框变形
- 普通局 `frees` 是 `false` 不是 `{}`
- Scatter=12，四个起 10 次免费；购买 `bet_type=3` 成本 75 倍总注，是摆牌，不进自然权重、不进自然 Redis 路径

**1407**

- 画格子：25 transport + `gfl`；不在 gfl 的合资格牌必须显式银
- 开局 Wild=0；开局银金 5737:1573；级联补牌 2429 全银
- 倍率 = `pay × ways × rpx` = `totalWin / (betSize × betLevel)`，禁止 `totalWin / betAmount`
- 3 SC → 12 次免费

新游戏先回答：哪几个字段决定像不像，真实样本空不空，生成器会不会按同样频率造出来。

---

## 7. 一份 Java（分层抄职责，不抄盘面）

只在 `generator/{ID}-mac-{Name}` 建标准 Java 工程。  
`server-api` 只能引用同一份 GameRuleCore，禁止新建第二套判奖。  
Python/JS 只能抓取或当原前端静态资源。

按 `generator/1809-mac-Freedom-Day` 分层，名字按游戏改，职责不许混：

| 层 | 1809 对照 | 做什么 |
|---|---|---|
| 出牌 | `FreedomDayBoardGenerator` | 只出当前游戏可见状态，按第 6 节出现率造，原站几乎每页有的字段生成器也必须有 |
| 独立 0 倍 | `FreedomDayIndependentLossGenerator` | Ways：阻断第 3 轴可匹配符号；Payline：逐线阻断。Wild 当万能。触发牌不得超过触发个数。同一 ResultUtil 复核为 0 且不进特殊。10 万张首次 ≥90%。用来**灌未中奖池**，不是给 Demo 运行时调 |
| 判奖 | `FreedomDayResultUtil` | 纯函数。禁止第二套判奖 |
| 整局 | `CompleteRoundFactory` | 一次从付费到连消/免费结束 |
| 极简 | `CompleteRoundCodec` | 只存不能从符号唯一推断的事实；decode 后可见状态还在 |
| Loader | `RedisDirectLoader` | 自然生成完整局，预写入 Redis |
| Demo | Controller | **不调出牌器**。只从 Redis 取 member，用 Codec+ResultUtil 投影协议 JSON |

禁止：

- 抄 1809 的 `prop` 长度 30、符号 1..13、空 `grids`
- 抄 1407 的 `totalWin/betAmount`
- `if (!gfl.contains) silver` 这种 else；互斥状态必须显式分区
- 开局、级联、免费共用一套权重，除非协议已证明同分布
- 运行时读 captures/fixtures/history/demo-script
- 固定牌面骗试玩（测试夹具可以，不能进 `src/main` 正式路径）
- Codec「允许该字段存在」当成生成器已经造出该字段

spin 源数据反推结果要写成文档，并在代码关键处注释（例如某种牌出现概率、最多几个、玛丽触发局是否必定不中奖）。

没有把够试玩的完整局预写入 Redis 之前，不准声称生成器完成。

---

## 8. Redis（Demo 用的缓存，先灌再玩）

### 8.1 连接（硬）

```
redis.host=192.168.10.3
redis.port=6379
redis.database=15
```

启动前 PING。连不上只查网络和配置，打印 `host:port`，**不准当规则 bug 改出牌，不准改成内存出牌**。

无 seed。禁止倍率追逐。禁止 `redis.enabled=false`、`LOCAL_JSONL`、`output.file`、安全拒绝。  
`generator.properties` 每个键都必须被代码读取。权重注释写场景、分子、分母、约百分比、证据来源。

### 8.2 Key（各游戏相同，不许再发明）

```
普通索引  PerKeyList_%09d
特殊索引  MaryKeyList_%09d
普通列表  BetLog:0%08d:%06d
特殊列表  MaryLog:%09d:%06d
```

`%09d` / `%08d` 用 `redis.game-id`（默认 raw `{ID}`）。  
`%06d` 是**整数**倍率。禁止 `0000.1`。

- 未中奖完整局写入未中奖池（普通 0 倍，`BetLog:…:000000`），Demo 抽「不中」时从这里取
- 正倍普通写入 `BetLog` 对应倍率
- 特殊/mali 按**实际结果**进 `MaryLog`（不是按开局入口分类）
- 购买若探索结论就是 mali：进 mali 池，不要另做购买专用自然结果
- 购买若是摆牌且不是自然路径：不进这条 Loader

倍率公式从本游戏赔表推，必须能整除。不能整除就先查规则，不准四舍五入进 Redis。1407 类禁止 `totalWin/betAmount`。

### 8.3 写入

一批 `MULTI/EXEC`：

1. `ZADD` 索引，score=倍率，member=倍率字符串
2. `RPUSH` 列表，member=极简 ASCII
3. `LTRIM key -N -1`，N 默认 300

容量只计**本次进程内存**，不读 Redis 已有长度。满了跳过该倍率。溢出删最旧。

两个开局入口，**每生成 1000 次（含丢弃）换入口**。  
特殊入口：只把开局触发牌权重 ×10，其余与普通入口相同。硬编码，不另加配置。  
写入按实际结果分类。

min/max 倍数：普通默认 1–20000，特殊/mali 默认 100–20000。只丢弃超范围整局，禁止搜倍率、禁止改牌硬凑。  
0 倍由规则自然算出（独立 LOSS 构造器负责稳定灌未中奖池），禁止配置 0 倍概率/开关。

### 8.4 member

极简 ASCII，能还原本游戏可见状态。禁止整份 JSON。不以 `{` `[` 开头。  
不存 `totalWin`、`wa`、余额、session、时间戳、可重算派生字段。

先问「哪些状态不能从符号唯一推断」。那些必须进 member。能推断的不要存。禁止为了短把 grids/gfl/框丢掉。

decode 后用**同一** ResultUtil 重算倍率和连消。对不上整局丢弃。

`dist/` 只交：带依赖 jar + `generator.properties` + 自定位启动脚本（Windows `.cmd` 双击保留窗口，支持 `--no-pause`；Mac/Linux 同等脚本可以一起交）。说明优先中文。

---

## 9. Controller / Demo（只读缓存）

交付：

```
java -jar server-api/{ID}-mac-{Name}/dist/controller.jar --port 5xxxx --config dist/demo-controller.properties --publish publish/{ID}-{Name}
```

- 端口 50000–59999，由启动参数注入，监听 `0.0.0.0`
- `demo-controller.properties`：`controller.contract-version=3`，`controller.jar=dist/controller.jar`
- Maven 无论生成什么名字，打包末尾必须复制成 `controller.jar`
- 禁止写死端口，禁止再派生 Java/Node 子进程
- 禁止改前端。前端要什么字段、什么类型就给什么（普通局原站 `frees:false` 就不要发 `{}`）

### 9.1 取结果（硬）

每次**新的付费局**：

1. 先随机「中」或「不中」
2. 再在对应奖池**已有**倍率中随机一个
3. 取一条完整局 member
4. 后续连消/免费只按 `deliveryIndex` 投影，禁止中途换 member、重新抽倍率、重新出牌

购买若就是 mali：购买从 mali 池取。  
缓存没有预生成数据：直接失败，不准本地随机、fixtures、历史响应顶上。

Init/config/history/余额/续局由 Controller 提供协议壳；**牌面结果只来自缓存 member**。  
GameRuleCore 只用于投影和校验，不用于 Demo 当场随机。

试玩必须功能完整，能进游戏、能转一圈不算：

- 原页面 + 已捕获语言可切
- Init / Config / Spin
- History（轻量，能打开已确认结果的详情）
- 本游戏已确认的购买 / 免费 / 特殊能从头走到结束
- 余额、同一局断线续局

---

## 10. 看盘和测试（JAR 起起来不等于过关）

只截 Get Started / 标题 / 能 Spin = 没验收。

必做：

1. 并排 20 局生成（从缓存取出并投影）vs 20 局抓包，逐项对比**本游戏**画格子字段（类型、是否为空、大致结构）。
2. 生成可见状态出现率相对抓包不能差一个数量级。
3. 用真实抓包 page0→page1 做连消连续性，不用 Factory 输出当唯一预期。
4. 独立 LOSS：10 万张，首次 ≥90%。Ways/Payline 不许混用。
5. 抽一次不中、抽一次中、若有 mali 再走一轮特殊/购买；确认先定中不中再按已有倍率取缓存，连消/免费没有中途换 member。
6. member 非 JSON、整数倍率、decode 后可见状态还在。
7. 特殊入口只放大触发牌；切换间隔 1000。
8. Redis 连不上：日志带 host:port，提示改配置，不改出牌。
9. 每种语言能指出 spin / his / 赔表及其它已确认模式触发过的静态文件在本地。

写 `runtime-generation-validation.json`，证明：

- `demoReadsRedisCache=true`
- `selectsWinOrLossThenMultiplier=true`
- `completeRoundsPreloaded=true`
- `ordinaryLossUsesRuntimeGenerator=false`
- `runtimeDealWhenCacheEmpty=false`

不像就改生成器并重新灌 Redis，不改前端，不准在 Controller 里另做一套出牌。

---

## 11. 交付时交什么

- publish：`index.html` + manifest，哈希对，无 `_hosts`，未改资源
- captures：原文 + 索引 + 摘要（200 LOSS / 100 WIN / 30 特殊、稀有 1000 局未出现即不存在，或 `SAMPLE_INSUFFICIENT` + `paidStarts`）；his 每种结果各一条的位置
- protocol：出现率能指到文件；写清本游戏分桶（购买是否就是 mali）
- generator dist：jar + `generator.properties` + 启动脚本；已预写入 `192.168.10.3:6379 db=15`
- Demo：`controller.jar` + 合同 v3 描述符；原页面功能完整；结果来自缓存
- reports：缺口写真的。未完成不准写成已完成。禁止用 1809/1407 字段名解释当前游戏

---

## 12. 不要卡死

- 稀有奖励摇了 1000 次一局都没有：标记为不存在，不再把它列为采样目标
- 已确认特殊 30 到 3000 还不够：停，记不足，继续协议/复刻，不要死等
- 语言：列表从游戏读；每种进主界面 + spin/his/赔表等入口即可，不要每种语言打满 100
- 接口/字段/分桶：从 Network、规则页和 JSON 推断
- 文件格式：先看本游戏和 1809/1407 已有目录
- 脏数据：先隔离再计数
- Redis 连不上：不是规则 bug
- 资源缺一张隐藏图：回到第 3 节补触发，不准改前端凑
- 盘面不像：改出牌器和权重，重新灌 Redis，不要先改 Controller 文件名

现在开始：先扫当前游戏已有目录和格式，清脏数据，拉齐语言资源；规则没写清之前不要堆 200 局，更不要写 Java。
