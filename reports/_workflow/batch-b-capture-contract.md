# CPGame 批次 B 原站证据采集契约

本契约只适用于 `42-Lucky-Dragon`、`43-Lucky-Wheel`、`45-Rio-Carnival` 和
`50-Lucky-Cat-II`。批次 B 已收到 `RELEASE_TO_B`，并在 HMS 个人中心独立确认 `abc222` 后完成
gid 42 的一次手工里程碑与 History 核对；B 创建的标签随后已关闭并发送
`RELEASE_BROWSER_TO_C`。该里程碑没有保留原始请求/响应，因此正式采样计数仍为0，不能据此
声明任何游戏完成。重新取得浏览器锁前不得继续 Spin，也不得把任何本地生成结果计作原站样本。

## 唯一游戏 ID

- 本批目录、配置、证据、协议、Java、API、生成器、dist、Redis、Verifier 和管理中心只允许
  使用及展示原始 `gameId`：`42`、`43`、`45`、`50`。
- 禁止加入派生编号、映射编号或其他流程的游戏 ID；目录主键始终是原始 gid。

## 账号与敏感信息

- 正式采集前必须在页面账户信息中确认 `abc222`。
- `captureAuthorization.parentReleaseSignal` 必须记录批次 B 的明确恢复授权，并保存页面账号
  确认的非敏感证据引用；否则归档工具硬拒绝。
- 当前短期令牌仅获准以内存方式发送至 `api.omgapibra.com` 用于原站批量采样；来源账户必须
  是 `abc222`，不得落盘、回显或复用其他会话令牌。
- 证据只记录 `accountAlias=abc222`，不得记录密码、入口令牌、会话令牌、Cookie 或
  Authorization。
- 网络交换必须在浏览器中完成；归档只接收已脱敏请求、响应、时间、顺序和哈希。

## 完整 Round

- 一个 `ba>0`（具体字段须由当前游戏首轮协议证据确认）的付费起点开启一个 Round。
- 其后的连消、重转、免费、玛丽或奖励 Step 全部归入该 Round，直到当前游戏证据确认的
  合法终止字段出现。
- `spin-index.jsonl` 每行只索引一个付费 Round 起点；所有 Step 写入
  `round-index.jsonl` 并通过 `roundId` 关联。
- 在端点、付费字段、终止字段和分类字段尚未由当前游戏真实请求确认前，采集配置必须保留
  `null`，参数化工具会拒绝进入正式归档。
- 即使已经达到 3000 个付费起点，最后一个 Round 的所有后续 Step 仍必须采完并抵达合法终局；
  未完成 Round、终局后游离 Step、无付费起点的 Step 都会被硬拒绝。
- 分类只读取从真实响应按 `factPointers` 派生的字段、终局输赢值和对应证据哈希；普通
  LOSS/WIN 互斥性、未分类完整 Round、特殊模式发现证据均属于终态验收门槛。

## 本批次用户明确覆盖口径

- 普通不中奖完整 Round：目标 200。
- 普通中奖完整 Round：目标 100。
- 玛丽及每种已确认特殊/奖励结果：各目标 30。
- 单游戏最多 3000 个付费 Round；全部适用目标达到后可提前停止。
- 稀有奖励在前 1000 个付费 Round 从未出现，标记 `NOT_APPLICABLE_RARE_REWARD_NOT_OBSERVED_WITHIN_1000_PAID_ROUNDS` 并视为不存在，不再阻止完成。
- 达到 3000 后仍不足目标的适用类别标记 `SAMPLE_INSUFFICIENT`；未到 3000 且不足目标时
  禁止停止或生成终态报告。

`roundSampleCoverage` 必须按 v15 提供 `ordinaryLossTarget=200`、`ordinaryWinTarget=100`、
`specialTargetPerCategory=30`、`rareRewardAbsencePaidRoundThreshold=1000`、`maxPaidRounds=3000`、`paidRoundStarts`、
`completedRoundCount`、`stopReason` 和 `categories[]`；每个类别必须提供 `kind`、`applicable`、
`target`、`actual`（或`completeRoundCount`）和 `status`。旧 `targetPerCategory` 只能作为兼容字段，
不能替代 v15 的两个独立目标字段。

## 使用方法

等待状态只校验参数文件，不产生 Spin 或样本：

```text
/Users/a1/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node \
  reports/_workflow/tools/batch-b-round-evidence.mjs validate-profile \
  --profile captures/42-Lucky-Dragon/capture-profile.json
```

正式采集完成后，输入必须是浏览器产生、已脱敏且标记
`sourceType=ORIGINAL_AUTHORIZED_HTTP` 的真实交换 JSONL；工具才允许生成 Round 索引和覆盖报告。
