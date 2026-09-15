# 57 — Crazy 777 协议与状态机规格

- 原生游戏：`57 — Crazy 777`
- 目录：`57-Crazy-777`
- 原生版本：`v1.5.10.250430`
- 协议修订：`protocol-r3-20260829`
- 规则哈希：`sha256:852d3021084c1f4117203c4a6eda137747bf9c1f689b30decb9791e46bf66f2b`
- 范围：当前游戏 HTTP 协议、字段语义、完整 Round、Delivery/Step 及 History 状态机；不包含 Java 实现。

## 身份与入口

平台大厅记录 `id=2518,g_id=455` 只用于调用平台启动接口；启动 URL 中的 `/57/`、查询参数 `gid=57`、前端 `_gameId` 和 provider 请求共同确认原生游戏 ID 为 `57`。平台 ID、平台 `g_id` 与原生 `gid` 不得混用。

启动顺序：

1. 平台游戏列表定位记录 `id=2518`。
2. 调用平台 startup，取得带敏感参数的游戏 URL；敏感值不得落入协议文档。
3. 首载 `/57/` 静态入口。
4. `POST /cp/api/v1/auth/verify`。
5. `POST /cp/api/v1/crazy-seven/config`。
6. 若配置存在未结束状态，前端恢复该状态；否则进入 `IDLE`。

## 传输与编解码

API base path 为 `/cp`，当前授权抓取观察到的 origin 为 `https://api.omgapibra.com`，实际 origin 来自入口 `sip` 参数。

- 请求：`application/x-www-form-urlencoded`。
- 响应：JSON envelope `{code,data,info}`。
- 成功：HTTP 2xx 且 `code` 数值或字符串等于 `200`，前端向上返回 `data`。
- 加密：provider payload 没有额外加解密层。
- 虚拟 header：调用层的 `web-token`、`game-id` 被 HTTP helper 映射为表单字段 `t`、`gid`，不是实际自定义 HTTP header。
- 敏感值：`t`、平台 token、cookie 和密码不得写入交付物。

## 端点

| 行为 | 方法与路径 | 主要表单字段 |
|---|---|---|
| Auth | `POST /cp/api/v1/auth/verify` | `t,gid` |
| Config | `POST /cp/api/v1/crazy-seven/config` | `t,gid` |
| Spin | `POST /cp/api/v1/crazy-seven/spin` | `bl,bs,t,gid` |
| Ping | `POST /cp/api/v1/ping` | `t,gid` |
| History list | `POST /cp/api/v1/crazy-seven/log-list` | `page_index,begin_at,end_at,t,gid` |
| History detail | `POST /cp/api/v1/crazy-seven/log-view` | `transfer_id,t,gid` |

未观察到 WebSocket，当前构建未发现购买端点或购买控件。

## 胜利与牌面模型

`winModel=FIXED_PAYLINES`，固定 5 线：

| 线键 | 三列位置代码 |
|---|---|
| `0` | `01,11,21` |
| `1` | `02,12,22` |
| `2` | `03,13,23` |
| `3` | `01,12,23` |
| `4` | `03,12,21` |

`wmkl` 的键 `5` 是 SC 触发奖伪键，不是第 6 条赔付线。前端内部 `PayLineCount=1` 只参与押注 UI 算术，不能用作线数。

`rskl` 长度为 15，按 reel-major 编码为三组各 5 个位置；每轴包含 2–3 个 `BLANK`。它不是普通平铺 3×3 数组。符号为 `H1..H6,WILD,SC,BLANK`；WILD 替代普通符号但不替代 SC。渐变替代对为 `H1→H4`、`H2→H5`、`H3→H6`。

客户端 `SymbolWeightsList=[5,10,15,20,25,30,10,10]` 只供 `randomReel` 动画背景使用，不是服务端结果权重；当前服务端权重保持未知。

## Spin 字段

| 字段 | 证据语义 |
|---|---|
| `bl`,`bs` | 下注 level/base；最小已用值为 `bl=1,bs=0.5` |
| `ba` | 本 Step 实际扣款；免费 Step 为 0 |
| `pb` | Step 后余额 |
| `wa` | 本 Step 奖金 |
| `rskl` | 15 项 reel-major 结果编码 |
| `wmkl` | 中奖线/SC 伪键到 symbol code 的映射；LOSS 可为空数组 |
| `rpx` | Step 赔付倍率；免费 Step 为 3 |
| `fsn` | 本 Round 获得/总免费次数；普通局为 0，触发后为 10 |
| `nfsc` | 已消费免费次数 |
| `ss` | Step 结束标识，不能单独作为 Round 终止条件 |

赔付复核公式为 `sum(spl[wmkl[key]] * bl * bs * rpx)`。配置 `spl` 的当前值：`H1=50,H2=25,H3=15,H4=6,H5=5,H6=3,MIX=1,SC=7,WILD=500`。

## 完整 Round / Delivery / Step

上游 Spin 响应没有观察到原生 `roundId/deliveryId/stepId`；抓取索引中的 `roundId`、`stepIndex` 是采集关联字段，不得伪装成原站字段。

状态转换：

1. `IDLE → PAID_REQUESTED`：接受一个付费 Spin，完整局计数加 1。
2. `PAID_REQUESTED → ORDINARY_TERMINAL`：`fsn==0`。
3. `PAID_REQUESTED → FREE_TRIGGER_DELIVERED`：`fsn=10,nfsc=0`；即使触发 Step 的 `ss=1`，Round 仍未结束。
4. `FREE_TRIGGER_DELIVERED → FREE_CONTINUATION`：后续 Step `nfsc=1..9,ss=0,rpx=3,ba=0`。
5. `FREE_CONTINUATION → FREE_TERMINAL`：`nfsc=10,ss=1,rpx=3`。
6. `PAID_REQUESTED → AGGREGATED_FREE_TERMINAL`：单响应已同时为 `fsn=10,nfsc=10,ss=1,rpx=3`；实际观察到 7 局。

Round 合法终止条件：`fsn==0 OR (fsn==nfsc AND ss==1)`。

3 个 SC 触发 10 次免费 Spin，沿用触发押注，免费中奖倍率为 3；规则和 48 个完整免费 Round 均未证明可重触发。41 局为 11 Step，7 局为单响应聚合终局。免费 Step 只能关联到触发它的付费 Round，不能再次计局。

后续 Java 实现必须一次生成完整中奖/特殊 Round，只领取一次 member；Delivery 只消费 `pendingRound.nextStep`。能力明确支持的普通单步 LOSS 可实时生成，但不得在活动 Round 中临时拼装结果。fixtures 只可作为独立 oracle。

## History 状态机

同一次 `abc224` 隔离 UI 交互已捕获以下相邻序列：

1. `log-list(page_index=1,begin_at,end_at,t,gid=57)`，响应 `code=200`，数据键为 `ba,end,lc,ll,wa`，本次 `ll` 有 10 项。
2. 选择真实 `ll[0].tis=2093578569416081408`，将其设为 `CurId`。
3. History 容器调用一次 `log-view(transfer_id=CurId)`。
4. 详情 prefab 初始化时再次调用相同 `transfer_id` 的 `log-view`；两次均 `code=200`，本次数据键为 `baf,bid,bsl`，`bsl` 一项、无 `fsl`。
5. 前端按 `bsl` 后接 `fsl` 的顺序构造详情 Step。
6. 关闭详情时清空 `CurId` 并派发 `HISTORY_DETAIL_BACK`。
7. 可观察状态由 `activeDetailCount=1,listOpacity=0` 转为 `activeDetailCount=0,listOpacity=255`；所选条目仍可见，且没有新的 `log-list` 请求。

分页在响应 `end` 为 truthy 时终止。详情在 `HISTORY_DETAIL_BACK` 后详情节点销毁且已有列表恢复时终止。

相邻原始证据：`captures/57-Crazy-777/history-adjacent-evidence.json`；脱敏 oracle：`fixtures/57-Crazy-777/history-list-adjacent.json`、`history-detail-adjacent.json`。

## 明确未知与禁止推断

- 原站 Redis Key、member payload codec 和编码长度没有当前游戏证据，保持未知，不得把其他游戏的原站 Redis 合同写成当前游戏抓包事实。
- 复刻 Demo 使用平台缓存 `192.168.10.3:6379/15`，当前游戏 ASCII member 前缀 `C777A1`，Key 为 `PerKeyList_000000057` / `MaryKeyList_000000057` 与对应倍率 List；这是复刻运行时合同，不是原站抓包字段。
- 服务端真实符号权重未知；不得使用客户端动画权重。
- 未发现购买模式、玛丽模式或 WebSocket；不得为其生成结果。
- 抓包响应和 fixtures 禁止成为运行时结果源。

## 实现前必读

后续节点必须同时读取 `game-capabilities.json`、`field-evidence-matrix.json` 与 `protocol-handoff.json`，按其中完整 behavior ID 集逐项实现和独立验证。任何测试预期必须来自本规格引用的当前游戏规则、前端代码、原始抓包或独立 ResultUtil，`usesImplementationGeneratedExpected` 必须为 `false`。
