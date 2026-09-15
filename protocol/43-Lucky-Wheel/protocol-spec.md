# 43-Lucky-Wheel 协议与状态机规范

- 状态：协议分析完成，等待独立协议验收；验收通过前不可进入 Java 实现，未捕获分支必须按本文的生成限制处理
- 游戏身份：`43-Lucky-Wheel`（ID `43`，名称 `Lucky Wheel`）
- 原站入口：`https://hms-paddle.com/`
- 静态入口：`https://static.cpgame.io/43/`
- 观察到的 API 主机：`api.omgapibra.com`
- 当前规则版本：`v1.5.10.250430`
- rulesHash：`sha256:5BD756F5F541F6577977BA3A6FD309AED6BCC45730150E4A687B488157E595D1`
- rulesHash 来源：原始规则资源 `assets/resources/import/07/071e7ce22.e91b6.json` 的 SHA-256

## 1. 身份、棋盘和中奖模型

门户目录、`versionconfig.js`、静态标题和 bundle 中 `_gameId=43` 共同确认真实 ID-name 为 `43-Lucky-Wheel`。

当前游戏不是 Ways 或 Cluster。bundle 的 `GameConfig` 明确给出：

- `PayLineCount=1`；
- `RowCounts=[1,1,1,1]`；
- `ColumnCount=4`；
- 前三列是数字奖励轮，第四列是特殊轮；
- 最小押注时第三个数字奖励轮被 `ThreeRollLockStatus` 锁定，所以 5087 个最小押注样本的 `rskl` 均为长度 2；这不改变四列单行和固定一条线的视觉模型。

数字符号映射来自当前 bundle 的 `GameSymbolNums`：

| 符号 | 数字文本 |
|---|---|
| `H0` | 空白，不参与拼接 |
| `H1` | `0` |
| `H2` | `00` |
| `H3` | `1` |
| `H4` | `5` |
| `H5` | `10` |

`score(symbols)` 定义为：按数组顺序将上述数字文本拼接，忽略 `H0`，空结果为 `0`，再按十进制整数解释。规则文本明确空白不改变数字顺序，最左侧代表高位。

最小押注样本只观察到 `H0/H1/H3/H4/H5`；`H2` 的高押注可用性来自规则/代码，不能据此推断最小押注权重。

## 2. 传输、编码和响应封装

前端 `Http` 模块使用 HTTPS 上的普通 XHR：

- 默认 `POST` Content-Type 为 `application/x-www-form-urlencoded`；
- key/value 使用 `encodeURIComponent`，空格转为 `+`；
- 逻辑 header `web-token` 被复制到表单字段 `t`；
- 逻辑 header `game-id` 被复制到表单字段 `gid`；
- 未观察到应用层加密、签名、压缩或二进制 codec；TLS 不属于应用层 codec；
- 响应是 JSON 包装 `{code, data, info}`，成功样本的 `code=200`、`info="ok"`；前端成功后向业务层返回 `data`。

敏感的 `t`、门户 token、Cookie 和口令没有落盘。后续实现不得从 fixture 取得或复用凭据。

## 3. 启动与端点顺序

观察到的启动顺序：

1. 已认证门户调用 `GET /api/game/startup?game_id=2484`。`2484` 是门户重复卡片 ID，返回的游戏 URL 指向静态 ID `43`；目录主条目 ID 为 `1399`。
2. iframe 参数提供 `ai/btt/gid/l/sip/t` 等启动信息。
3. `POST https://api.omgapibra.com/cp/api/v1/auth/verify`，表单 `ai,btt,t,gid=43`。
4. 成功响应的 `data.token` 作为游戏 token，仅内存使用。
5. `POST /cp/api/v1/lucky-wheel/config`，表单 `t,gid=43`。
6. 配置确认 `bll=[1,5,10,50,100]`、`bsl=[1]`；原5000局为 `bl=1,bs=1`，定向补采另使用原UI选择的 `bl=5,bs=1` 完整Round 100局。
7. 每次用户/自动付费开始调用 `POST /cp/api/v1/lucky-wheel/spin`。

完整 auth/config 响应没有作为原始 fixture 持久化；除上述由抓取工具实际检查的字段外，其余字段保持未知。

## 4. Spin 请求与字段矩阵摘要

请求表单：

| 字段 | 类型 | 语义 |
|---|---|---|
| `bl` | 数字文本 | Bet Level；本次最小值 `1` |
| `bs` | 数字文本 | Bet Size；本次唯一观察值 `1` |
| `t` | 敏感字符串 | 当前游戏 token，由逻辑 header 映射 |
| `gid` | 数字文本 | 游戏 ID，固定 `43` |
| `ec` | 字符串，条件字段 | 活动免费游戏代码；当前账号样本未使用，语义只来自前端条件代码 |

Spin 响应 `data` 的 5087 条样本字段全部存在且类型稳定：

| 字段 | 类型 | 已确认语义/约束 |
|---|---|---|
| `ba` | number | 本局扣注金额；最小样本恒为 `1` |
| `bl` | number | 本局 Bet Level，恒为 `1` |
| `bs` | number | 本局 Bet Size，恒为 `1` |
| `ca` | number | 服务端创建时间，Unix 秒 |
| `gt` | number | 结果类型常量，样本恒为 `1`；不得与门户 game ID `43` 混同 |
| `small_game_type` | number | 样本恒为 `0`；其他取值未知 |
| `md` | number | 模式：`0` 基础、`1` 倍率轮、`2` 响应内重转、`3` Lucky Wheel（仅静态确认） |
| `rskl` | array<string> | 基础数字轮符号；最小押注样本长度 2 |
| `wskl` | array<string> | `rskl` 中去掉 `H0` 后的非空符号；5087/5087 成立 |
| `fws` | string | 特殊轮结果：样本为 `H0/2/5/RS` |
| `fsk` | string | 与 `fws` 相同；5087/5087 成立，独立业务差异未观察到 |
| `rpx` | number | 最终作用于基础数字的倍率；样本为 `1/2/5` |
| `fwi` | array<string> | `md=2` 时的免费重转数字轮结果；其他已捕获模式为空数组 |
| `fwa` | number | 特殊模式追加金额；`md=2` 时等于 `score(fwi)`；`md=3` 前端将其作为 Lucky Wheel 数字 |
| `wa` | number | 本 Round 总派奖 |
| `pb` | string | Round 结算后余额，十进制字符串 |
| `ss` | 条件字段 | 5087 条样本均缺失；bundle 只在字段存在时读取，`ss=0` 表示仍有消除续步 |
| `wmkl/rwa/frwa/fbt` | 条件字段 | bundle 支持但本次 5087 条 Spin 均未出现；不得生成或猜测 |

## 5. 已确认派奖公式

### 5.1 基础模式 `md=0`

`rpx=1`、`fws=fsk="H0"`、`fwi=[]`、`fwa=0`，且：

`wa = score(rskl)`

该公式在 4104/4104 个 `md=0` Round 成立。其中 `wa=0` 为 3884 局，`wa>0` 为 220 局。

例：`R43-000014` 的 `rskl=["H4","H1"]`，拼接为 `50`，`wa=50`。

### 5.2 倍率轮 `md=1`

`fws=fsk` 为倍率文本 `"2"` 或 `"5"`，`rpx` 为对应数值，`fwi=[]`、`fwa=0`，且：

`wa = score(rskl) * rpx`

该公式在 896/896 个 `md=1` Round 成立。`rpx>1` 与 `md=1` 在当前样本完全重合，因此 `RPX_GT1` 是 `md=1` 的覆盖别名，不是独立模式。

例：

- `R43-000010`：`55 * 2 = 110`；
- `R43-000025`：`55 * 5 = 275`。

### 5.3 响应内重转 `md=2`

`fws=fsk="RS"`、`rpx=1`、`fwi` 为第二次数字结果，且：

- `fwa = score(fwi)`；
- `wa = score(rskl) + fwa`。

两条公式均在 87/87 个 `md=2` Round 成立。

例：`R43-000040` 中 `rskl=["H5","H4"] => 105`，`fwi=["H0","H4"] => 5`，所以 `wa=110`。

`md=2` 是一个 HTTP 响应内的两阶段动画，不会触发第二个 Spin 请求。前端在 `onGameEnd` 中调用 `refreshRespinList()` 把同一响应的 `fwi` 切换为展示结果；`onPlayBet()` 在 `IsFreeGame=true` 时不发请求。

旧抓取器错误地把 `md=2` 视为跨请求 continuation，并立即再次调用相同的付费 `spin()`。相邻证据 `captureSeq 40 -> 41` 显示后一个响应余额从 `14255.99` 变为 `14254.99`，即按 `-ba(1)+wa(0)` 结算，证明它是新的付费 Round。当前 `spin-index.jsonl` 已按这一结论重建，一行一个真实请求起点。

### 5.4 Lucky Wheel `md=3`

规则和 bundle 确认存在 `md=3 / MODE_FEATURE_SCAT`：基础数字金额加 Lucky Wheel 奖励。原始 UI 在押注等级 5 明示 `APOSTE 5 DESBLOQUEIA SCATTER`。固定 100 个 `bl=5,bs=1,ba=5` 完整 Round 的补采中出现 2 个原始 `md=3` 响应：`fws=fsk="SCAT"`、`rpx=1`、`fwi` 为标量 Lucky Wheel 奖励、`fwa=fwi`，且：

- `R43-BET5-020`：`score(["H4","H4","H3"])=551`，`fwi=fwa=150`，`wa=701`；
- `R43-BET5-040`：`score(["H4","H1","H1"])=500`，`fwi=fwa=50`，`wa=550`。

因此确认 `wa=score(rskl)+fwa`。该模式在一个 HTTP 响应内完成基础数字展示、SCAT Lucky Wheel 切换和最终结算；后一个响应是独立付费 Round。补采只确认已观察值域与状态转换，不把视觉扇区或样本频率宣称为服务端权重。

## 6. Round、Step 与 Delivery 状态机

### 6.1 已捕获分支

对当前捕获到的 `md=0/1/2/3` 且无 `ss` 的响应：

1. `IDLE`：无活动 Round；
2. 接收一次付费 Spin，扣注 `ba`；
3. 服务端一次返回完整 Round；
4. `md=0/1` 直接展示；`md=2` 在同一 Delivery 内从 `rskl` 切换到数组 `fwi`；`md=3` 从 `rskl` 切换到 SCAT 标量 `fwi=fwa`；
5. 余额 `pb` 已是整个 Round 结算后值；
6. 回到 `IDLE`。

因此当前 5087 条 Spin 响应对应 5087 个完整付费 Round、5087 个协议 Step；`md=2` 不增加 HTTP Step 数。

### 6.2 未捕获的 `ss=0` 跨请求分支

bundle 构造结果时执行 `spin_status = !ss`。当 `ss=0`：

1. 前端展示当前中奖符号；
2. 符号移除完成后 `onPlayRemoveBlock()`；
3. `onReqSpin()` 使用相同 `bl/bs/t/gid` 发相邻 Spin；
4. 直到后续响应不再满足 `ss=0` 才能结束同一 Round。

当前 5087 条样本均没有 `ss` 字段，没有任何原始相邻响应链、重触发或终止样本。实现约束：保留状态机接口，但正式生成和强制场景默认禁用；不得把它实现成新付费 Round，也不得猜后续字段。

### 6.3 Delivery 约束

- Java GameRuleCore 一次生成完整 Round；
- `md=0/1/2` 当前能力下一个 Round 只有一个协议 Delivery；`md=2` 的基础/重转是 Delivery 内部两个展示阶段；
- 若未来启用 `ss=0`，必须先生成整条相邻 Step 链，Delivery 只消费，不得逐请求临时拼装；
- 运行时禁止读取、轮播或随机选择 fixtures/历史 Spin 作为结果源。

## 7. 余额与历史

余额递推在 5086 个相邻响应对中有 5084 对满足：

`pb(current) = pb(previous) - ba(current) + wa(current)`

两处不连续与已记录的传输缺口/会话间隙相邻，不能用于补造缺失响应。历史详情的 `baf`/`balance_after` 与 `pb` 均表示结算后余额。

历史端点：

- `POST /cp/api/v1/lucky-wheel/log-list`
  - 请求：`page_index,begin_at,end_at,t,gid`；
  - 响应：`ll` 列表、`lc` 记录数、`ba` 汇总下注、`wa` 汇总派奖、`end` 分页状态；
  - `tis` 是详情查询所用 transfer ID，`bid` 是带游戏前缀的投注编号。
- `POST /cp/api/v1/lucky-wheel/log-view`
  - 请求：`transfer_id,t,gid`；
  - 响应：Spin 详细字段加 `bid/baf/balance_after/cc/cs/created_at` 等历史展示字段。

Spin 原始响应没有发现 `bid/tis/roundId`。采集索引的 `roundId` 是本地生成 ID，不得作为原协议字段。

## 8. 采样复核与上限偏差

协议复核后的真实计数：

| 类别 | 完整 Round | 目标 | 状态 |
|---|---:|---:|---|
| 普通 LOSS (`md=0,wa=0`) | 3815 | 200 | COVERED |
| 普通 WIN (`md=0,wa>0`) | 218 | 200 | COVERED |
| `md=1` 倍率轮 | 885 | 30 | COVERED |
| `md=2` 响应内重转 | 82 | 30 | COVERED |
| `md=3` Lucky Wheel | 2（固定 100 个 bet=5 补采） | 固定 100 Round，不追稀有目标 | COVERED_RUNTIME_CONTRACT |
| `rpx>1` 覆盖别名 | 885 | 30 | COVERED |
| `ss=0` 跨请求消除 | 0 | 30 | SAMPLE_INSUFFICIENT |

旧采集器把 87 个 `md=2` 后的下一次付费请求并入前一局，导致运行时实际发出了 5087 个有原始响应的付费请求；另有 2 个服务端确认但原始响应缺失的传输歧义请求，总服务端观察起点为 5089。当前官方索引按原始顺序只保留前 5000 个付费起点；第 5001 至 5087 条已移入 `spin-index.cap-overage-audit.jsonl`，只作审计证据，不参与任何当前覆盖统计。

后续专项补采使用独立浏览器配置，由原始 UI 选择 `bl=5`，只计并精确停止于 100 个 `bet>1` 完整 Round：`md0=82, md1=15, md2=1, md3=2`，`ss=0` 仍为 0，未为缺失矩阵追加 Spin。History 的当日汇总为 101 条/R$501，其中 100 条 R$5 是合格补采，另 1 条 R$1 是被选择器保护立即停止且排除的诊断 Round。完成后已关闭浏览器，禁止再下注。

## 9. 生成能力边界

- 可独立随机生成并由独立 ResultUtil 校验：`md=0` 普通 LOSS/WIN、`md=1` x2/x5、`md=2` 单响应内重转、`md=3` 单响应内 SCAT Lucky Wheel（仅限已确认字段合同和值域）。
- 默认禁用并安全拒绝强制生成：`ss=0`、未观察到的条件字段和未确认的高押注专属结果。
- `symbolWeights` 没有服务端权威证据。bundle 的 `SymbolWeightsList=[10,20,15,10,5]` 只属于前端视觉/滚动逻辑，禁止作为 Java 结果权重。
- Redis 属平台内部下游合同而非原浏览器字段；已按目标消费者 Key 形状实现 Lucky Wheel 专用极简事实 member，并以独立 Codec/ResultUtil 和真实 Redis 复验。
- fixtures 仅作为独立协议 oracle；正式 API、Loader、Demo 和 GameRuleCore 均不得依赖 fixtures。

## 10. 交接文件

- `game-capabilities.json`：唯一能力来源；
- `protocol-handoff.json`：全部行为证据、实现契约、独立验收契约和未决处置；
- `field-evidence-matrix.json`：字段/规则—来源—前端判定—样本印证矩阵；
- `reports/43-Lucky-Wheel/scenario-coverage.json`：协议修正后的完整 Round 覆盖；
- `reports/43-Lucky-Wheel/current-status.json`：当前唯一状态。
- `reports/43-Lucky-Wheel/protocol-analysis-validation.json`：本节点离线一致性与公式校验结果。

实现门禁已更新：实现和交付必须继续读取同一 `rulesHash` 的 `protocol-handoff.json` 并逐 `behaviorId` 复核。`md=3` 已由 bet=5 定向证据启用，仅允许在 `bet>=5` 档案出现；`ss=0` 在精确100局内仍为0，只记录缺席且继续禁用，不追加采集。本地权重不得宣称原厂 RTP。
