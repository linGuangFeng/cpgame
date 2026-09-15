# Crazy Piggy（56）协议与状态机规格

- ID-name：`56-Crazy-Piggy`
- 当前前端/规则版本：`v1.5.10.250430`
- 规则哈希：`7edd2945da54923875932aa9feb1bfd657c9c67cca832637060f8419573480ab`
- 能力来源：[game-capabilities.json](game-capabilities.json)
- 行为交接：[protocol-handoff.json](protocol-handoff.json)
- 机器可读完整规格：[protocol-spec.json](protocol-spec.json)

本文件供后续 Java 实现与独立验收核对。fixtures 只能作为协议 oracle，不得成为运行时结果源。

## 1. 入口与请求顺序

真实游戏名称由大厅入口、`gid=56`、页面标题和 `GameGlobalConfig.GameName="Crazy Piggy"` 共同确认。大厅目录 ID `454` 不是 CP 游戏 ID。

必需顺序：

1. `POST /cp/api/v1/auth/verify`
2. `POST /cp/api/v1/crazy-piggy/config`
3. `POST /cp/api/v1/crazy-piggy/spin`

入口 URL 的 launch token 只用于 `auth/verify`。`verify.data.token` 是后续 Config、Spin、History 和可选 Ping 使用的 runtime token。使用错误角色 token 调 Spin 的已捕获结果为 HTTP 200、`dt=null`、`err.cd="1002"`，该失败请求不形成付费 Round。

## 2. 编码契约

- 方法：HTTP POST over HTTPS
- 请求 Content-Type：`application/x-www-form-urlencoded`
- 字符编码：UTF-8 URL form encoding
- 调用别名：`web-token -> t`、`game-id -> gid`
- 成功响应：JSON `{code:200, info:"ok", data:{...}}`
- 应用层加密/签名：当前代码和抓包均未发现

`Http.js` 将所谓 Header 别名写入表单体，并不发送为真实 HTTP Header。

## 3. 端点字段

### auth/verify

请求字段：

| 字段 | 含义 |
|---|---|
| `ai` | 当前入口应用标识；环境值，不得跨环境硬编码 |
| `btt` | 当前采样为字符串 `1`；更深语义未确认 |
| `t` | launch token |
| `gid` | 固定为 `56` |

关键响应字段：`player.id`、`player.balance`、`token`、`ping.enable`、`ping.seconds`。当前 `ping.enable=0`，所以心跳未实际启用。

### config

请求字段：`t`、`gid`。

| 字段 | 语义 |
|---|---|
| `auto` | 自动局数列表 `[10,30,50,100,500]` |
| `bsl` | Bet Size `[0.5,5,50]` |
| `bll` | Bet Level `[1..10]` |
| `dbs` | 默认 Bet Size，捕获为 `0.5` |
| `dbl` | 捕获为 `50`；不在 `bll` 时前端回退索引 0，禁止解释为赔付线数 |
| `cc/cs` | `BRL` / `R$` |
| `spl` | 当前 8 个符号的每线赔付表 |
| `last` | 最近结果快照；只恢复展示和下注状态，不创建新 Round |
| `ts` | 服务端 Unix 秒时间 |

### spin

请求字段：`t`、`gid=56`、`bl`、`bs`。最低押注为 `bl=1, bs=0.5`，总投注 `ba=bs*bl=0.5 BRL`。

| 响应字段 | 语义 |
|---|---|
| `ba` | 本付费 Round 总投注额 |
| `pb` | 完整 Round 扣注并计入全部派奖后的余额 |
| `rskl` | 长度 9、按列展开的 3×3 棋盘 |
| `wa` | 完整 Round 总派奖 |
| `wmkl` | 线路号到中奖符号的映射；可能是空数组、稀疏对象或五线全中的数组 |
| `gm` | `0` 普通，`1` Booster Wheel |
| `small_game_type` | 当前样本中 `0` 普通、`2` Booster Wheel |
| `fwa` | Booster 相对初始五线奖的增量派奖 |
| `fwtl` | 有序轮盘落点；最后一项是终止落点 |
| `fwxl` | 每个非终止轮盘 Step 的派奖倍率，长度为 `fwtl.length-1` |

`rpx`、`rdri`、`fbt` 只存在于通用代码映射，当前规则和 1392 个 Spin 响应都未实例化，不属于已确认能力。

### history

- 列表：`POST /cp/api/v1/crazy-piggy/log-list`
  - 请求：`page_index`、`begin_at`、`end_at`、`t`、`gid`
  - 代码逻辑：`end=0` 时继续下一页，列表项 `tis` 用于详情
- 详情：`POST /cp/api/v1/crazy-piggy/log-view`
  - 请求：`transfer_id=tis`、`t`、`gid`

端点、请求顺序与前端消费字段已由当前代码确认，但没有当前游戏原始 History 响应，因此 `B-HISTORY-READ` 状态为 `UNRESOLVED`。不得把代码默认值或其他游戏字段当成 wire schema。

## 4. 棋盘和五条固定赔付线

棋盘为 3 列×3 行，`rskl` 按列展开：`flatIndex=column*3+row`。

| 线路 | 代码 | 每列行号 | rskl 索引 |
|---|---|---|---|
| 0 | `PL0001` | `[0,0,0]` | `[0,3,6]` |
| 1 | `PL0002` | `[1,1,1]` | `[1,4,7]` |
| 2 | `PL0003` | `[2,2,2]` | `[2,5,8]` |
| 3 | `PL0004` | `[0,1,2]` | `[0,4,8]` |
| 4 | `PL0005` | `[2,1,0]` | `[2,4,6]` |

每线奖：`spl[symbol] * bs * bl`。普通局总奖为 `wmkl` 中每条线路奖之和。200 个普通赢局全部复算一致。

符号赔付：`HOT=200, SEV=50, H2=20, H3=10, H4=8, H5=5, H6=3, H7=1`。

规则奖表有一条通用 Wild 文字，但当前 `spl`、`SymbolList` 与 1392 局均无 `WILD`，当前生成器不得输出 WILD。

## 5. Round / Delivery / Step 状态机

一个完整 Round 从一次授权付费 Spin 开始，包含其响应中的全部 Delivery Step，直到前端回到 Wait。

```text
SESSION_UNVERIFIED
  -> auth/verify success
  -> config success
SESSION_READY
  -> paid spin
PAID_REQUEST_IN_FLIGHT
  -> gm=0 -> ORDINARY_RESULT_DELIVERY -> ROUND_COMPLETE
  -> gm=1 -> BOOSTER_BASE_DELIVERY -> BOOSTER_WHEEL_STEP*
             -> fwtl exhausted -> ROUND_COMPLETE
ROUND_COMPLETE -> frontend Wait -> SESSION_READY
```

全部 1392 个捕获 Round 都只有一个 Spin HTTP 请求。Booster Wheel 的 `fwtl/fwxl` 是同一响应中的本地 Delivery Step，不能成为新的 Spin、免费 Spin、Redis member 或 `spin-index.jsonl` 行。

余额原子公式：`nextPb = previousPb - ba + wa`。1304 个相邻转换零偏差。Booster 的 `fwa` 已包含于 `wa`，不得重复入账。

## 6. 普通 LOSS 与 WIN

- 普通 LOSS：`gm=0, small_game_type=0, wa=0, wmkl=[]`
- 普通 WIN：`gm=0, small_game_type=0, wa>0`，按 `wmkl` 五线公式复核

Controller 不实时生成任何结果。每个付费起点先选择 LOSS/WIN/SPECIAL，再从 Redis DB15 对应索引的现有整数倍率领取一个 CP56A1 极简 ASCII 完整局；空池或连接失败返回 HTTP 503。

## 7. Booster Wheel

触发：九格同一符号，且符号不是 `HOT` 或 `SEV`。

- `gm=1`
- `small_game_type=2`
- 初始奖：`base = 5 * spl[symbol] * bs * bl`
- 增量奖：`fwa = base * sum(fwxl)`
- 总奖：`wa = base + fwa`
- 长度：`fwtl.length = fwxl.length + 1`

39/39 个完整轮盘局满足触发、长度和奖金公式。代表局：

- `cp56-00105`：`fwtl=[0,4,6,3,7,5]`、`fwxl=[1,1,1,2,2]`、`base=20`、`fwa=140`、`wa=160`
- `cp56-00178`：`fwtl=[0,6,4,2,1]`、`fwxl=[1,1,2,2]`、`base=25`、`fwa=150`、`wa=175`

规则写明最高 21 倍且前端存在高倍率展示分支，但39局仅捕获 `fwxl=1/2`。该子分支标记 `RARE_NOT_CAPTURED_CAP_POLICY`，不继续抓取、不伪造，也不阻塞后续流程。

## 8. 样本覆盖与实现边界

| 类别 | 完整 Round | 目标 | 状态 |
|---|---:|---:|---|
| 普通 LOSS | 1153 | 200 | `TARGET_MET` |
| 普通 WIN | 200 | 200 | `TARGET_MET` |
| BOOSTER_WHEEL | 37 | 30 | `TARGET_MET` |

- paid Round 起点：1392
- 合法完成：1392
- 停止原因：`ALL_APPLICABLE_TARGETS_REACHED`
- 最大允许付费 Round：5000

Redis 只确认一个 member 必须覆盖一个完整 Round；当前没有 Redis Key、编码或长度证据，均保持 UNKNOWN。服务端生成权重也保持 UNKNOWN；前端动画权重不得用于正式 RNG。

后续实现必须逐项读取 `protocol-handoff.json` 的 behaviorId，并使用规则、前端代码或原始抓包作为独立预言机，不能以待验收实现自产结果作为唯一预期。

