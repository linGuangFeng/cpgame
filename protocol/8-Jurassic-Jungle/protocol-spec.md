# Jurassic Jungle（raw gid 8）正式协议规范

本规范按工作流模板 v40、控制器合同 v3 描述 `ORIGINAL_AUTHORIZED_HTTP`、Config、History、归档语言资源与独立复算已经建立的行为。原站概率及原站 Redis 消费者合同没有证据，保持 `UNKNOWN`。

规则哈希由 `protocol/8-Jurassic-Jungle/rules-core-canonical.json` 去掉可能存在的顶层 `rulesHash` 后做 `JSON.stringify` 再 SHA-256。期望值为 `8dae3e502d7eb6610086d677915adb0b988b25f2b8210b378d3ba6d313518b04`。

## 身份与端点

- 唯一游戏身份是 raw gid `8`。请求、History `bid`、Redis sourceGameId 和产物目录都不得改用任何派生 ID。
- 进房：`POST /cp/api/v1/auth/verify`。
- Config：`POST /cp/api/v1/jurassic-jungle/config`。
- Spin：`POST /cp/api/v1/jurassic-jungle/spin`，`application/x-www-form-urlencoded` 字段为 `bl`/`bs` 或 `bet_level`/`bet_size`；身份由 `gid=8` 或 `game-id:8` 提供，令牌为 `t` 或 `web-token`。
- History 列表：`POST /cp/api/v1/jurassic-jungle/log-list`。
- History 详情：`POST /cp/api/v1/jurassic-jungle/log-view`，使用精确字符串 `transfer_id`/`tis`。
- 成功响应为明文 JSON `{code:200,data,info}`。

## Config、押注和盘面

- Config 的 bet level 为 `1..10`，bet size 为 `0.05/0.5/4`；归档确认 `bl=4,bs=0.05` 的 `bet_amount=2`。定价为 `10*bet_size*bet_level`（10 条固定线）。
- 盘面固定为 5 列×5 行，`rand_symbol_key_list` 为行优先；数组下标是 `row*5+column`，中奖位置 wire key 是 `row*10+column`。
- 赔付符号为 `S2..S9`。`S1` 是 Wild，可代入任一赔付符号，且可同时属于多个符号的簇。
- `symbol_pay_list` 与原站 Config 一致；`win_amount = symbol_pay_list[symbol][n] * bet_size * bet_level`。

## 簇赔付与连消

- 同一符号（含 Wild）四连通（上下左右）至少 4 个即中奖。不相邻的同符号是独立簇，分别赔付。
- 1613 个清洁完整付费 Round、8207 个 Step 的中奖符号集合、位置和金额独立复算无差异。
- `spin_status=0` 且当前盘有中奖时，删除全部中奖位置（Wild 在多个簇中只删除一次）。每列未中奖符号保持原顺序并落到底部（row 增大），新符号从该列顶部补入。
- `win_amount_sum` 累加各 Step 的 `win_amount`。整局 payout 等于终局 `win_amount_sum`。
- 付费局第一步 `bet_amount` 为正，`small_game_type=0`；后续 Delivery `bet_amount=0` 且 `small_game_type=1`。

## 收集器与四条龙

- 收集器在付费局开始时为 0；每步将本步唯一中奖格数累加，上限 70。
- 无中奖盘且仍有待触发模式时 `spin_status=0`，否则终局 `spin_status=1`。
- 模式只在无中奖盘触发，一次只触发一条，顺序为土龙、水龙、火龙、巨龙，每条每局至多一次。
- 土龙（收集≥10）：删除全部低赔符号 `S6-S9`，剩余下落并顶部补牌，`remove_status` 变为 1。
- 水龙（≥30）：在 wire 坐标 `11,13,31,33` 放入 Wild，不改其他格，`remove_status` 变为 2。
- 火龙（≥50）：在 `(row+col)%2==0` 的非 Wild 格铺上同一个随机符号，不覆盖已有 Wild。abc222 新抓 1000 局中 151 次火龙应用的覆盖符号为 `S1/S2/S3/S4/S5/S6/S7/S8`（其中 Wild/S1 97 次）；**S9 仍为 0，不生成**。
- 巨龙（≥70）：每个低赔符号变成 `S2-S5`。abc222 1000 局中 6135 次 extra 转换目标全是高赔符号，**从未变成 Wild**。`extra` 为 `[coord,旧低赔符号]`。

## 完整 Round 和 Redis

- 一个付费请求建立一个完整 Round；同一 Round 的所有连消和龙 Delivery 由 Loader 预先整体生成、独立验证并写入 Redis；首次响应前仅领取和固定一个 member。
- 会话存在 active Round 时不得领取或生成另一 Round。
- member 是 `JJ8V1.<base64url>` 极简 ASCII，只包含下注、Step 状态、extra 和 25 格盘面；中奖与 payout 由唯一 `GameRuleCore` 复算。
- Redis 比率桶是整数 paytable unit 之和（`payout/bet*10`）。LOSS 为 0。
- Demo 先随机决定中奖或未中奖，再从对应奖池已有整数倍率桶 LPOP 一条 member。
- Redis 不可用、池空或 member 非法时返回 HTTP 503，并且不得扣注、改余额或写 History。
- fixtures 与 captures 只作独立 oracle，严禁作为运行时结果源。

## History

- 列表字段含 `bet_amount/bid/created_at/game_type=8/tis/transfer_id/win_amount`，每页 10 条，含 `page_is_end`。
- 详情是 Step 对象数组（不是 Jungle Fruit 的 base/free 两段）。详情里的 `win_match_key_list` 为 `{symbol_key,win_amount,win_match_key}` 对象；实时 Spin 为平行数组。
- History 只在完整 Round 的最后 Delivery 后写一次。

## UNKNOWN

- 原站各符号、特殊模式和倍率的理论概率/权重：`UNKNOWN`。本地使用 1513 个训练完整局的列块经验分布，100 个留出局未参与建模。
- 原站 Redis 消费者协议：`UNKNOWN`。
- 已归档语言资源为 `bn/en/es/fr/id/ko/pt-br/th/tr/vi`。
- 购买免费、Scatter 免费：抓包未观察到，不生成。
- 巨龙变成 Wild、火龙选择 S9：抓包未出现，不生成。
