# 45-Rio-Carnival 协议与状态机规范

真实名称：Rio Carnival。工作流 ID 为 45；平台目录项 1401、平台 g_id 447、CP 资源号 50045。规则哈希：`sha256:515ffa3a4f803e36d44a1a50da1ce79b4c1df2e75a79893a18cd404aecc0565c`。

## 编码与首载顺序

业务 POST 使用 `application/x-www-form-urlencoded`。逻辑头 `game-id`、`web-token` 分别映射到表单 `gid`、`t`；响应是明文 JSON envelope，没有发现应用层加解密。首载顺序为平台 `GET /api/game/startup?game_id=1401` → iframe → `POST /cp/api/v1/auth/verify` → `POST /cp/api/v1/rio-carnival/config`。

## 端点

| 端点 | 请求字段 | 响应/用途 |
| --- | --- | --- |
| `POST /auth/verify` | `ai,btt,t,gid` | 会话与余额校验 |
| `POST /rio-carnival/config` | `t,gid` | 押注、币种、奖表、可选 `last` 恢复快照 |
| `POST /rio-carnival/spin` | `bl,bs,t,gid` | 付费 Round 起点或同一 Round 的下一 Delivery |
| `POST /rio-carnival/log-list` | `page_index,begin_at,end_at,t,gid` | History 分页列表 |
| `POST /rio-carnival/log-view` | `transfer_id,t,gid` | History 详情 |

## 牌面与中奖

可见牌面 5×3，`rskl` 为列优先：索引 = reel×3+row。玩法是固定 25 线、从左向右、每线取最高奖；`Wild` 替代除 `Scat` 外的符号并使参与中奖翻倍。`wmkl` 是中奖线结果。不得把前端动画 reel count 或样本频率声称为原厂 RNG 权重；本地复刻采用另行披露的经验聚合模型。

## Paid Round / Delivery / Step

付费起点满足 `ba>0 && ss==1`。同一 Round 的后续免费 Delivery 满足 `ba==0`；非终止步骤通常 `ss==0`。合法终止必须同时满足 `ss==1 && fsn==nfsc`，因为触发免费局的付费 Step 本身也可能 `ss==1`。一次生成必须覆盖整个 Round，后续请求只顺序消费已生成的 Delivery。

## FREE_SPINS 与恢复

3/4/5 个 `Scat` 触发免费局并可重触发。真实恢复链见 `captures/45-Rio-Carnival/session-resume-adjacent-evidence.json`：Round 45-001406 激活为 `fsn=8,nfsc=0`，重载后 `config.last` 非空且关键字段完全匹配，随后八次 `ba=0` 请求把 `nfsc` 推进到 8，最终 `ss=1 && fsn==nfsc`。激活 Round 禁止临时生成或开启新付费局。

## History 跨请求契约

真实链见 `captures/45-Rio-Carnival/history-adjacent-evidence.json`。`log-list` 第 1 页返回 `lc,ba,wa,end,ll`；列表项实见 `ba,baf,bid,ca,fe,gm,gt,tis,wa`。相邻第 2 页响应也已保存。选择 `ll[].tis` 后，以同一值请求 `log-view.transfer_id`；持久化证据只保留两者一致的 SHA-256。详情返回 `baf,bid,bsl,fsl`，前端按 `bsl` 后 `fsl` 的顺序渲染。History 列表的 `gt=45` 是游戏标识语境，不得套用实时 Spin 的 `gt=1/2` 语义。

## 独立验收边界

原始响应与 fixtures 只能作为协议 oracle，绝不能成为运行时结果源。实现前必须读取 `protocol-handoff.json` 并校验相同 rulesHash，逐一实现其中 19 个 behaviorId；测试预期来自规则、前端代码、抓包与相邻请求证据，`usesImplementationGeneratedExpected=false`。

## 当前 Java v3 实现

当前实现以 protocol-spec.json 的 implementationV3 为准。所有回合（含普通与免费 0 倍）均由 Java 离线生成并以 R45C3 ASCII 完整成员装入 Redis DB15。API 先选输赢，再从对应模式的实际整数倍率索引原子领取一局；空桶直接失败，不回退、不临时生成。控制器通过 --port 或 PORT 接收 50000–59999 端口，单个进程承载原版静态页与 API。

原始 History 详情证据包含一个完整免费回合（1 个基础步骤与 8 个免费步骤）；普通输、赢的独立原始详情样本标为 SAMPLE_INSUFFICIENT，不把本地验证当原站抓包。通用详情字段与普通步骤转换同时有原版前端代码依据。原始 ID 已脱敏，本地显示 ID 是稳定的短不透明标识，不声称复现原站 ID 格式。
