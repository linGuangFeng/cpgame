# agent-ai「复刻游戏」工作流问题，以及给其他会话的复刻文案

来源：`smb://192.168.10.3/agent-ai`  
模板：`WorkflowTemplateCatalog.GAME_REPLICATION`（当前版本 22）  
平台硬校验：`GameArtifactLayoutService`  
对照：1809 Freedom Day、1407 Coin Master GO。这两款单独重做（不走工作流）已经能看；走工作流时问题大。

---

## 一、工作流流程哪里出了问题

节点实际顺序：

```
capture → publish_package → publish_review
→ protocol_analysis → protocol_review
→ replica_implementation      ← 先写 Controller
→ result_engine               ← 后写 GameRuleCore / 生成器 / Redis
→ standalone_test → acceptance ⇄ defect_repair → delivery
```

`result_engine` 节点还会额外拼上 `CPGAME_RESULT_ENGINE_POLICY`（17 条，Redis 和 JAR 合同在前，盘面观感在第 12 条）。

### 1. 先写服务端，后写规则（顺序反了）

`replica_implementation` 的指令是：根据已验收协议 **和 Java GameRuleCore** 实现 Controller。  
但 GameRuleCore 在下一个节点 `result_engine` 才出现。

所以实现节点只能：抄上一款游戏的 Controller/牌面模型当壳，专属状态留空。1809 就是 30 个单格 + 空的 `grids/gf/sl`。结果引擎接着继承这套空模型，Codec 只保证“如果有 grids 别非法”，生成器从不造 grids。测试 roundtrip 用手工拼的合法牌面，自己测自己。

正确顺序必须是：协议 → **先规则引擎/出牌** → 再用同一份 Core 接 Controller → 肉眼看盘 → 最后才 Redis。

### 2. 平台硬校验不看“像不像原游戏”

`GameArtifactLayoutService.finalComplianceProblems` 会卡：

- publish 根上有 `index.html`、没有 `_hosts`
- `current-status.json` 字段齐全，LOSS/WIN 样本 200/200，特殊 30
- `game-capabilities.json` **有** winModel/boardModel/redisContract 这些键
- `protocol-handoff.json` 有 behaviorId、evidenceRefs、`usesImplementationGeneratedExpected=false`
- `dist/controller.jar` 文件名、`generator.properties` 键、Redis 没被关掉、RPUSH+LTRIM

它 **不卡**：

- 随机生成 20 局的 `grids/gfl/银金` 出现率是否接近抓包
- 空数组是不是原站常态
- 打开原页面盘面是否像

所以模型只要把 JSON 键和 JAR 名字做对，平台就会放行。1809 增量修复报告写 PASS，生成器仍出全单格盘。

协议验收 `protocolHandoffProblems` 同样只验交接 JSON 形状，不验「可见状态在真实 spin-response 里空不空、实现会不会按同样频率造出来」。`boardModel.mergedSymbols.supported=true` 加上空 `grids:[]` 就能过。

### 3. 提示词把 Redis/采样放在玩法前面

`result_engineer` 人设和 `CPGAME_RESULT_ENGINE_POLICY` 前 11 条几乎全是 Redis：0 倍不写、无 seed、LTRIM、300 条/倍率、禁止 LOCAL_JSONL、必须参考 1809 索引结构。  
「互斥状态必须显式建模、禁止 else」写在政策第 10 条后半和生成器节点末尾，但前面已经用光注意力。

`tester` 节点虽然写了「特殊牌/材质必须验证起始生成、补牌入口、else 必须失败」，同一段还强制：隔离 Redis 实跑、10 个游戏进程、端口换号、最旧淘汰。失败成本最高的是 Redis/进程，不是盘面。

`reviewer` 验收人设第一句是 200/200/30 采样口径，后面才是“原页面闭环”。驳回清单里写得最具体的是：target JAR、generator.properties、Redis 关闭、controller.jar 文件名。

### 4. 「参考 1809」和「禁止抄 1809」写在同一节点

结果引擎节点要求：参考 1809 的通用索引/列表/原子裁剪，同时禁止复制 1809 的游戏 ID、牌面长度、符号。  
执行时变成：抄 1809 的工程骨架和「可空数组」习惯，当前游戏的 `grids/gfl` 当成可缺省。1809 自己早期就是空 grids，于是模板把病传给所有后继游戏。

### 5. 抓取用「局数」收工，分析节点不再回头数可见字段

`capture` 完成条件是普通输/赢各 200 局、特殊各 30 局。够数就停。  
`protocol_analysis` 写明「保留现有抓取成果，只补充验收明确指出的缺失证据」。没有人被要求统计：420 页里 402 页有 grids、1407 开局 501 局 Wild=0、级联补牌 2429 张全是银。

证据在 captures 里，工作流没有节点强制把这些频率写进 `game-capabilities` 并当成生成器验收。

### 6. 浏览器验收没有「并排对比」

测试节点要求用原页面 Spin，但不要求把生成局和抓包局的 `grids/gf/sl/gfl/frees` 并排打印。截一张 Get Started、能 Init/Spin 即可往下走。

### 7. 增量修复把明显漏项锁在门外

`acceptance` 驳回后进 `defect_repair`：只改 defectArea。follow-up 还规定范围外问题不得扩大。  
空 grids、银金 else、开局乱抽 Wild，只要没写进最近一次 defectArea，就可以带着 PASS 交付。

### 8. 和 1809 / 1407 的对应关系

| 工作流漏洞 | 1809 表现 | 1407 表现 |
|---|---|---|
| 先 Controller 后规则 | 抄单格随机盘当 Core | 牌面模型先按「有 gfl 就金、否则银」的 else |
| 硬校验不看出现率 | `grids.supported=true`，生成全空 | 金牌「支持」，开局几乎没有或乱出 |
| Codec 不强制生成 | 手工牌面 roundtrip PASS | 转换规则测了，起始分布没对抓包 |
| 补牌入口未分统计 | 30 格独立随机，无 2–4 格堆叠 | 级联补牌套开局权重，原站补牌全是银 |
| 验收看 JAR/Redis | 报告 PASS，页面全是小图标 | 多轮 v18.x 才把银金/Wild 来源补对 |

单独重做这两款能看，是因为绕开了上述顺序和硬校验：先读真实 spin-response 和前端 JS，先把可见状态造出来，前端不动。

---

## 二、给其他会话的复刻文案（整段复制即可）

下面从「你是这个仓库…」起到文末，作为新会话第一条消息发出。把 `{ID}`、`{英文名}` 换成目标游戏。不要再让它走 agent-ai 工作流。

---

你是 /Volumes/hd/cpgame 的游戏复刻执行人。不要启动、不要调用 smb://192.168.10.3/agent-ai 的 GAME_REPLICATION 工作流。那套流程先写 Controller 后写规则，平台硬校验只看 JSON 键和 controller.jar 文件名，不看盘面像不像；1809/1407 走工作流会出空 grids、银金 else、开局乱抽 Wild。这两款后来单独重做才正常。你按本指令直接做。

任务：

```
直接复刻，不走工作流。
游戏ID：{ID}
Mac 目录：{ID}-mac-{英文名}
原目录：{ID}-{英文名}   （只读，禁止修改）
前端不能动：resources/ 与 publish/ 的 js/html/css/图/音频禁止改；Mac 前端只能从原目录字节拷贝。
先看效果：原页面 + Java Controller 局域网可玩，盘面必须像原站。
```

业务身份是 raw gameId={ID}。页面 gid（1809=2260，1407=55）只是前端兼容字段。

### 禁止再犯的工作流错误

1. 不要先写 server-api 再写 generator。必须先 Java 规则/出牌，再接 Controller。
2. 不要把 Redis Loader、JAR 改名、200/200/30 采样当第一验收。盘面不像就停。
3. 不要抄 1809 的 30 个独立单格、空 grids、空 gfl。1809 只借鉴「Generator + ResultUtil + RoundFactory + Controller 同一份 Java」。
4. 字段在协议里“支持”不等于生成器可以一直输出空数组。原站几乎每页都有的状态，生成器也必须造出来。
5. 测试预期必须来自真实抓包或前端常量，禁止用自己 factory 的输出当唯一预期。
6. 开局、级联补牌、重转补牌三个入口分别统计，禁止共用一套权重。
7. 银/金、框、Wild 来源必须显式分区，禁止 else/默认值。

### 必做顺序

1. 只读原 captures 里至少 30 个真实 spin-response，以及 publish 里的游戏 JS。列出原前端用来画格子的字段（1809：prop/trl/grids/gf/sl；1407：5x4+缓冲+gfl，非 gfl 合资格牌必须显式银）。
2. 统计这些字段在真实页上的出现率、合法取值、哪些符号不能进。写进 protocol/{Mac目录}/。答不出出现率不准写 Java。
3. 拷贝 resources、publish 到 Mac 目录，哈希与原文件一致。
4. Java 生成器按统计产出可见状态；ResultUtil 只做确定性判奖；RoundFactory 一次整局；Codec 用真实 page0→page1 校验连消。
5. Controller 投原协议字段和类型（普通局 frees 在原站是 false 就不要发 {}）。
6. `java -jar server-api/{Mac目录}/dist/controller.jar --port 5xxxx --config dist/controller.properties --publish publish/{Mac目录}`，监听 0.0.0.0。
7. 打开原页面肉眼看：合并符号、金银框、金牌、连消变形、免费。并排打印 20 局生成 vs 20 局抓包的可见字段。不像就改生成器，不改前端，不写 Redis。
8. 盘面像了再做 Redis。member 必须能还原可见状态，不能为了紧凑编码丢掉 grids/gfl。

### 红线（一条即失败）

- 改了原目录或任何前端文件
- Mac 与原版 index.html / 主游戏 JS 哈希不一致
- 随机 20 局可见状态出现率明显低于抓包
- 测试只做自生成 roundtrip
- 报告 PASS 但页面全是 1 格小图标、没框、没金牌
- 运行时读 fixtures/captures

### 对照（换游戏时当检查表）

1809：6x5 prop + 4 格 trl；中间 4 列高度 2–4 合并；gf/sl 引用整组；银中奖变金、金中奖去框变形；普通局 frees=false；Scatter=12 四个起 10 次免费；购买 bet_type=3 成本 75 倍总注。

1407：5 轴 4 行 + 缓冲；合资格牌显式银或金，gfl 只标 2/3/4 轴金牌；银中奖消除，金中奖变 Wild；开局 Wild=0；级联补牌固定银；3 SC → 12 次免费，倍率 2,4,6,10。

新游戏先回答：哪几个字段决定像不像，真实样本空不空，生成器会不会按同样频率造出来。

交付只给：局域网 URL、已按抓包统计生成的可见状态、前端未改的哈希、未验证的原厂 RTP 标 UNKNOWN。

现在开始：先读该游戏真实 spin-response 和前端判定代码，再写 Mac 目录的 Java。
