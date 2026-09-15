# Cyber GO（ID 52）协议与状态机说明

rulesVersion: evidence-snapshot-e138fe9b94c489b9
rulesHash: ae4823db63a5e8bd1d1f3a06da3ed6f76c04d761aefce10f29de6f6502885cfa

本说明只描述当前游戏已由规则文本、奖表、配置、前端判定代码和原始接口证据确认的行为。Fixtures 仅是独立回归 oracle，禁止作为 Java 运行时结果源。

## 端点与调用顺序

1. `POST /api/v1/auth/verify`：认证当前会话并取得玩家、余额、心跳和游戏配置入口信息。
2. `POST /api/v1/go-cyber/config`：读取币种、投注级别/大小、奖表和未结束局状态。
3. `POST /api/v1/go-cyber/spin`：无活动局时以 `bl=1&bs=0.02` 启动最低押注 R$0.60 的付费 Round；免费模式活动时，同一端点交付下一免费 Step。
4. `POST /api/v1/go-cyber/log-list` 后接 `POST /api/v1/go-cyber/log-view`：按 `transfer_id` 对账完整局；明细顺序为 `bsl` 后接 `fsl`。
5. `POST /api/v1/ping`：仅在认证响应 `ping.enable` 为真时调用。

请求默认使用 `application/x-www-form-urlencoded`。前端适配层把 `web-token` 映射为表单键 `t`，把 `game-id` 映射为 `gid`；键和值按 URL 编码，空格写为 `+`。成功条件是 HTTP 2xx 且 JSON `code=200`，业务载荷从 `data` 解包。当前证据未发现自定义请求加密、响应解密或二进制协议层；Token 必须当作不透明会话值处理。

## 盘面、中奖与金额

- 游戏名由页面标题、规则和接口共同确认：`52-Cyber-GO` / `Cyber GO`。
- 盘面为5卷轴×3可见行，`rskl` 恰含15个可见符号；未确认额外缓冲格。
- `winModel` 为 `WAYS`，固定243 Ways，从最左卷轴开始向右连续。它不是固定中奖线玩法，因此 `paylineCount=NOT_APPLICABLE`。
- 前端常量30只参与 `CurBetGold = bs × bl × 30`；不得把它解释为中奖线数。
- `WILD` 只出现在卷轴2、3、4，可替代除 `SC` 外的符号。`wmkl`/`wskl` 描述中奖组合，`wa` 是当前Step中奖，`rwa` 是局内累计中奖。

## Round / Delivery / Step 状态机

一个完整 Round 只从真实付费 Spin 起点开始。付费起点 `stepIndex=0`；若 `fsn=0`，该单步即合法结束。若付费起点触发免费游戏，则它与全部免费 Step 共用同一 `roundId`，免费 Step 不得建立新的付费索引或重复领取结果成员。

免费游戏由付费盘面3/4/5个 `SC` 分别触发12/15/20次。初始倍率为x2，累计至少3个 Wild 后倍率加2、消耗3个并保留溢出余数，规则上限x20；免费期间不出现 Scatter，因此当前游戏规则不支持免费重触发。

每局必须满足以下边界：

- `stepIndex` 从0开始逐次加1且无重复、无缺口。
- 免费 Step 在付费起点之后按 `nfsc=1..fsn` 严格递增；不能按HTTP返回到达顺序乱排。
- `fsn>nfsc` 表示仍需交付同一 Round 的下一Step。
- `fsn=0` 或 `fsn=nfsc` 表示 Round 合法结束；结束前禁止启动新付费 Round。
- 定向相邻证据：`rounds.jsonl#52-round-00100`，付费Step后为 `nfsc=1..12`，终态 `fsn=nfsc=12`。

## 关键状态字段

`roundId`、`stepIndex`、`bid`、`ss`、`gt`、`small_game_type`、`fsn`、`nfsc`、`rpx`、`rskl`、`wmkl`、`wskl`、`wa`、`rwa`、`pl.balance`。字段级的“来源—前端判定—样本—结论”见 `field-evidence-matrix.json`；逐行为实现与独立验收契约见 `protocol-handoff.json`。

## 实现边界

- 包括普通 LOSS 在内的全部结果只由离线 Java Loader 预生成。Demo 付费起点先随机输赢，再从对应现存整数倍率池领取一个完整 Round member；运行时禁止生成或兜底。
- 中奖与免费模式必须一次生成从付费起点到合法终止的完整 Round；Delivery 只能按序消费，不能逐请求临时拼装。
- Redis 下游合同由用户要求授权：192.168.10.3:6379、DB15、CyberGo:52:v3:win 与 CyberGo:52:v3:round:<整数倍率>。member 每格一位 ASCII，15 格一盘，盘间以 | 分隔。空池明确失败。
- 原厂卷轴条与 RTP 仍未知。离线模型由 1,362 个真实完整局的逐轴联合条件计数构建，排除 100 个留出局；不存整盘或整局回放。分布、分母和百分比见 reports/52-Cyber-GO/generation-distribution-model.json。
- 支持语言代码为 `bn,en,es,fr,id,ko,pt,th,tr,vi`，入口别名 `pt-br` 映射运行时 `pt`。

独立硬校验入口：`captures/52-Cyber-GO/tools/validate-protocol-rework.mjs`。

## 2026-09-08 后端复核

History 按 begin_at/end_at（秒）过滤，每页 10 条，返回 lc/ba/wa 汇总与整数 end；详情返回 bid/bsl/fsl/baf。完整 Round 的投注从原始 config 的 20 种选项中选择，免费延续不得改注。

原始局复核 1,462 局，新生成复核 10,000 局；模型类别最大绝对比例差 0.002314，低于预设 0.03 门槛。原始首页仍含历史注入，未取得未修改首页的精确路径，页面验收保持待补证据。
