# Game 2290 协议与状态机

rulesHash: `46ef48cf0977a2785f257825d1e499b8049d293900e2a10d4a0dec7ad1ba2205`

## 一局如何结束

普通付费请求为 `type=1`。响应未激活 `frees.st` 时，该响应就是完整 Round 终点：`total_win=0` 且金币未满槽为普通未中奖；`total_win>0` 且金币未满槽为普通中奖。响应出现 `frees.st=5` 时，必须继续请求五次 `type=2`，相邻状态固定为 5→4→3→2→1→0；只有终态 0 才结束。

## 特殊与购买

Free Spins 固定五次、三轴全开。每轴 5×3 原始数组中，中央三列九格编码同一个大符号，外围六格为独立符号。购买请求 `type=3` 直接进入同一 Free Spins 状态机，因此购买不是独立互斥玩法。实际购买触发页请求 `bet_type=1` 但返回三轴，Scatter 数为 9/10/11、整页 30，必须与自然 Spin 入口分开处理。金币满槽奖励由 `props.coins.is_full` 表示，在一个响应内结算。

## 中奖与牌面

本游戏是 25 条固定线，不是 Ways/Cluster。Wild=0，可替代 1..9，不替代 Scatter=10；从左向右至少三个，每线只取最高奖。付费 `bet_type=1/2/3` 分别返回 1/2/3 个独立 5×3 轴。

## Scatter 约束

规则收集阈值为 30。自然付费入口按 bet_type 分别执行训练集观测上限：1轴为单列3/单轴4/整页4，2轴为2/4/5，3轴为3/6/8；购买触发页为单列3/单轴11/整页30，禁止混用。允许轴位为 1,2,3。Free Step 训练证据中 Scatter 为 0，因此该入口禁止生成 Scatter。

## 协议与证据边界

请求为 HTTPS + form-urlencoded，响应为普通 JSON，未观察到应用层加密。`oid` 必须按字符串保真。整数倍率为 `roundAward / bet`；`roundAward / bet_gold` 仅为 wagerMultiple。History 保留一次轻量列表调用和分页定位出的四条真实详情记录，不参与样本配额；普通输赢、Free Spins 与金币满槽均通过详情订单标识连接到唯一 canonical Round。

## 生成边界

所有分析统计只使用 4900 个真实训练 Round。冻结的 100 个真实完整 Round 与训练集零交集，只供后续独立 oracle。必须按 ROUND_OUTCOME_CLASS × ROUND_STEP_COUNT × STATE_ELEMENT_COUNT_VECTOR 联合分布建模，禁止独立格抽样和人为拼未中奖盘。金币五槽按相邻训练状态差分，记录不变、递增、满槽、满槽后复位分支及两端原始哈希。后续至少生成 10000 个新 Round 做独立检验。
