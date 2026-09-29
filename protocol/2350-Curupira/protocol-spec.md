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
| Scatter 位置限制 | 每列最多 1 个 | 用户于 2026-09-23 确认；生成时约束候选空间，禁止出牌后替换 |
| 首列 Wild | UNKNOWN | 10 局样本未见不能证明禁出；当前实现不得硬禁 |
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

## 8. 原站 wire 未确认边界与本地工程投影

以下行为的规则/UI/前端路径可见，但没有完整原站运行时 wire 序列，因此 handoff 的原站证据状态保持 `UNKNOWN`。这不再表示两种 Mary 停止生成；本地实现按已确认规则建立显式工程投影，并且不得把该投影反称为原站抓包事实：

### B005_FREE_EXPANDING_WILD

- 已知规则：3+ Scatter 后选择该模式；6 次免费 Spin；每次出现一个扩展 Curupira reel。
- 代码请求路径：`type=2, game_type=2`。
- 缺失：激活响应、相邻 6 Step、`f` 转换、rid/oid 连续性、终止响应、History 聚合。
- 本地处置：正式生成 6 Step 完整 Mary 事实，每 Step 恰好一列三 Wild，Scatter 少于 3（当前不实现免费再触发）；触发后 `type=2, game_type=2` 只从 `Mary type0` 读取并逐 Step 投影。状态字段属于本地工程合同，待完整原站序列取得后再校准。

### B006_HOLD_AND_SPINS

- 已知规则：初始 3 次；新 Coin 增加一次；单 Coin 最高 10x；15 格填满提前结束。
- 代码请求路径：`type=2, game_type=3`。
- 缺失：激活响应、Coin 数组类型、相邻 respin、剩余次数转换、终止响应、History 聚合。
- 本地处置：正式生成从 3 次开始到终止的完整 Coin 状态序列，新 Coin 各增加 1 次、面值 1..10、填满 15 格立即结束；触发后 `type=2, game_type=3` 只从 `Mary type1` 读取并逐 Step 投影。空/Coin 权重是显式本地临时配置，不是原站概率。

### B007_FEATURE_BUY

- 已知入口：选择模式后构造 `type=3, game_type=2/3`，静态成本为当前总押注 40x。
- `buy_free_max_bet=0` 在当前 `isShowBetBuy` 代码中通过检查，不是禁用证据。
- 未发送购买请求：授权仅覆盖最小押注付费 Spin，不覆盖 40x 特殊购买。
- 缺失：扣费字段/时机、购买响应、rid/oid 边界、相邻购买模式 Step、终止响应。
- 处置：不得模拟或实现购买响应。

## 9. 正式完整局生成边界

正式生成器在统一 `generation.count` 尝试预算内，先按显式场景权重选择普通、免费扩展 Wild 或 Hold & Spins，再自然生成该场景完整事实。它不得接收目标结果类型、目标奖金或样本局模板；样本只可作为明确标注不足的基础权重。Demo 运行时不得调用生成器。

普通场景每次自然生成一个 5×3 候选，应用同列最多一个 Scatter 后由独立 `ResultUtil` 计奖并分类为 LOSS、WIN、主游戏 EXPANDING_WILD 或 TRIGGER；TRIGGER 不再丢弃，作为普通类型 0 的付费起点缓存。免费场景必须包含完整 6 Step，每 Step 恰好一个扩展 Wild 列且当前不生成再触发。Hold 场景必须包含从初始 3 次到剩余次数归零或 15 格填满的全部状态；每个新 Coin 增加一次，Coin 面值为 1..10。

普通与免费符号 Step 都必须保存完整 15 格及可独立反推的奖项；Hold 每个 Step 必须保存完整 15 格 Coin 快照、新增位置、剩余次数和本 Step 新增面值。0 倍同样保存完整事实。

当前没有 reel strip 或服务器 symbol weights 证据。10 个连续付费样本的 150 格计数为 `1=26,2=14,3=16,4=16,11=16,12=17,13=19,14=17,21=6,31=3`，状态为 `SAMPLE_INSUFFICIENT`，只能作为当前经验基础权重。前端随机数组只用于动画，不得当作结果权重，不得声称复现原站概率分布。

Scatter 位置约束为每列最多一个：同列首次抽到 31 后，余下位置不再包含 31。该约束在候选采样阶段执行，不得在生成后替换。首列 Wild 未经证实为禁止，因此所有列都允许 21。

## 10. Redis 与 fixtures

原站证据中没有 Redis Key、score、member 编码或长度；本地交付合同因此明确标注为工程合同而非原站事实：普通 LOSS/WIN/主游戏 Expanding Wild/TRIGGER 使用当前配置 `redis.game-id` 的类型 0 `PerKeyList` / `BetLog`；免费扩展 Wild 使用 `Mary type0`；Hold & Spins 使用 `Mary type1`。类型映射固定，不按样本、倍率或运行结果改变。

每个 member 都必须保存 `CU1` 紧凑 ASCII 完整事实：普通/触发保存完整 15 格，免费保存完整 6 Step，Hold 保存从开始到终止的全部 Coin 状态。旧 `CU1PL;#` 标记禁止写入和消费。

Demo Init/Spin 只读上述正式缓存；普通、免费和 Hold 各自按六档实际总注倍数 `0、(0,5]、(5,20]、(20,50]、(50,100]、(100,10000]` 选择目标，换算到 Redis 单位后只向下找同一场景最近有效桶。禁止跨普通/Mary 或两种 Mary 混取。缓存空、member 非法或连接失败必须明确报错，不得现场生成。

fixtures 的唯一用途是协议回归 oracle：

- 禁止读取或轮播历史 Spin 响应作为运行时结果。
- 禁止按局号播放固定 LOSS/WIN 列表。
- 禁止把 fixture token、余额或 Round ID 当作运行时 session。
- 本地 Init 与 Spin 的牌面必须来自 Redis 中正式生成的完整事实；Balance 与 Session 由 Java 服务端根据该事实投影。

## 11. 下游验收入口

下游节点开始前必须读取：

1. `game-capabilities.json`
2. `protocol-handoff.json`
3. `protocol-spec.json` 与本文件
4. `evidence-matrix.json`
5. `reports/2350-Curupira/protocol-validation.json`

实现不得跳过任何 `CONFIRMED` behaviorId。B005/B006 的原站 wire 状态仍为 `UNKNOWN`，但本地工程投影必须按本规范生成并消费两种 Mary，同时明确不冒充原站已验证 wire；B007 Feature Buy 保持禁用/安全拒绝。


## 完整事实编码修订（2026-09-23）

旧独立无奖标记 `CU1PL;#` 已废止。它会导致消费缓存时重新造盘，不满足“包括 0 倍在内所有试玩结果均从 Redis 完整局读取”的要求。Loader 现在把 LOSS/WIN/主游戏 Expanding Wild 都编码为完整 15 格事实；解析器明确拒绝旧标记。更新顺序为先部署新 Controller，再由新 Loader 写入完整 member；旧标记只会被跳过，不会现场物化。
