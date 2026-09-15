# 58-Crazy-Gems 协议与状态机规范

## 身份与证据版本

真实游戏为 `58-Crazy-Gems`：启动路径 `/58`、查询参数 `gid=58`、业务请求 game-id 58、页面标题和 bundle `GameName="Crazy Gems"` 相互一致。大厅 `g_id=456` 只是站点目录项 ID，不得用作游戏协议 ID。

本规范的 `rulesVersion` 为 `crazy-gems-rules-0bf6b8291cfbfe00`，绑定规则哈希 `0bf6b8291cfbfe0005aacbe5150d64097752cd989da88fb5a384db1e2d3d60ef`。它由以下四份当前游戏证据按既定顺序及绝对路径计算：规则文本、奖表文本、Config fixture、主 bundle。各文件 SHA-256 见 `protocol-handoff.json.rulesManifest`。本轮复算与现有哈希一致。

## 端点与请求顺序

源站业务请求均为 POST。首次加载的已观察顺序是：

1. 加载 `/58` 与静态资源。
2. `POST /cp/api/v1/auth/verify`，本次只保存了 HTTP 200，未保存请求或响应字段。
3. `POST /cp/api/v1/crazy-gems/config`，表单字段为 `t,gid`。
4. Config 成功后才能 `POST /cp/api/v1/crazy-gems/spin`，表单字段为 `bl,bs,t,gid`。

打开 History 后：

1. `POST /cp/api/v1/crazy-gems/log-list`，字段为 `page_index,begin_at,end_at,t,gid`，从页 1 顺序请求直到响应 `end=1`。
2. 选择列表记录后，把 `ll[].tis` 原值作为 `POST /cp/api/v1/crazy-gems/log-view` 的 `transfer_id`。

bundle 还枚举 `POST /cp/api/v1/ping`。其字段未被当前抓包保存，必须保留未知，不能猜测。源站没有捕获独立 Init、Balance、Session 或 Delivery 业务端点；Config、Spin、History 是当前有字段级证据的端点。

## 编码与响应封装

前端 `Http.post` 默认使用 `application/x-www-form-urlencoded`：键和值经 `encodeURIComponent`，空格替换为 `+`，随后用 `&` 拼接。抓包中的 Config、Spin、log-list、log-view 均符合这一格式。

响应为 JSON 封装：

```json
{"code":200,"data":{},"info":"ok"}
```

前端对 `code` 使用宽松的 `== 200` 判断，成功时把 `data || {}` 交给游戏逻辑，非 200 则进入错误分支。业务表单和 JSON 响应未观察到应用层加解密。`t` 是分配会话的不可解释凭证，只做 URL/form 编码；不得解读、持久化、回显或发送到授权域名之外。

## Config 字段语义

| 字段 | 已确认语义 | 证据边界 |
|---|---|---|
| `auto` | 自动 Spin 选项 `[10,30,50,100,500]` | Config 与前端 `autoTimes` |
| `bll` | bet level 列表 `1..10` | Config、规则、前端初始化 |
| `bsl` | bet size 列表 `[0.5,5,50]` | Config、规则、前端初始化 |
| `dbs` | 默认 bet size `0.5` | `getIndexByBaseMultiplier` |
| `dbl` | 原值 `50` | 不在 `bll`；前端找不到时回退索引 0。语义未知，禁止当作线数或倍率 |
| `cc`,`cs` | `BRL`,`R$` | Config 与规则显示一致 |
| `spl` | 三个同符号的奖表值 | Config 与奖表页面一致 |
| `ts` | 与首载一致的服务端时间值 | 未发现参与中奖或状态转换，保留原值语义边界 |

最低押注是 `bl=1, bs=0.5`，总下注 R$0.50。

## 牌面、符号与中奖判定

服务端 `rskl` 恒为 9 个符号，按 reel-major 展平：`flatIndex = reelIndex * 3 + rowIndex`。实际中奖牌面是 3 reels × 3 rows。前端 `ColumnCount=4` 的第 4 列仅显示 minecart 倍率，不是第 4 个中奖滚轴。

固定 5 条线的行坐标依次为：

```text
line 0: [0,0,0]
line 1: [1,1,1]
line 2: [2,2,2]
line 3: [0,1,2]
line 4: [2,1,0]
```

`wmkl` 是以字符串线号为 key、中奖符号为 value 的对象。WILD 可替代任意普通符号；若一条线全为 WILD，则按 WILD 奖值。每条线只支付最高奖。总中奖为：

```text
wa = Σ spl[wmkl[line]] × bs × bl × rpx
```

奖表为 WILD=5、H1=4、H2=3、H3=2.4、H4=2、H5=1.6、H6=1、H7=0.4。对 1160 个 History detail 独立复算，中奖线集合不一致数为 0，金额不一致数为 0。

bundle 的 `SymbolWeightsList=[5,10,15,20,25,30,10,5]` 和 `SymbolRpxWeightsList=[30,25,20,15,10,5]` 只在 `GameConfigData.randomReel` 中构建滚轴动画 `backReels`。权威结果随后由 `Spin.data.rskl` 和 `rpx` 注入。这些常量不得作为服务端生成权重；真实服务端权重仍为 UNKNOWN，也不得从 1160 局频率反推。

## Spin、Round、Delivery 与 Step 状态机

源站 Spin 成功的当前确认字段是 `ba,pb,rpx,rskl,wa,wmkl`。History detail 另提供 `baf,bid,bl,bs,ca,gt`。Spin 响应本身没有传输 ID；History 以 `tis` 为传输 ID，并满足 `bid = "58-" + tis`。

当前 1160 个真实付费 Round 都只有一个 Step，且均为合法终局：

```text
READY
  -> PAID_START_ACCEPTED
  -> COMPLETE_ROUND_GENERATED       （GameRuleCore 仅调用一次，Redis member 仅领取一次）
  -> STEP_AVAILABLE                 （当前只有 Step 1: PAID_SPIN）
  -> TERMINAL_STEP_DELIVERED        （Delivery 只消费，不重新生成）
  -> READY
```

终止条件是 `spin_status` 缺失或为假；前端 `onGameEnd` 也只在 `!spin_status` 时进入终局。当前没有 truthy `spin_status` 的相邻 Step 证据，所以不能实现臆造 continuation。若将来捕获到 truthy 路径，必须同时保存前后相邻 Step、状态字段和终止响应，并修订规则哈希及交接。

Delivery 是本地运行时为了不可变完整 Round 队列而建立的抽象，不是已捕获的源站 HTTP 端点。一次生成必须覆盖完整 Round，Step 在生成后不可变。免费或奖励 Step 不得计为新付费起点；当前游戏没有确认这类 Step。

普通独立 LOSS 可实时生成，但仅限 READY 状态的普通付费单 Step：必须使用正式安全随机，独立 ResultUtil 复核五条线均不中奖、`wa=0`、`wmkl={}`。不得按局号轮播、固定牌面或使用 fixture/History 作为运行时结果。

## History 与跨局余额

`log-list` 共 117 页，首项 `lc=1160`，最后一页 `end=1`，共有 1160 个唯一 `tis`。所有 `tis` 都有一份 `log-view` detail，且 `bid` 全部满足 `58-tis`。

History header 的 `ba=580` 等于 1160×0.5；`wa=14303` 等于全部 detail 中奖合计。列表 `gt=58` 是当前游戏 ID，而 detail `gt=1` 只被前端存入 `game_type`，二者语境不同，不能把 detail `gt` 当 gameId。

`pb` 是 Spin 完成后客户端采用的余额，1160 局中均与 `baf` 数值相等。按 transferId 升序，1159 对相邻局全部满足：

```text
current.pb = previous.pb - current.ba + current.wa
```

余额只可在完整 Round 终局提交一次；Delivery 重试不得重复扣款或派奖。

## 特殊结果与未确认边界

已确认特殊结果只有 `MINECART_MULTIPLIER`。`rpx` 值域为 `1,2,3,5,10,15`，它是同一付费单 Step Round 的正交倍率字段，不形成新 Round 或新 Step。1160 局中 `rpx>1` 为 983 局，满足 30 局目标。

以下均为已处置的稀有/未知边界，不阻断后续正确实现：

- `[BLOCKER:RARE]` FREE_SPINS：仅有通用 FreeGame scaffold，规则/奖表无入口，1160 局无 `fsn/frwa`。
- `[BLOCKER:RARE]` MULTI_STEP_ROUND：仅有前端 truthy `spin_status` 分支，未捕获实际 continuation。
- `[BLOCKER:RARE]` FEATURE_BUY：规则、奖表、bundle 和场景树均未发现入口。
- `dbl=50`：语义未知，保留原始证据，不配置。
- Redis Key、member 编码与长度：当前游戏证据中不存在，不得从示例项目继承或猜测。

## 交付与实现约束

完整字段矩阵见 `field-evidence-matrix.json`，每项行为的实现契约、独立验收契约和多步骤相邻证据见 `protocol-handoff.json`。后续节点必须先核对同一 `rulesHash` 和 `game-capabilities.json.handoffContract.behaviorIds`。

fixtures、History、抓包 Spin 只能作为独立 oracle；运行时 Config、Spin、余额、Round、Delivery、Redis 成员必须由 Java `GameRuleCore` 链路生成或管理。任何 fixture 轮播、固定结果列表、按局号脚本或未证实模式都违反本交接。
