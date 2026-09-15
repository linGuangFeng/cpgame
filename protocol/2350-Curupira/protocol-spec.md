# 2350-Curupira 协议与状态机实现核对规范

- 游戏：`2350-Curupira`
- `rulesVersion`：`2350-curupira-rules-d5421efc`
- `rulesHash`：`d5421efc347392576699048c0538c72d669056017eb723ae2a046fa7bac71404`
- 机器可读规范：`protocol-spec.json`
- 行为契约：`protocol-handoff.json`
- 能力唯一来源：`game-capabilities.json`

实现与验收必须同时匹配上述 `rulesVersion`、`rulesHash` 和 handoff 中全部 15 个 `behaviorId`。fixtures 仅可作为独立 oracle，禁止作为运行时结果源。

## 1. 已确认的游戏模型

| 项目 | 确认值 | 实现约束 |
|---|---|---|
| 游戏名称 | Curupira | 后端 `game_info.name=Magic Scroll` 是冲突元数据，不得覆盖真实名称 |
| 牌面 | 5 列 × 3 行 | `res.ps` 固定 15 格 |
| 存储顺序 | 列优先 | wire index = `column * 3 + row` |
| 中奖模型 | `FIXED_PAYLINES` | 不得当作 Ways/Cluster |
| 固定线数 | 25 | Bet Level、Bet Amount 或倍率都不是线数 |
| 方向 | 从左到右 | 每条线只取最高一组中奖 |
| Wild | 21 | 替代除 Scatter 外的所有付费符号 |
| Scatter | 31 | 3 个或以上进入功能选择；孤立的“4+”文本是陈旧冲突文本 |
| 最小押注 | line bet 0.02，level 1，总押注 0.50 | `bg = b * l * 25` |

25 条线的逻辑行模式如下，行号使用 `0/1/2`：

```text
 1  [1,1,1,1,1]    2  [2,2,2,2,2]    3  [0,0,0,0,0]
 4  [2,1,0,1,2]    5  [0,1,2,1,0]    6  [1,2,2,2,1]
 7  [1,0,0,0,1]    8  [2,2,1,0,0]    9  [0,0,1,2,2]
10  [1,0,1,2,1]   11  [1,2,1,0,1]   12  [2,1,1,1,2]
13  [0,1,1,1,0]   14  [2,1,2,1,2]   15  [0,1,0,1,0]
16  [1,1,2,1,1]   17  [1,1,0,1,1]   18  [2,2,0,2,2]
19  [0,0,2,0,0]   20  [2,0,0,0,2]   21  [0,2,2,2,0]
22  [1,0,2,0,1]   23  [1,2,0,2,1]   24  [2,0,2,0,2]
25  [0,2,0,2,0]
```

普通固定线奖项满足：

```text
lineWin = b * l * wa.o
tw = res.tws = b * l * sum(wa[].o)
cg = tw - bg
eg = sg + cg
```

第 10 局独立 oracle：`sum(wa.o)=1303`，`0.02 * 1 * 1303 = 26.06 = res.tws = tw`。

## 2. API 基址、编码与签名

入口 `versionconfig.js` 使用：

```text
ApiBase = window.location.protocol + "//" + query.sip + "/cp"
```

所有已捕获接口均为 POST，Content-Type 为：

```text
application/x-www-form-urlencoded;charset=utf-8
```

响应为 UTF-8 JSON；未观察到请求或响应加密层。

`signapt` 算法来自当前 2350 bundle：

```text
secret = 3fZ8kL2qW9xA4pT7vJ1rQ6yB0sN5mX8h
expire = server-adjusted epoch milliseconds
canonical = 原始业务参数按 key 升序排列，再以 key=value 用冒号连接
signapt = lowercaseHex(MD5(secret + expire + canonical + "aptsignature"))
```

顺序必须是：构造原始参数 → 取得 `expire` → 计算 `signapt` → 再把 `signapt/expire` 放入表单。签名 canonical 集合不得包含刚生成的 `signapt` 和 `expire`。

## 3. 首载与请求顺序

确认的强制顺序：

1. `POST /cp/config/initialData`
2. `POST /cp/account/getUserInfo`
3. `POST /cp/single_game.Game/initRoom`
4. 加载语言纹理及游戏运行资源并显示主界面

可选/按需请求：

- `/cp/activity/getActivity`：活动子系统，不启动或推进游戏 Round。
- `/cp/Goldgame/user_game_history`：打开 History 时请求。
- `/cp/single_game.Game/gameResult`：付费或功能 Step 请求。

`initialData` 请求字段：`currency,gid,language,ai,signapt,expire`。

`getUserInfo` 请求字段：`token,gid,language,ai,signapt,expire`。

`initRoom` 请求字段：`token,gid,language,signapt,expire`。

`gameResult` 请求字段：`token,gid,language,bet,level,type,game_type,signapt,expire`。

History 请求字段：`start,end,page_size,page,token,gid,language,zone_time,signapt,expire`。

## 4. Round、Delivery 与 Step 边界

### 4.1 已确认的普通付费 Round

一次 `gameResult(type=1, game_type=1)` 请求只索引一个真实付费 Round 起点。

普通单步终止响应的确认条件：

```text
gt = 1
small_game_type = 0
f = []
响应 data 不是空数组重试哨兵
没有已激活特殊模式
```

此时该 Round 在单个响应内终止。Init、重试、回放和 History 均不得增加 `spinCount`。

未观察到独立 Delivery 接口。前端代码对未来免费/奖励 Step 仍调用 `gameResult`；这些 Step 必须关联到触发它们的同一付费 Round，不得索引为新付费 Spin。

### 4.2 Init 与恢复

`initRoom` 恢复当前或最近一局结果用于展示，不产生新 Round。确认的恢复逻辑包括：

- `gameResult data=[]`：作为未完成/忙状态哨兵，进入重试。
- `BET_BUSY`、`BET_ERROR` 或网络失败：通过 `initRoom` 恢复。
- `initRoom` 返回与当前缓存相同的 `oid`：进入结果恢复流程，仍不得建立新 Spin 索引。

### 4.3 History 聚合

当前普通样本中，History 每个 `data.list[]` 对应一个付费 Round，并含一个 `results[]` Step。未来特殊 Step 的精确聚合方式没有运行时证据，不得猜测。

10 局 History 汇总 oracle：

```text
data.totals.bet_golds = 5
data.totals.change_golds = 21.06
data.totals.total = 10
```

## 5. Round ID 精度

`rid/oid` 为超过 `2^53-1` 的 19 位整数。浏览器把它们转成 JavaScript Number 后会丢失低位。

实现要求：

- Java 端用 `long` 或十进制字符串保存、比较和生成标识。
- 浏览器/API 适配层必须保留十进制字符串影子值。
- 禁止经由 `double` 或 JavaScript Number 做 Round 相等性判断。
- 抓包 oracle 中以 History `order_id` 的 `-2350` 前缀部分作为精确 Round ID。

`spin-index.jsonl` 已将 10 个精确 ID 保存为字符串。

## 6. 响应字段语义

| 字段 | 已确认语义 |
|---|---|
| `b` | line bet |
| `l` | bet level |
| `bg` | 当前普通付费响应总押注 |
| `sg/start_gold` | 响应前余额，普通样本中两者相等 |
| `cg` | 净余额变化 |
| `eg` | 响应后余额 |
| `tw/res.tws` | 普通响应总中奖 |
| `o` | 普通响应中 `sum(wa.o)`；只确认普通模式 |
| `cl` | 仅观察到 0，语义 UNKNOWN |
| `gt` | 普通样本为 1 |
| `small_game_type` | 普通样本为 0 |
| `f` | 普通模式为空数组；前端特殊路径期望对象，但 wire 未捕获 |
| `rid/oid` | Round/Order 标识，存在精度风险 |
| `t` | 已脱敏的不透明字段，禁止猜测 |
| `u` | 用户标识，不是游戏规则输入 |
| `res.ps` | 列优先 15 格最终符号 |
| `res.sc` | Scatter(31) 数量 |
| `res.wa[]` | 固定线中奖项 |
| `wa.c` | 匹配个数 |
| `wa.ln` | 1–25 中奖线编号 |
| `wa.o` | 奖表倍率 |
| `wa.s` | 被评估为中奖的符号 ID |

特殊 `f` 对象字段仅有前端代码语义，不能视为 wire 已确认：`f.t,f.st,f.tt,f.twa,f.fcc,f.fcp,f.fcn,f.ba,f.bet,f.l`。

## 7. 已确认特殊表现：主游戏 Expanding Wild

主游戏 Expanding Wild 不依赖独立响应 flag。当前前端执行：

```text
for column c in 0..4:
    if ps[c*3] == 21 and ps[c*3+1] == 21 and ps[c*3+2] == 21:
        expandingWildCols.add(c)
```

第 10 局 `res.ps` 的零起始第 2 列为 `[21,21,21]`，同 Round Init 回放和截图均显示扩展 Curupira reel，故 `B013_MAIN_GAME_EXPANDING_WILD` 为 `CONFIRMED`。

必须保留原始 wire `res.ps` 供规则校验；前端动画期间对显示符号的本地替换不得回写协议结果。

## 8. UNKNOWN 行为与禁止实现边界

以下行为的规则/UI/前端路径可见，但没有完整运行时 wire 序列，因此 handoff 状态必须为 `UNKNOWN`：

### B005_FREE_EXPANDING_WILD

- 已知规则：3+ Scatter 后选择该模式；6 次免费 Spin；每次出现一个扩展 Curupira reel。
- 代码请求路径：`type=2, game_type=2`。
- 缺失：激活响应、相邻 6 Step、`f` 转换、rid/oid 连续性、终止响应、History 聚合。
- 处置：不得实现协议 payload 或运行时模式。

### B006_HOLD_AND_SPINS

- 已知规则：初始 3 次；新 Coin 增加一次；单 Coin 最高 10x；15 格填满提前结束。
- 代码请求路径：`type=2, game_type=3`。
- 缺失：激活响应、Coin 数组类型、相邻 respin、剩余次数转换、终止响应、History 聚合。
- 处置：不得实现 Coin payload 或运行时模式。

### B007_FEATURE_BUY

- 已知入口：选择模式后构造 `type=3, game_type=2/3`，静态成本为当前总押注 40x。
- `buy_free_max_bet=0` 在当前 `isShowBetBuy` 代码中通过检查，不是禁用证据。
- 未发送购买请求：授权仅覆盖最小押注付费 Spin，不覆盖 40x 特殊购买。
- 缺失：扣费字段/时机、购买响应、rid/oid 边界、相邻购买模式 Step、终止响应。
- 处置：不得模拟或实现购买响应。

## 9. 运行时独立 LOSS

能力清单仅允许普通付费独立单步 LOSS。生成结果必须由 Java `GameRuleCore` 实时产生，并由独立 `ResultUtil` 验证：

```text
type=1, game_type=1
res.ps 长度 15，符号属于当前游戏枚举
res.sc < 3
res.wa = []
res.tws = tw = 0
f = []
cg = -bg
eg = sg - bg
gt = 1
small_game_type = 0
```

当前没有 reel strip 或服务器 symbol weights 证据。前端随机数组只用于动画，不得当作结果权重。不得声称复现原站概率分布。

## 10. Redis 与 fixtures

没有当前游戏 Redis Key、score、member 编码或长度证据，`redisContract` 保持 UNKNOWN。禁止继承其他游戏的 Redis 结构。

fixtures 的唯一用途是协议回归 oracle：

- 禁止读取或轮播历史 Spin 响应作为运行时结果。
- 禁止按局号播放固定 LOSS/WIN 列表。
- 禁止把 fixture token、余额或 Round ID 当作运行时 session。
- 本地 Init、Spin、Balance、Session 必须来自 Java `GameRuleCore`。

## 11. 下游验收入口

下游节点开始前必须读取：

1. `game-capabilities.json`
2. `protocol-handoff.json`
3. `protocol-spec.json` 与本文件
4. `evidence-matrix.json`
5. `reports/2350-Curupira/protocol-validation.json`

实现不得跳过任何 `CONFIRMED` behaviorId；状态为 `UNKNOWN` 的三项只能保持禁用/安全拒绝，不能根据示例游戏或猜测补齐。


## 独立无奖标记（2026-09-14）

本次只接普通付费单步 LOSS，保留 CU1 版本和入口/种类头，编码为 `CU1PL;#`。解析器调用共享 ConstructiveLossGenerator 物化真实零奖盘面，禁止带入免费触发或整列扩展 Wild。

FREE_EW / BUY_FE（免费扩展 Wild）、HOLD / BUY_HS（锁币）、TRIGGER 及其他种类仍完整保存。本次未接免费扩展 Wild 的专用零奖生成逻辑；锁币中即使当前没有新增奖金，也不得替换前后继承的格子与剩余次数。只有 `CU1PL;#` 接受标记，其他模式的 `#` 明确拒绝。

新解析器兼容已有完整编码。每次领取 Redis member 后只物化一次，校验、后续交付、重试与历史共用该事实；Redis 为空时仍失败。零奖生成最多尝试 5 次，并有 10 个已校验默认盘（41 按 PAN 数量分别保存）。先更新消费端 JAR，再使用新 Loader 写入带标记的数据。Loader JAR 通过 Maven package 交付到本游戏 dist 目录。
