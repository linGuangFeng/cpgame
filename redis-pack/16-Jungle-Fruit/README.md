# Jungle Fruit Redis 完整 Round 合同

本目录定义 raw gid `16` 的**本地复刻** Redis 合同，不声称兼容未捕获的原站 Redis 消费者。

- 正式 Loader 只写已经由唯一 Java `GameRuleCore` 生成、再由 `IndependentVerifier` 独立复核的完整 Round。
- 证据中的 200 个 WIN 全部属于 Mary（164）或 Scatter Free Rounds（36），因此不声称原站普通 WIN 的概率或权重。Browser 验收需要的普通 WIN 池只用已确认的全盘计数与赔付 Core 构造单 Step 正式 member，并继续经过独立 Verifier。
- 一个 member 覆盖付费入口至全部 Mary/Free/Tumble 结束。Delivery 不可单独入池。
- 首次领取由 Redis `LPOP` 原子完成；一个极简 ASCII member 固定同一完整局的全部有序 Step。
- 最后一条 Delivery 后只结算一次余额并写一条 History row/detail。
- LOSS、WIN、MARY、FREE 都必须预生成入池；运行时不得构造 LOSS 或其他结果。
- 正式配置不得包含 seed。fixtures/captures 不能被 Loader 或 server-api 读取。
- 每个 mode/非负整数倍率 bucket 的写入使用同一个 `MULTI/RPUSH/LTRIM/SADD/EXEC`，默认每桶最多 500 个 member；LOSS 使用 0 倍桶。

精确 key、claim/advance/failure 状态见 `redis-contract.json`。金额、倍率与分类一律由 rulesHash `e53fffb1bc81b26725d44f1522b44b050eb21f93063de0c6a3a0c241adc3f7e2` 的 Java 规则核心重算。
