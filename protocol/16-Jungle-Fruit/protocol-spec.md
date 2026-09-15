# Jungle Fruit（raw gid 16）正式协议规范

本规范按工作流模板 v40、控制器合同 v3 描述 `ORIGINAL_AUTHORIZED_HTTP`、Config、History、归档语言资源与独立复算已经建立的行为。原站概率及原站 Redis 消费者合同没有证据，保持 `UNKNOWN`。

规则哈希由产物根目录执行 `jq -cS 'del(.rulesHash)' protocol/16-Jungle-Fruit/rules-core-canonical.json | shasum -a 256` 计算；只排除 canonical 顶层 `rulesHash`，保留 `jq` 输出换行。期望值为 `164088c0440871c9bc33b631a1b6bfa5e00d3012db3b0e3694bb59ac683e1743`。机器定义见 `rules-hash-definition.json`，其置于 canonical 外以避免说明字段改变被哈希输入。

## 身份与端点

- 唯一游戏身份是 raw gid `16`；请求、History `bid`、Redis sourceGameId 和产物目录都不得改用任何派生 ID。
- Config：`POST /cp/api/v1/jungle-fruit/config`。
- Spin：`POST /cp/api/v1/jungle-fruit/spin`，`application/x-www-form-urlencoded` 字段为 `bet_level`、`bet_size`；身份由 `gid=16` 或原版 `game-id:16` 提供，令牌为 `t` 或 `web-token`。兼容旧 `bl/bs` 别名。
- History 列表：`POST /cp/api/v1/jungle-fruit/log-list`。
- History 详情：`POST /cp/api/v1/jungle-fruit/log-view`；详情请求必须使用精确字符串 `row.tis`，不能使用已丢失长整数精度的数值型 `row.transfer_id`。
- 成功响应为明文 JSON `{code:200,data,info}`。授权语料的 2852 个 Spin Step 全部为 HTTP 200。

## Config、押注和盘面

- Config 的 bet level 为 `1..10`，bet size 为 `0.05/0.5/2.5`；旧归档确认 `bet_level=1,bet_size=0.05`，其 `bet_amount=1`；新增归档确认 `bet_level=10,bet_size=0.05`。其他组合由同一规则核心按 `20*bet_size*bet_level` 定价。
- 盘面固定为 6 列×6 行，`rand_symbol_key_list` 为列优先；数组下标是 `column*6+row`，中奖位置 wire key 是 `column*10+row`。
- 普通赔付符号为 `H1/H2/H3/H4/H5/A/K/Q/J/T`。`Scat` 和 `X2/X3/X4/X5/X7/X15` 是样本中已观察到的特殊符号。

## Count-anywhere 赔付和 Tumble

- 每个普通符号在整张 6×6 盘面出现至少 8 次即中奖，不要求相邻；`win_match_key_list` 枚举该符号的全部位置。
- `win_amount = Σ(symbol_pay_list[symbol][count] * bet_size * bet_level)`。Config 原始表与压缩的等价断点表在 `rules-core-canonical.json`。
- 2852/2852 Step 的中奖符号集合、位置和值独立复算无差异。
- `spin_status=0` 时，删除全部中奖位置。每列未中奖符号保持原顺序并落到底部，新符号从该列顶部补入。1224 次相邻 Tumble 转换全部满足此规则。
- 连续 `spin_status=0` Step 的 `round_win_amount` 累加各 Step 的 `win_amount`。
- 随后的 `spin_status=1` Step 完成当前结算段：将前一累计值乘以终止 Step `win_x_key_list` 中全部 X 数值之和；X 列表为空时按 x1。371 个此类终止段复算无差异。

金额必须使用整数分或十进制运算。`round-index.jsonl` 有 4 条 JavaScript 二进制浮点尾差，但按两位金额均与原始 Step 和 History 一致。

## 完整 Round 和 payout

- 一个付费请求建立一个完整 Round；同一 Round 的所有 Tumble、Mary 和 Free Step 必须由 Loader 预先整体生成、独立验证并写入 Redis；首次响应前仅领取和固定一个 member。
- 每次 Spin 请求只投影同一 Round 的下一 Delivery。会话存在 active Round 时不得领取或生成另一 Round。
- Jungle Fruit 的 Round payout 不是最后一个 Step 的 `round_win_amount`，而是该 Round 内所有 `spin_status==1` Step 的 `round_win_amount` 之和。
- 1126 个 Round 的 ordinal `1..1126` 连续，2852 个 scene 连续，每局最后一个 Step 均为 `spin_status=1`，1126/1126 History 匹配。

## Mary Small Game

- 唯一分类谓词：Round 任一 Step 的 `small_game_type==1`。
- 164 个正式完整 Round 被确认；与 Free 集合不重叠。
- 已观察状态机从一个 `small_game_type=0,spin_status=0` 的付费中奖 Step 开始，随后使用 `small_game_type=1` Delivery，直至 `spin_status=1`。
- Mary 终止 Step 的 X 数值之和结算整个未终止累计值。不可把 `small_game_type` 的其他值猜作 Mary。

## Scatter Free Rounds

- 分类谓词：Round 任一 Step `small_game_type==2` 或 `free_spin_num>0`。
- 36 个正式完整 Round 被确认；全部 History 详情为一个 `base_spin_list` Step 后接 `free_spin_list`。
- 初始盘面 3/4/5 个 `Scat` 分别授予 10/12/14 次 Free spin。
- Free 模式某一结算段终止盘面出现 2 个 `Scat` 时，`free_spin_num` 增加 5；正式语料 26/26 次增量事件均为此转换。
- `now_free_spin_count` 在同一 Free spin 的 Tumble 中不变；最终 Step 满足 `now_free_spin_count==free_spin_num`。

## History、余额与返回

- 列表和详情以字符串 `tis/transferId` 1:1 关联，1126 个 transferId 全部唯一。`bid` 固定为 `16-{transferId}`。
- 列表证据共 113 页，每页 10 条，末页 6 条。
- 详情播放顺序严格为 `base_spin_list` 后接 `free_spin_list`；不得按 `spin_status` 再排序。
- `detail.balance_after` 是扣注后、派奖前余额；1126/1126 都满足 `balance_after + row.win_amount == Round 终局余额`。
- History 只在完整 Round 的最后 Delivery 后写一次。查看列表、详情或返回游戏不得生成 Spin、领取 Redis member 或改变余额。

## 运行时与 Redis 边界

- 所有 LOSS、普通非免费 WIN（MARY 连消标签）和 FREE 都必须来自正式 Loader 预生成并独立验证的完整 Round member；运行时不得构造结果。
- member 是 `JF16V1.<base64url>` 极简 ASCII，只包含完整 Round 的下注、Step 状态和盘面事实；所有中奖与 payout 由唯一 `GameRuleCore` 复算。控制器按模式与非负整数倍率桶执行 `LPOP`，同一 member 的全部 Step 作为同局有序投影。
- Redis 不可用、池空或 member 非法时返回 HTTP 503，并且不得扣注、改余额或写 History。
- fixtures 与 captures 只作独立 oracle，严禁作为运行时结果源或轮播脚本。
- 本地 Redis 合同见 `redis-pack/16-Jungle-Fruit/redis-contract.json`；它不声称兼容未知的原站 Redis key/member/TTL。

## UNKNOWN

- 原站各符号、特殊模式和倍率的概率/权重：`UNKNOWN`。
- 原站 Redis 消费者协议：`UNKNOWN`。
- 已归档并经浏览器逐一验证的语言资源为 `bn/en/es/fr/id/ko/pt-br/th/tr/vi`；不外推归档之外的语言。
- 未在证据中出现的 X 值、Scatter 数量、Free award 或最大中奖上限：`UNKNOWN`，不得外推。


## 2026-09-08 重构与最终复核口径

旧段落中的 1126/164/36 等计数专指旧归档，不是新增归档合计。严格复核合并的 2550 个候选完整序列，剔除 104 个缺步或不一致序列，并保守去重 39 个相同序列后，保留 LOSS 2111、普通非免费中奖连消 241、FREE 55，共 2407 局。MARY 是原协议 small_game_type=1 的连消标记，与普通非免费中奖的 241 局重叠，不可重复相加。原始单步中奖并直接终止的独立 WIN 类型未被观察，不伪造该模式。详细排除项见 reports/16-Jungle-Fruit/original-round-audit.json。

生成分布以 1026 个训练完整局统计 6 个入口：PAID_LOSS、PAID_CASCADE、PAID_FREE、FREE_INITIAL、BASE_REFILL/FREE_REFILL（补入场景分别统计）。固定排除的 100 局只做原始留出验证。按入口、列、实际替换长度抽取加权列块，保留列内相关性；整个候选违反规则时丢弃，禁止拼出指定中奖、强补散点或强制终止。11346 种列块与分母、计数、概率、低支持量均见 distribution-model.json。该分布是经验近似，不代表原站理论权重。

生成约束为每列 Scat<=1、X<=2；全盘 Scat<=5、X<=4；连消段最多18次、累计免费次数最多25。新增原始证据出现全盘5个X，独立核验允许5，生成仍使用训练证据的保守4上限。这些不是原站理论上限。生成模型 SHA256 为 0d46ea41b87e22e433f21acdcf5f536b1fbcd1120200b25923a71ae3688757e2。

正式 Loader 使用 SecureRandom、不接收固定 seed；池按 payout/paidBet 的精确非负整数分组，非整数候选不入池，禁止 floor。Redis 固定 192.168.10.3:6379 DB15，namespace 为 cpgame:runtime:16:jungle-fruit:empirical-v1，列表为 :pool:{MODE}:{integer}，索引为 :pool-index:{MODE}。API 先随机输赢，再均匀选择当前存在的整数倍率，原子 LPOP 一整个 member；任何结果都不在运行时生成。旧 namespace 不消费、不删除。

History 与 Spin 需要不同序列化：History 的 bet_amount、bet_size、win_amount、round_win_amount 为字符串；win_match_key_list 为带 symbol_key、win_match_key 和 win_amount 的对象列表。Spin 仍使用数值金额和位置数组。原版 History ctorCopyData 按类型复制，不能直接复用 Spin JSON。balance_after 为扣注后基线，页面按逐步金额重建展示余额，终页等于真实终局余额。

控制器合同 v3 使用 java -jar controller.jar --config controller.properties --port 50116 --publish /Volumes/hd/cpgame/publish/16-Jungle-Fruit；或使用 PORT 环境变量。仅监听传入的一个 50000..59999 端口。原版入口通过 ?t=demo-jf16&l=en&gid=16&sip=127.0.0.1:50116 路由本地 API，不注入 GameUrl。独立静态服务器可以位于其他端口，sip 仍指向该控制器。

最新验收状态只看 reports/16-Jungle-Fruit/current-status.json；旧 READY 或旧浏览器报告不替代本次验证。Windows start-loader.cmd 已提供自定位、--no-pause 和退出码传播；当前 macOS 主机未实际运行 Windows cmd。
