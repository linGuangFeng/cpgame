# 字段/规则 — 来源 — 代码判定 — 样本印证

| 规则 | 来源 | 代码 | 样本 |
|---|---|---|---|
| gid=60 | 启动 URL、GameConfig._gameId | GameConfig.js | startup-evidence |
| 6x4 4096 ways | GameGlobalConfig.RowCounts/ColumnCount | GameGlobalConfig.js | rskl.length=24 |
| 最低注 1x1 | config.bsl/bll/dbs | config.response | spin ba=1 |
| spl | config.spl | RewardRule + config | 122/95 复核 |
| WILDXn + pxl | rskl + pxl | GameDataCommon | WILDX5 pxl.13=5 |
| 免费 3 轴 SC、fsn=8 | GameLogic + 抓包 | ResultUtil.scatterReels | round-steps FREE |
| 4/5/6 轴 SC 奖励 15/25/40 次 | 前端免费次数映射 + 恢复态 | GameRules.freeSpinsForScatterReels | 3/4/5 轴抓包；6 轴由恢复态与前端映射交叉印证 |
| 付费/免费基础权重 | 连续抓包已完成 Step 的逐格统计 | GenerationWeights | 付费 961 屏、免费 433 屏；仅经验权重，不是官方概率 |
| 首轴禁 Wild、每轴 SC/Wild 各最多1个 | 1394 个已完成 Step 的位置统计 | ResultUtil.validateBoardForStage | 当前样本零反例；作为当前本地生成约束 |
| 最低可中奖倍率 0.25、缓存桶 25 | config.spl + ×100 缓存单位 | ResultUtil + GameRules.cacheMultiplier | 独立构造牌面单测精确复核 |
| 生成范围直接使用缓存整数 | 用户明确口径 | GeneratorConfig.acceptsCacheMultiplier | 当前普通0..25000、免费500..30000；每个玩法独立按范围过滤，普通0倍允许、免费0倍拒绝 |
| 缓存家族保持原映射 | 现有游戏60 Generator/Handler 合同 | RedisLoader + RedisRoundStore | 普通=Per/BetLog类型0，免费=MaryKeyList/MaryLog类型0；倍率过滤不改键名 |
| 批次中性→逐牌单独×3→复位 | 本地正式生成策略 | GenerationWeights.forBatch | 17 相位单测覆盖，放大不累计 |
| member 无固定前缀/头部 | 本地缓存合同 + 极简编码要求 | MinimalRoundFactCodec | 单 Step 恰好24字符；多 Step 仅 `|`；旧前缀拒绝 |
| ss 终局 | GameApi IsContent / ss | collect-inpage isTerminal | ss=1 且 fsn==nfsc |
| History bsl/fsl | GameApi.HistoryByID | HistoryData.js | history-view |
