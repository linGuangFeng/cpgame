# 字段/规则 — 来源 — 代码判定 — 样本印证

| 规则 | 来源 | 代码 | 样本 |
|---|---|---|---|
| gid=60 | 启动 URL、GameConfig._gameId | GameConfig.js | startup-evidence |
| 6x4 4096 ways | GameGlobalConfig.RowCounts/ColumnCount | GameGlobalConfig.js | rskl.length=24 |
| 最低注 1x1 | config.bsl/bll/dbs | config.response | spin ba=1 |
| spl | config.spl | RewardRule + config | 122/95 复核 |
| WILDXn + pxl | rskl + pxl | GameDataCommon | WILDX5 pxl.13=5 |
| 免费 3 轴 SC、fsn=8 | GameLogic + 抓包 | ResultUtil.scatterReels | round-steps FREE |
| ss 终局 | GameApi IsContent / ss | collect-inpage isTerminal | ss=1 且 fsn==nfsc |
| History bsl/fsl | GameApi.HistoryByID | HistoryData.js | history-view |
