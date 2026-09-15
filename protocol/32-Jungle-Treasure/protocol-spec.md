# Jungle Treasure（raw gid 32）正式协议规范

本规范按工作流模板 v40、控制器合同 v3 描述 `ORIGINAL_AUTHORIZED_HTTP`、Config、History、归档语言资源与独立复算已经建立的行为。原站概率及原站 Redis 消费者合同没有证据，保持 `UNKNOWN`。

## 身份与端点

- 唯一游戏身份是 raw gid `32`。
- Config：`POST /cp/api/v1/jungle-treasure/config`。
- Spin：`POST /cp/api/v1/jungle-treasure/spin`，`application/x-www-form-urlencoded` 字段为 `bl`/`bs`（兼容 `bet_level`/`bet_size`），身份 `gid=32` 或 `game-id:32`，令牌 `t` 或 `web-token`。
- History 列表：`POST /cp/api/v1/jungle-treasure/log-list`。
- History 详情：`POST /cp/api/v1/jungle-treasure/log-view`，使用字符串 `transfer_id`/`tis`。
- 进房：`POST /cp/api/v1/auth/verify`。
- 成功响应为明文 JSON `{code:200,data,info}`。

## Config、押注和盘面

- bet level `1..10`，bet size `0.02/0.1/0.2`；默认 `bl=10,bs=0.02`。付费 `ba=20*bs*bl`。
- 盘面 6 轴，主高度 5，轴 2–5 另有独立顶格，合计 34 格。`rskl` 为带高度前缀的符号块（`1H3`/`4T`/`3Wild`），前端解析见 `s=[5,6,6,6,6,5]`。
- 坐标 `reel*10+tokenIndex`。轴 2–5 的 index 0 是顶格（10/20/30/40）。
- 普通符号 `H1..H6/A/K/Q/J/T`。`Wild` 只出现在轴 2–5，可替代除 Scatter 外全部符号。`Scat` 计 token 数。

## Ways 赔付与连消

- 从左向右相邻 ways。每轴匹配数为该轴（主格先、顶格后）命中 token 数，含 Wild。至少 3 轴。
- `wa = rpx * Σ(paytable[symbol][length] * bs * bl * ways)`。478/478 原始 Step 与独立复算一致。
- `ss=0` 当且仅当 `wa>0`；`ss=1` 当且仅当无 ways 中奖。
- 主游戏 `rpx` 开局 1，每次中奖连消后 +1。免费模式开局重置为 2，每次中奖连消后 +2，并在后续免费 spin 保持。
- 未镶框中奖块爆炸下落；银框中奖变为随机普通符号并转金框；金框中奖变为 Wild。顶格与主 5 格分别重力。新符号从主轴顶部与空顶格补入。

## Scatter 免费

- 4 个 Scatter token 授予 10 次免费。抓包未出现 5+ Scatter 或重触发，生成禁止。
- 触发 Step 仍 `small_game_type=0,nfsc=0,fsn=10,gt=1`。随后免费 Step `sgt=2,gt=2,nfsc=1..10,ba=0`。
- 进入免费后 `rwa` 从 0 重新累计（不含触发前主游戏）。`frwa` 为已完成免费 spin 的累计。

## Redis 与运行时

- 完整局由唯一 `GameRuleCore` 生成并校验后写入 Redis `192.168.10.3:6379/15`。
- member ASCII `JT32V1.` + base64url，只存下注、token、银/金框。中奖由核心复算。
- 普通 LOSS/WIN 使用 `PerKeyList_000000032` / `BetLog:000000032:{ratio}`；FREE 使用 `MaryKeyList_000000032` / `MaryLog:000000032:{ratio}`。倍率必须为整数。
- Demo 先随机输赢，再从已有整数倍率桶 LINDEX 领取一条完整局；连消/免费只投影同一 member。
- Redis 不可用或池空返回 HTTP 503，不扣注、不写 History。

## UNKNOWN

- 原站理论权重：UNKNOWN。本实现使用 478 个授权 Step 的经验列块/补牌分布。
- 原站 Redis 消费者合同：UNKNOWN。
- 重触发、5+ Scatter、rpx>30：抓包未出现，不生成。
