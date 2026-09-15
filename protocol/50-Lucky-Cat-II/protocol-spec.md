# Lucky Cat II（ID 50）协议与状态机规范

状态：`PROTOCOL_ACCEPTED_READY_FOR_IMPLEMENTATION`  
规则哈希：`4fcb4da38b457ee89e699b66ae9e258af2c73a622fc324202f20b7d7957c95f6`  
哈希输入：`protocol/50-Lucky-Cat-II/rules-core-canonical.json`

## 1. 身份与证据边界

真实 ID-name 为 `50-Lucky-Cat-II — Lucky Cat II`。身份同时由入口标题、`GameConfig._gameId=50`、前端 `GameName` 和英文规则标题确认。

本规范只使用当前游戏的规则文本、奖表、前端判定代码、Config、Spin、History 与完整局索引。`fixtures` 仅是独立验收 oracle，禁止成为 Java 运行时结果源；不得轮播、随机抽取、复制或硬编码其中的结果。

当前静态发布验收已 PASS，本节点没有修改 `publish/50-Lucky-Cat-II`。

## 2. 传输、主机与鉴权

### 2.1 API 主机

共享 `versionconfig.js` 读取入口参数 `sip`：

- 有 `sip`：`window.GameUrl = {入口协议}://{sip}/cp`。
- 无 `sip`：原前端落到 `http://127.0.0.1:9500/cp`；这是公开首载/开发回退，不是原生产 API 证据。
- 本次认证入口的 `sip=api.omgapibra.com`，实际 API base 为 `https://api.omgapibra.com/cp`。

### 2.2 编码与加解密

- 方法：全部游戏 API 为 HTTP POST。
- Content-Type：`application/x-www-form-urlencoded`。
- 编码：`encodeURIComponent`，空格替换为 `+`。
- 逻辑鉴权字段：`game-id`、`web-token`。
- 线上表单字段：`game-id -> gid`，`web-token -> t`。
- 请求加密：无。
- 响应加密：无。
- 响应包装：明文 JSON `{code, info, data}`。

前端只把上述两个逻辑头映射到表单；其他未映射的“头”不会被真正发送。实现端应直接兼容线上表单字段 `gid/t`，不要添加未经证据确认的 AES、DES、签名或二次编码。

### 2.3 通用成功与错误路径

- HTTP 2xx 且 `code == 200`：resolve `data`，无 `data` 时 resolve `{}`。
- 业务 `code != 200`：reject 原解析包装。
- 非 2xx：reject；若响应不是可识别 JSON 或没有 `code`，生成 `code=HTTP<status>`、`info=''`。
- `code=401`：停止心跳。
- Spin 失败：恢复余额显示、停止自动局、调用游戏错误收尾并解锁 Spin。
- Ping 的 `HTTP0`：按原间隔重试；其他 Ping 错误显示给用户。

当前没有主动制造 provider 错误，故具体 `info` 文案未知；实现必须透传，禁止自造错误文本。

## 3. 请求顺序与端点

当前认证流程的确定顺序是：

```text
portal startup
  -> game entry(gid=50,sip,t,...)
  -> POST /api/v1/auth/verify
  -> POST /api/v1/golden-cat/config
  -> POST /api/v1/golden-cat/spin（用户付费开始一局）
  -> History list/view（用户打开历史时）
  -> Ping（仅 ping.enable 为真；本次为 0，因此不发）
```

### 3.1 Session v2

- 端点：`POST /api/v1/auth/verify`。
- 选择条件：入口 `t` 非空，`Platform.getCurVersion()` 返回 v2。
- 请求：入口平台参数作为表单；当前入口包含 `ai/btt/t`，逻辑 `game-id` 映射为 `gid`。凭证/token 在证据中均已脱敏。
- 已确认响应字段：`player.id`、`player.balance`、`token`、`ping.enable`、`ping.seconds`，以及本次摘要中的 `rc/gc`。
- 成功后：更新 PlayerData、保存当前游戏 token、设置 HeartMgr，然后才允许 Config。
- 失败后：显示错误并停止后续 Config/Spin。

### 3.2 Session v1

- 端点：`POST /api/v1/auth/session`。
- 选择条件：入口没有 `t`。
- 当前状态：只由前端分支与公开无 `t` 首载确认，未取得生产认证 wire response。
- 处置：不得把 v1 当作已确认的生产会话返回结构；本地复刻的当前主路径应实现已确认的 v2 契约。

### 3.3 Config

- 端点：`POST /api/v1/golden-cat/config`。
- 请求：`gid/t`。
- 响应字段：
  - `auto=[10,30,50,100,500]`
  - `bll=[1..10]`
  - `bsl=[0.1,1,10]`
  - `dbl=10`、`dbs=0.1`
  - `cc=BRL`、`cs=R$`
  - `spl={WILD:80,S1:25,S2:20,S3:15,S4:7,S5:5,S6:2}`
  - `last`：上次结果显示恢复
  - `ts`：服务端时间戳
- 最小合法付费：`bl=1, bs=0.1, ba=bl*bs*5=0.5 BRL`。

`bl` 是 Bet Level，不是中奖线数量；固定中奖线数量独立为 5。

### 3.4 Spin

- 端点：`POST /api/v1/golden-cat/spin`。
- 请求：`bl, bs, gid, t`。
- 直接 Spin 响应确定字段：`ba, gm, pb, rdri, rdskl, rpx, rskl, wa, wmkl`。
- History detail 扩展字段：`baf, bid, bl, bs, ca, tis`。
- `tis/bid/ca` 在直接 Spin 返回中可缺失，不得强制伪造。

### 3.5 History list

- 端点：`POST /api/v1/golden-cat/log-list`。
- 请求：`page_index, begin_at, end_at, gid, t`。
- 前端分页：`end=0` 时页码加一；否则置为终页。
- 原前端消费：`lc` 汇总、`ll` 局记录，`ll.tis` 用于打开详情。
- 列表货币字段为字符串；不要强制转成 Spin 的 number 类型。

`fixtures/history-list.json` 是把多页结果合并后的脱敏 oracle，使用 `query/summary/records` 包装，不应误认为线上原始单页 JSON 结构。

### 3.6 History view

- 端点：`POST /api/v1/golden-cat/log-view`。
- 请求：`transfer_id=<list.ll.tis>, gid, t`。
- 响应：完整局详情。1365 条证据均满足 `bid='50-'+tis`，且 `Number(baf)=pb`。
- 用途：历史展示与抓取中断窗口的付费 Round 对账；仍然只作 oracle。

### 3.7 Ping

- 端点：`POST /api/v1/ping`。
- 请求：空业务参数加 `gid/t`。
- 启动：Session 的 `ping.enable` 为真时，每 `ping.seconds` 秒发送。
- 本次：`ping.enable=0`，所以合法行为是 0 个 Ping 请求；不是抓取缺失。

## 4. 牌面与中奖模型

### 4.1 牌面编码

- 3 reels × 3 rows，共 9 个可见格。
- `rskl` 按 reel-major 平铺：`reel0[row0,row1,row2], reel1[...], reel2[...]`。
- 符号：`WILD,S1,S2,S3,S4,S5,S6`。
- 前端滚动缓冲格不属于结果协议，不得把动画缓冲当可见结果格。

### 4.2 五条固定中奖线

每个数组依次给出 reel0、reel1、reel2 的 row：

| Line | Rows |
|---|---|
| 1 | `[0,0,0]` |
| 2 | `[1,1,1]` |
| 3 | `[2,2,2]` |
| 4 | `[0,1,2]` |
| 5 | `[2,1,0]` |

从左到右三个符号匹配即中奖。WILD 替代所有非 WILD；全 WILD 线按 WILD 支付。`wmkl` 的键是中奖线号，值是该线支付符号。每条线只付最高奖。

### 4.3 奖金公式

```text
lineAward(line) = spl[wmkl[line]] * bs * bl * rpx
wa = sum(lineAward for each wmkl line)
```

独立 oracle 对 1565 局重算：

- `wmkl` 牌面判定不一致：0。
- `wa` 公式不一致：0。
- 最大浮点误差：`7.105427357601002e-15`。

## 5. Round / Delivery / Step 状态机

### 5.1 计数边界

- 一次真实付费 `POST /spin` 是一个 Round 起点。
- 一个响应交付完整 Round。
- `spin-index.jsonl` 每行只对应一个付费 Round 起点。
- 免费 Lucky Respin S02 和 Wheel 展示不能另计 paid Round。
- 本次 1565 个 paid starts = 1565 个 completed Rounds；总逻辑牌面 Step 为 1615，其中 50 个为免费 S02。

### 5.2 总状态机

```text
IDLE
  -- paid POST /spin --> ROUND_DELIVERED
      | gm=0
      |   -> S01_PAID_BOARD
      |   -> [rpx>1 ? WHEEL_AWARD : no wheel]
      |   -> ROUND_TERMINAL
      |
      | gm=1
      |   -> S01_PAID_INITIAL_BOARD
      |   -> S02_FREE_LUCKY_RESPIN_FINAL
      |   -> [rpx>1 ? WHEEL_AWARD : no wheel]
      |   -> ROUND_TERMINAL
      |
      `-- error -> ROUND_NOT_ACCEPTED / UI_RECOVERY
```

这里的 `WHEEL_AWARD` 是同一 Delivery 内的展示/结算阶段，不是另一牌面 Spin Step，因此 spin-index 的 Wheel 普通局仍为 `stepCount=1`。

### 5.3 普通局

- 条件：`gm=0`。
- S01 牌面：`rskl`。
- 普通 LOSS：`gm=0,rpx=1,wa=0,wmkl={}`。
- 普通 WIN：`gm=0,rpx=1,wa>0`。
- 终止：S01 后立即终止；若 `rpx>1`，先消费同响应 Wheel 阶段再终止。

已捕获普通 LOSS 1272 局、普通 WIN 200 局，均达到每类 200 目标。

## 6. Lucky Respin

### 6.1 入口与相邻步骤

- 协议入口：`gm=1`。
- `rdri`：0-based 的唯一重转 reel。
- `rdskl`：付费初始牌面上 `rdri` 的 3 个符号。
- `rskl`：免费 Lucky Respin 后的最终 9 格牌面。

相邻步骤构造：

```text
S01 board = copy(rskl); S01.reel[rdri] = rdskl
S02 board = rskl
```

除 `rdri` 外的两个 reel 在 S01/S02 必须完全相同。S01 `paid=true`，S02 `paid=false`，两者使用同一 `roundId`。

### 6.2 触发形态

规则文本要求：未中奖时，任意两个 reel 堆叠相同符号和/或 WILD。

完整样本进一步确认了 wire 平铺方向上的必要且在当前样本中充分的形态：锁定 reel 为零个或多个前缀 `WILD`，随后全部为同一个兼容符号；全 WILD reel 也兼容。后缀 WILD 的近似牌面未触发。把“无付费线奖 + 两个兼容前缀-WILD 堆叠 reel”作为分类器检查全部 1565 局，和 `gm` 的不一致数为 0。

该方向性来自当前样本，不应被改成对称 Wild 猜测。

### 6.3 终止

前端 `GameResults` 构造时保存最终 `rskl` 的 rdri reel、先显示 `rdskl`，随后 `refreshRespinResult` 只执行一次替换。没有第二个 Spin HTTP，也没有递归重触发。

因此即使 S02 最终形态仍像可触发牌面，也必须终止。已捕获 50 个完整 Lucky Round，全部严格为 S01+S02，0 个相邻牌面错误。

## 7. Multiplier Wheel

### 7.1 入口与触发

- 协议入口：`rpx>1`。
- 触发牌面：最终 `rskl` 的 9 格仅包含同一个非 WILD 符号和/或 WILD。
- 1565 局中，`rpx>1` 与该牌面条件双向完全一致，0 个不一致。

### 7.2 结算与边界

- `rpx` 同时乘到每条中奖线与总 `wa`。
- 捕获倍率：`2,3,4,5,10`；规则上限 10。
- Wheel 与 Lucky Respin 可重叠，本次有 5 局；此时 Wheel 消费 S02 最终牌面和同一响应的 `rpx/wa`。
- Wheel 不产生新 Spin、不产生新 paid Round，也不在 spin-index 新增牌面 Step。

规则明确声明转盘图形分布不是实际概率。倍率权重和 Wheel 触发概率保持 UNKNOWN，禁止按 48 局频率生成配置。

## 8. 运行时独立 LOSS

当前能力允许 `gm=0,rpx=1,wa=0,wmkl={}` 的普通付费独立单步 LOSS 实时生成。它必须来自正式 Java `GameRuleCore` 的随机生成，并由独立 `ResultUtil` 再验证；不得从 fixtures 取得牌面或响应。

一旦 Round 已激活 Lucky/其他后续阶段，就不能临时生成或替换下一 Step；完整 Round 必须在一次生成中确定。

## 9. 语言、特殊入口与网络能力

- 实际语言：`en,pt,es,th,vi,id,bn,ko,fr,tr`。
- 参数：`l`；游戏默认 `pt`。
- 门户 `spa` 不受支持，保留 `pt`，不能映射为 `es`。
- 购买入口：规则、bundle 与认证运行树均未发现，`NOT_APPLICABLE_NOT_DISCOVERED`。
- WebSocket：11 次首载 0 连接，main bundle 0 token 命中；当前接口模型是 XHR POST。

## 10. 未确认项与禁止推断

以下内容没有当前游戏权威证据：

- 服务端 reel strip 与符号生成概率。
- Wheel 倍率概率分布。
- Redis key、编码、固定长度。
- v1 Session 的生产 wire 返回结构。
- provider 专属错误信息文案全集。

前端 `SymbolWeightsList=[10,15,15,15,20,20,30]` 只被 `randomReel` 用来构造视觉滚动填充条，不能成为 Java 结果概率。

平台复刻合同 v3 已指定 Demo 结果源为 Redis `192.168.10.3:6379` db 15。Key：

- 普通索引 `PerKeyList_%09d`，列表 `BetLog:0%08d:%06d`
- 特殊索引 `MaryKeyList_%09d`，列表 `MaryLog:%09d:%06d`

整数倍率 = `wa/(bs*bl)`（赔付表全为整数，该值可精确整除）。0 倍未中奖写入普通 0 池；Lucky Respin / Wheel 写入 Mary 池。Member 为 `LC50A1` ASCII，不是 JSON。Controller 每次付费 Spin 只领取一条完整局并投影到当前 `bs/bl`。

## 11. 下游实现门槛

后续 Java 实现开始前必须同时读取：

- `game-capabilities.json`
- `protocol-handoff.json`
- `capture-field-evidence-matrix.json`
- 本规范

实现与测试必须逐个覆盖 handoff 的完整 `behaviorIds`。测试预期来自规则、前端判定、原始抓包或独立 oracle，`usesImplementationGeneratedExpected` 必须为 `false`。正式运行链路不得读取 fixtures。
