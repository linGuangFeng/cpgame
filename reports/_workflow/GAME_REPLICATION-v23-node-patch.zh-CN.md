# GAME_REPLICATION 工作流修改说明（v22 → v23）

改 `smb://192.168.10.3/agent-ai` 里的模板 `WorkflowTemplateCatalog.GAME_REPLICATION`（当前 v22），以及硬校验 `GameArtifactLayoutService` / `protocolHandoffProblems`。  
依据：1809-mac、1407-mac 人工看盘能过，工作流按原顺序会出空 grids / 银金 else / 小数倍率 / JSON member。

目录约定仍用 `reports/_workflow/workflow-v2.md`。玩法和 Redis 合同以 `reports/_workflow/game-replication-from-1809-1407.zh-CN.md` 为准。

---

## 0. 现在错在哪（改之前先对齐）

现行节点：

```
capture → publish_package → publish_review
→ protocol_analysis → protocol_review
→ replica_implementation      ← 先写 Controller
→ result_engine               ← 后写 Core / 生成器 / Redis
→ standalone_test → acceptance ⇄ defect_repair → delivery
```

`result_engine` 还拼 `CPGAME_RESULT_ENGINE_POLICY`（17 条）：前 11 条几乎全是 Redis/JAR，盘面观感在第 12 条。

硬校验 `GameArtifactLayoutService.finalComplianceProblems` 卡文件名和 JSON 键，不卡出现率、不卡原页面像不像。

所以模型会先把 `controller.jar`、200/200/30、RPUSH+LTRIM 堆齐，盘面空着也能 PASS。

---

## 1. 节点顺序：整段替换

**改后：**

```
capture → publish_package → publish_review
→ protocol_analysis → protocol_review
→ result_engine                 ← 先出牌+判奖+整局+独立 LOSS，禁止写 Redis
→ demo_visual_gate              ← 新增。原页面看盘，人工或强制并排 20 局
→ replica_implementation        ← 同一 Core 接 Controller
→ redis_loader                  ← 从 result_engine 拆出，Demo 过了才能进
→ standalone_test → acceptance ⇄ defect_repair → delivery
```

| 节点 | 现行 | 改成 |
|---|---|---|
| `replica_implementation` | 按「已验收协议 **和 GameRuleCore**」写 Controller，但 Core 还不存在 | 移到 `result_engine` **和** `demo_visual_gate` 之后。本节点禁止新建规则类，只能调已有 Core |
| `result_engine` | Core + 生成器 + Redis 一次做完 | **禁止写 Redis、禁止 shade Loader JAR、禁止连 Redis**。只交付 BoardGenerator / ResultUtil / Factory / 独立 LOSS / Codec（Codec 可先不接 Redis） |
| `demo_visual_gate` | 无 | **新增**，卡住 Redis |
| `redis_loader` | 无（埋在 result_engine） | **新增**。合同抄 1809 Loader，盘面合同抄当前游戏 |

`capture` / `publish_*` / `protocol_*` 保留，只改完成条件（第 2 节）。

`maxConcurrentExecutions`、Mac 目录、`gameId=raw ID` 不变。

---

## 2. 每个节点：完成条件 / 失败条件

### 2.1 `capture`（改完成条件，不是取消 200/200/30）

**保留：** 普通输 200、普通赢 100、每种已确认特殊 30、最多 3000 付费起点；稀有奖励在 1000 个付费局未出现即视为不存在；一局=付费 Spin+全部后续 Step。

**增加，缺一不可离开本节点：**

- `spin-index.jsonl` / `round-index.jsonl` 指向真实 `spin-response` 文件，能打开 JSON。
- 至少 30 个自然付费第一页（排除购买/摆牌）可逐格计数。
- 若有连消：至少 10 对相邻页 page0→page1。
- 若有免费：至少 30 个免费 Spin 第一页（可与特殊 30 重叠）。

**失败：** 只有局数、没有可打开的第一页 JSON。不够则 `SAMPLE_INSUFFICIENT_CONTINUE_WORKFLOW`，不准用「保留现有抓取、只补验收指出的缺口」跳过。

### 2.2 `protocol_analysis` / `protocol_review`

**增加必填表（写进 `game-capabilities.json`，不是 optional）：**

| 字段 | 必须有 |
|---|---|
| 前端画格子字段列表 | 名字、类型、长度 |
| 每个可见字段出现率 | `pagesWithField / pagesCounted`，带抓包路径 |
| 空值是不是原站常态 | true/false |
| 开局 / 级联补牌 / 免费补牌 三套计数 | 分母+各符号（或银金）计数 |
| 玩法 | WAYS 或 PAYLINE |
| 倍率公式 | 必须能 `intValueExact()`，写清分子分母 |
| 触发牌 | 符号、几个触发、买不买、买是否摆牌 |

**`protocolHandoffProblems` 增加硬失败：**

- `visibleStateRates` 缺失或分母 &lt; 30
- `mergedSymbols.supported=true` 但未给出现率
- `usesImplementationGeneratedExpected=true`
- 三入口权重共用一行且未声明「已证明同分布」

**删掉：** 「有 `boardModel.mergedSymbols.supported` 键即可」。键在、率为 0、生成全空，必须失败。

**禁止离开 protocol_review：** 出现率答不出。不准写 Java。

### 2.3 `result_engine`（削权）

人设第一句改成：按 `protocol` 的出现率生成可见状态；同一 ResultUtil 判奖；一次生成完整局。

**本节点禁止：**

- 写 Redis、连 `redis.host`、shade `*-redis-loader.jar`
- `LOCAL_JSONL` 当正式交付（本来就禁，保持）
- 抄 1809 的 `prop` 长度 30、符号 1..13、空 `grids`
- 抄 1407 的 `totalWin/betAmount`
- `if (!gfl.contains) silver` 这种 else
- 开局、级联、免费共用一套权重，除非协议已证明同分布
- 购买/摆牌写进自然生成入口

**本节点必须交付：**

- 出牌器：按出现率造状态（原站几乎每页有 grids，生成器也必须有）
- ResultUtil：纯函数
- Factory：付费到结束一局
- 独立 LOSS：Ways 堵轴 / Payline 堵线；10 万张首次 ≥90%
- Codec：只编码不能从符号唯一推断的事实；decode 后可见状态还在；**本节点不要求写入 Redis**

`CPGAME_RESULT_ENGINE_POLICY` **整表重排**（现行 17 条）：

1. 互斥状态显式建模，禁止 else  
2. 生成器必须按出现率产出，Codec 允许存在不算做了  
3. 三入口权重分开  
4. 测试预期来自抓包或前端常量  
5. 独立 LOSS  
6. 倍率必须整数  
7. 禁止抄 1809 盘面、禁止抄 1407 小数倍率  
8. 分层：Generator / ResultUtil / Factory / Codec  
9–17. 现有 Redis/JAR 条目标成「仅 `redis_loader` 节点生效」，本节点忽略

### 2.4 `demo_visual_gate`（新节点，卡住 Redis）

**完成：**

- `server-api/{ID}-mac-…/dist/controller.jar` 能起，端口 50000–59999，`0.0.0.0`
- 原 `publish` 打开，能 Init/Spin
- 报告里有表：生成 20 局 vs 抓包 20 局，逐项 `grids`/`gf`/`sl`/`gfl`/`frees` 类型与是否为空
- 生成 200 张 vs 抓包出现率：可见字段不得差一个数量级（1809 grids 接近每页都有）
- `rulesVersion`/`rulesHash` 与 generator Core 一致

**失败（不得进 redis_loader）：**

- 只会截 Get Started / 标题 / 能 Spin
- 全单格、没框、没金牌
- 普通局 `frees` 类型与抓包不符（1809 必须是 `false` 不是 `{}`）
- 改了前端

本节点 **人工可驳回**。驳回原因若是盘面，`defect_repair` 必须改生成器，范围可以扩到出牌，不准锁在「只改 Controller 文件名」。

### 2.5 `replica_implementation`（后移后的职责）

只把已有 Core 接到 Controller。禁止新建第二套判奖。禁止为了好看改 publish。

硬校验仍要 `dist/controller.jar` 文件名，但 **本节点 PASS 不能代替 demo_visual_gate**。

### 2.6 `redis_loader`（新节点）

人设第一句：盘面已过 `demo_visual_gate`。Redis 合同固定，禁止发明。

**必须：**

- 启动前 PING `redis.host:port`，拒绝则失败并打印键名，不准改规则
- 整数倍率 `%06d`，`intValueExact()`；公式写在 protocol。1407 类游戏禁止 `totalWin/betAmount`
- 0 倍跳过，不写 Redis；没有 0 倍概率配置
- Key：`PerKeyList_%09d` / `MaryKeyList_%09d` / `BetLog:0%08d:%06d` / `MaryLog:%09d:%06d`
- 一批 MULTI：`ZADD` + `RPUSH` + `LTRIM key -N -1`，N 默认 300，只计本次内存
- 每 1000 次切换普通/特殊开局；特殊只把触发牌权重 ×10；写入按实际结果分类
- 购买/摆牌禁止进 Loader
- member 极简 ASCII，不以 `{` 开头；decode 后可见状态仍在（1809 占格、1407 gfl）；禁止整份 JSON
- `dist/` 仅 jar + `generator.properties` + `start-redis-loader.cmd`

**权重：** 用 protocol 里的抓包计数，禁止 8,8,9,9 手填。

min/max 倍数：允许 `normal-min=1`、`special/mary-min=100`、max=20000。只丢弃超范围，禁止搜倍率。

### 2.7 `standalone_test`

**先跑、失败即停（现行是 Redis/10 进程优先）：**

1. 可见状态出现率 vs 抓包  
2. 真实 page0→page1 连消，不用自己 Factory 当唯一预期  
3. 独立 LOSS 10 万 ≥90%  
4. member 非 JSON、整数倍率、decode 可见状态  
5. 特殊入口只放大触发牌  

**后跑：** Fake Redis 的 ZADD/RPUSH/LTRIM+EXEC；连不上 Redis 的错误文案。  
**取消作为离开本节点的硬条件：** 10 个游戏进程、端口换号。改为可选。

### 2.8 `acceptance` / `reviewer`

驳回清单**最上面**改成：

1. 原页面盘面不像 / 并排 20 局表缺失  
2. 出现率与抓包差一个数量级  
3. member 是 JSON 或还原丢 grids/gfl  
4. 倍率非整数  
5. 改了前端  

文件名、200/200/30、RPUSH+LTRIM 放到后面。有 1–4 不得 delivery。

### 2.9 `defect_repair`

**删掉：** 「范围外问题不得扩大」若问题是空 grids、银金 else、开局 Wild、小数倍率、JSON member。这些视为**全局缺陷**，允许改生成器。

**保留：** 真正的局部文案/端口问题仍锁 defectArea。

follow-up 不得用 defectArea 把盘面问题挡在门外。

---

## 3. 硬校验代码改什么

### `GameArtifactLayoutService.finalComplianceProblems`

**保留：** publish 根 `index.html`、无 `_hosts`、`controller.jar` 名、Loader dist 三件套、禁止关 Redis。

**增加硬失败：**

- `game-capabilities.visibleStateRates` 缺失或分母 &lt; 30  
- `reports/.../visual-compare-20.json`（或约定文件）不存在  
- Redis member 抽样：以 `{` 开头  
- 倍率 Key 匹配 `:\d+\.\d+`（小数）  
- generator 与 controller 的 `rulesHash` 不一致  

**200/200/30：** 只作为 capture 完成条件，**不再**作为 acceptance 唯一通过条件。

### `protocolHandoffProblems`

`supported=true` 必须带 `rate` 和 `evidenceRef`。无 rate 即失败。

---

## 4. 提示词里两句对着的话，改成三句

现行 result_engine：「参考 1809 索引/LTRIM」+「禁止抄 1809 牌面」写在同一段，执行时抄空 grids。

**改成固定三句，三个节点分开贴：**

1. `result_engine`：分层抄 `generator/1809-mac-Freedom-Day` 的类职责，**禁止**抄 30 格、符号 1..13、空 grids、手填权重。  
2. `redis_loader`：索引/列表/MULTI/整数 `%06d`/0 倍跳过/触发牌 ×10 抄 1809 Loader。member 编码按**当前游戏**不可反推事实，禁止整份 JSON，禁止抄 1407 旧 `win/betAmount`。  
3. 任何节点：购买/摆牌不是自然 Redis 路径。

---

## 5. 不改的

- 十类目录、Mac 隔离、raw gameId  
- 一份 Java Core、禁止第二套判奖、禁止改前端  
- 抓包 200/200/30 作为**采样下限**（不是验收上限）  
- Redis 一批 ZADD+RPUSH+LTRIM、300 条/倍率、无 seed、禁止 LOCAL_JSONL  
- Controller 端口 50000–59999  

---

## 6. 建议版本

模板版本 **22 → 23**。旧 run 不自动重跑。新游戏必须用 v23。  
`workflows.json` 里 `startNodeId` 仍是 capture，但 `replica_implementation` 的入边改为 `demo_visual_gate`。
