# 2210 Wu Kong 协议与完整 Round

证据基线为顺序采集的 1,122 个最小押注原厂响应、原前端 `assets/Game2210/index.64954.js`、History 抓包及 v46 资源闭包。`gameResult` 的一次成功 POST 同时返回完整 `chess.normal/extend/respin/result/win` 与结算字段，不存在第二个 HTTP step。RESPIN 是同一响应内自动播放的第二组两位置重转，直到前端触发 `Game_All_Anim_End` 后按钮恢复。

原厂接口包括 `initialData`、`getUserInfo`、`getActivity`、`initRoom`、`gameResult`、`getUserHistory`、`wukong_user_gold_history`、`wukong_user_history`。Controller 保持原字段类型；内部在协议边界把字符串 `"null"` 还原为显式 NONE/无重转状态，响应时再编码回原表示。

判奖：`normal` 左右位置去掉 `null` 后按顺序拼接十进制文本。NONE 直接结算；X2/X5 乘 2/5；RESPIN 追加一组完整两位置重转并把两段结果相加。`start_gold + change_gold = end_gold`，`change_gold = total_win - bet_gold`。购买玩法不存在，也不是独立分桶。

Redis 是平台内部合同而非原厂字段：db=15，运行时先安全随机选择 LOSS/WIN，再从对应池已有整数倍率中随机选择并领取一次最小 ASCII 完整局 member。Controller 只投影；缓存空立即 503，不读 fixture、不轮播、不在运行时出牌。

## 字段级独立 oracle

逐端点字段、JSON 类型、`normal/respin` 位置结构，以及上游使用字符串 `"null"` 表示缺省状态的合同，固化在 `protocol-field-oracle.json`。该 oracle 引用真实接口归档、1,122 局原厂响应、History 原始响应和原前端消费者的路径及 SHA-256；验证预期不来自 Controller。平台内部 `/api/session` 明确与上游协议隔离，不冒充原厂字段。
