# Lucky Panda（raw gid 41）协议规范

本规范只描述已由正式 `ORIGINAL_AUTHORIZED_HTTP` 证据或本地复刻实现验证的行为。原站概率分布、原站 Redis 消费者协议和 History 详情的原始 URL 均未被证据建立，因此不作等价声明。

## 身份与传输

- 游戏身份固定为 raw gid `41`；不得替换为 `8000000+gid` 或 integrationGameId。
- Config：`POST /cp/api/v1/lucky-panda/config`。
- Spin：`POST /cp/api/v1/lucky-panda/spin`，表单字段 `bl`、`bs`、`gid=41`。
- History 列表：`POST /cp/api/v1/lucky-panda/log-list`。
- 本地复刻详情：`POST /cp/api/v1/lucky-panda/log-detail`。该路径是本地协议，不声称为原站详情 URL。
- 成功响应为 `{code:200, info, data}` 的明文 JSON 包装。

## 盘面、赔付与 Round

- 6 轴行数为 `[5,6,6,6,6,5]`，位置编号为 `reel*10+row`。
- `rskl` 使用 `<高度><符号>` RLE；2883 个正式 delivery 盘面全部严格解码。
- 从左到右连续 3–6 轴的 All Ways 中奖；Wild 可代替普通赔付符号，不代替 Scatter。
- 赔付公式和 paytable 见 `rules-core-canonical.json`；2883 个 delivery 的 `wa` 独立复算零差异（浮点最大误差约 `2.84e-14`）。
- 一个 paid Spin 建立一个完整 Round。服务器先生成完整 Round，再逐 delivery 投影；`ss=0` 表示当前 Round/免费局仍需继续，`ss=1` 表示当前 delivery 分段终止。
- 每个 Round 只在最终终态写入一条 History；1093/1093 transferId 一致。

## 特殊模式

- Cascade：中奖格消除后继续，`rwa` 累计，`rpx=0` 等价 x1，正值在样本中为 2–26 的偶数。
- Scatter Free Rounds：至少 3 个 Scatter 触发固定 10 次免费局；`fsn=10`，`nfsc=1..10`。
- 样本中未建立免费局 retrigger 规则，因此复刻核心禁止声称或随机实现原站 retrigger。

## 实现边界

- 正式运行只调用唯一 Java `GameRuleCore`；采集 JSONL 只作独立 oracle，禁止作为运行时结果池。
- 原站 reel strip/概率未捕获。本地生成器的策略必须标注为 local policy，不能冒充原站概率。
- Redis pack 是 raw gid 41 的本地完整 Round 合同；不声称兼容未知原站消费者。
