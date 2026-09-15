# Spin 源数据反推与 Controller 投影约束

绑定规则版本：`46ef48cf0977a2785f257825d1e499b8049d293900e2a10d4a0dec7ad1ba2205`。

完整的逐入口样本数、概率、每个可直接生成符号/特殊牌/材质的列上限、轴上限、整页上限、允许位置及来源，见 `reports/2290-Samba-Sensation/spin-generation-reverse-engineering.json`。统计仅使用 4,900 个训练完整局，100 个真实留出局未参与反推。Controller 不生成牌面；它只解码 Redis 中已经由 Java 生成器执行全部上限后写入的完整局，并调用同一 Maven 产物中的 `GameRuleCore.validateStructure` 与 `ResultUtil` 再次校验和投影，不存在第二套判奖。

## 牌面与特殊符号硬边界

- 游戏为 5×3、25 条固定线、最多三轴；Wild 为符号 0，Scatter 为符号 10。
- 自然付费入口按 `bet_type` 分开执行每符号的列、轴、整页及允许位置上限；权重不能替代上限。
- 购买触发页固定三轴，Scatter 的三轴上限分别为 9/10/11、整页上限 30；购买盘位置集合按反推报告固定。
- 免费入口是完整重发的三轴大牌材质盘；790 页/轴训练证据中 Scatter 出现数为 0，因此该入口禁止生成 Scatter。
- 金币向量是相邻状态差分入口。4,874 组分支为：不变 4,141、递增 699、满槽 17、满槽后复位 17、非法下降 0。普通增量只生成抓包出现过的单槽 `+1`，以及唯一出现过的双槽 `[0,2,0,0,4]`；不生成负增量。

这些限制由既有 Java `GameRuleCore` 和生成器强制执行。Controller 不截断、不补牌、不用本地随机、fixture 或 History 响应顶替 Redis member。Redis 空池或连接失败时直接返回缓存连接/配置错误。

## 跨付费 Round 状态投影

Redis member 使用 `SS2` 极简 ASCII 编码，只保存当前完整局牌面、从牌面复算的 Scatter 增量、金币相邻转换类型、金币增量及满槽奖励总数；不再保存随机绝对进度。

- Controller Session 持有 `scatterProgress` 和五槽金币前态。每个新付费起点只调用共享 `GameRuleCore.applyCollectionTransition` 一次，后续 Free Step 复用同一投影。
- Scatter 增量必须等于当前付费初始响应全部轴中符号10的实际数量，进度按 `(前态 + 实际数量) % 30` 推进。是否进入 Free 仍只看完整局中明确的 `frees.st` 分支，不能从进度条猜触发时刻。
- 金币只允许 `UNCHANGED`、`INCREMENT_NONDECREASING`、`FULL_TRIGGER`、`RESET_AFTER_FULL` 四个显式互斥分支。普通局不得把最后空槽静默填满；满槽member必须与会话前态相邻，奖励后的下一付费局才投影抓包确认的全零复位。
- 购买页实际30个Scatter按模30不改变持久进度，协议响应仍按原厂游标回显25；购买不改变金币状态，也不建立第二个特殊池。
- Controller在领取member前用同一GameRuleCore检查其对当前Session是否可应用，不兼容member留在原Redis池，不弹出、不篡改、不用本地结果顶替。

## 完整局领取与续局

新的普通付费局先用 `SecureRandom` 选择中奖或不中奖，再在对应奖池的已有整数倍率中随机选择倍率，最后随机取一个完整局 member；一局只领取一次。购买 `type=3` 属于同一 Free Spins/mali 池。后续 `type=2` 只投影已领取 member 的下一 Step，不再选择倍率或 member。

自然 Free 的供应方相邻证据 `spin-raw#line-20..25` 同时约束以下投影：

- 起点和五个续局的 `frees.st` 必须严格为 `5→4→3→2→1→0`，终态不能省略。
- 自然触发起点若同时中奖，该起点中奖额必须立即计入 `frees.twa`；后续逐 Step 累加。
- `frees.m` 回显当前 Step 倍率，包括自然触发起点，不能把 index 0 强制写成 0。
- 免费续局不扣余额，但响应的 `data.bet_gold` 仍回显基准下注额；余额结算使用实际扣款 0。
- 原厂购买触发页及随后五个免费响应的 `props.scatter` 协议游标固定为 25。该字段只驱动原页面 Free 状态机，不参与判奖；购买盘 30 个 Scatter 的硬上限仍由共享 `GameRuleCore.BoardCaps` 校验。

原页面验证证明自然 Free、购买 Free 和金币满槽均能走到各自终态，按钮恢复后可再发出新的 Canvas Spin；详见 `reports/2290-Samba-Sensation/original-page-play-validation.json`。
