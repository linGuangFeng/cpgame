# Mac 隔离交付边界

目录标签为 `1407-mac-Coin-Master-GO`，raw gameId 始终为 `1407`；上游页面协议中的 `gid=55` 是已捕获且单独记录的 provider protocol id，不得与 raw gameId 或 Redis game id 混用。

Controller 只调用唯一 `GameRuleCore.generateRuntimeRound`。它不读取 `fixtures`、`captures`、原 History 响应或预置结果。普通局、中奖局和特殊局都由同一次完整 Round 生成，后续请求只推进 delivery/step 游标。History 只投影当前隔离 Controller 自己已结算的运行时 Round。
