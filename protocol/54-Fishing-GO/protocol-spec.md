# 54 — Fishing GO 协议与状态机说明

本说明只整理当前游戏的页面、配置、规则、前端代码和真实抓包证据。未捕获字段保持未知；`fixtures/54-Fishing-GO` 仅作独立协议 oracle，禁止成为运行时结果源。

## 身份与玩法

- 真实 ID-name：`54-Fishing-GO`，显示名 `Fishing GO`。
- 可见牌面为 5 轮 × 3 行，使用 243 Ways；固定中奖线和 `paylineCount` 不适用。
- WILD 可替代 SC 以外符号，只出现在第 2、3、4 轮。
- 已确认唯一特殊模式为 `FREE_SPINS`：付费 Step 出现 5/6/7 个 SC 时进入 12 次免费序列，倍率步长分别为 1/2/3。
- 免费模式中 5 个 SC 增加 12 次的规则已确认，但 43 个免费完整 Round 均未捕获重触发；不得推定 `fsn` 的更新时间或伴随字段。

证据：规则页截图、语言奖表资源、`000085-config.json`、前端 `GameLogic/GameApi` 判定代码，以及 `rounds.jsonl` 的 1341 个完整 Round。

## HTTP 与编解码

- 游戏 API 使用 HTTPS `POST` 与 `application/x-www-form-urlencoded`。
- 表单按键和值执行 `encodeURIComponent`，空格编码由 `%20` 转为 `+`，键值对以 `&` 连接。
- 前端把 `game-id` 映射为表单字段 `gid`，把 `web-token` 映射为不透明字段 `t`。
- 未观察到应用层加密或业务层密文；传输保密由 TLS 提供。不得解析、记录或复用会话 Token。
- 响应为 UTF-8 JSON envelope。HTTP 2xx 且 `String(code)=="200"` 时向业务层解包 `data`；非 JSON、非 2xx、业务错误、超时和网络错误均不得推进本地 Round/Step 游标。
- `code=401` 停止心跳并要求重新认证。请求结果未知时先用 Config 的 `last/ls` 对账，禁止直接重放成新的付费 Round。

## 端点与顺序

| 阶段 | 端点 | 已证实的输入/输出 |
| --- | --- | --- |
| 首载 | `GET /54/?gid=54&l=<code>&sip=<host>&t=<opaque>` | 加载静态 bundle 和实际语言资源 |
| 配置 | `POST /api/v1/go-fishing/config` | 输入 `t,gid`；输出含 `auto,bll,bsl,cc,cs,dbl,dbs,last,ls,spl,ts` |
| Spin | `POST /api/v1/go-fishing/spin` | 输入 `bl,bs,t,gid`；输出字段见 `field-evidence-matrix.json` |
| History 列表 | `POST /api/v1/go-fishing/log-list` | 输入 `page_index,begin_at,end_at,t,gid`；输出 `ba,end,lc,ll,wa`，`end=0` 时可继续分页 |
| History 详情 | `POST /api/v1/go-fishing/log-view` | 输入 `transfer_id,t,gid`；输出 `baf,bid,bsl` 及可选 `fsl` |
| 心跳 | `POST /api/v1/ping` | 仅前端枚举，未作为当前游戏结果生成契约 |

真实相邻证据已确认 `ll[].tis → log-view.transfer_id`；`bid` 是显示交易号。详情按 `bsl` 后接可选 `fsl` 合并为一个完整历史 Round，末 Step 仍须满足 `fsn==nfsc` 或 `fsn==0`。证据见 `captures/54-Fishing-GO/history-log-list-view-adjacent.json`。

## Round / Delivery / Step 状态机

```text
IDLE
  --合法付费 Spin--> PAID_STEP_DELIVERED
  --fsn=0---------> ROUND_TERMINAL
  --fsn>nfsc------> FREE_ACTIVE

FREE_ACTIVE
  --下一免费响应且 fsn!=nfsc--> FREE_ACTIVE
  --fsn=nfsc-----------------> ROUND_TERMINAL

PAID_STEP_DELIVERED 或 FREE_ACTIVE
  --超时/网络未知--> RECOVERY_REQUIRED
RECOVERY_REQUIRED
  --Config.last 对账--> FREE_ACTIVE 或 ROUND_TERMINAL
```

- `IDLE` 才允许创建付费起点；付费 Step 满足 `ba>0, gt=1` 并创建一个新 `roundId`。
- 激活免费模式后，后续请求仍使用 Spin 端点，但 Step 必须关联同一 `roundId`，不得再次领取 member 或计为新局。
- 捕获的免费 Step 满足 `ba=0, gt=2`，`nfsc` 递增且 `rwa` 不下降。
- 一个完整 Round 的生成单位是从付费起点到合法终止的全部 Step；Delivery 单位才是单个 Step 响应。
- 仅在无激活 Round 且能力清单允许时，普通单步 LOSS 可由正式随机链路实时生成，并必须由独立 `ResultUtil` 复核。

## 已独立验证的不变量

- 1342 个真实付费起点，1341 个完整 Round：LOSS 1098、WIN 200、FREE_SPINS 43；另 1 个超时父局保持不完整且未计入完整样本。
- `wmkl` 外层与 `wskl` 同索引；3753 个坐标全部满足 `10*reelIndex+rowIndex`。
- 390 个中奖 Step 全部满足已证实的最小押注奖金公式。
- 516 个免费 Step 全部满足入口 `apx` 恒定且 `rpx=apx*nfsc`。
- 1341 个完整 Round 的末 Step 均满足 `rwa=ΣStep.wa`；43 个免费 Round 的中间余额保持、末步统一结算均通过。
- 合法终止条件为 `fsn==0` 或 `fsn==nfsc`。

## 下游实现边界

- 正式 Java `GameRuleCore` 一次生成完整 Round，后续 Delivery 只消费该 Round；不得逐请求重新拼装。
- 运行时不得读取、轮播、随机抽取或硬编码历史 Spin 响应和 fixture。
- Redis 是平台内部下游合同，不属于原厂 HTTP 协议。索引、倍率分桶、一个 member 对应一个完整 Round，以及同一 `MULTI/EXEC` 内 `ZADD + RPUSH + LTRIM` 来自 1809 成熟交付证据；Fishing GO 的 member 必须使用当前游戏专属完整 Round codec，禁止复制其他游戏牌面或字节编码。
- 通用 `bl/bs/ba` 公式和原厂符号权重仍未暴露；下游只能实现已确认的最小押注映射，并提供 Fishing GO 专属、可调整且经过分布校验的本地复刻权重，明确不代表原厂 RTP。
- 进入实现前必须读取 `game-capabilities.json` 与 `protocol-handoff.json`，并验证两者 `rulesHash` 一致。

机器可读字段、证据引用和逐行为实现/验证契约分别见 `protocol-spec.json`、`field-evidence-matrix.json`、`game-capabilities.json` 与 `protocol-handoff.json`。
