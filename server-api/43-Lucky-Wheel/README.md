# Lucky Wheel Java API

本工程以当前游戏 rulesHash=sha256:5BD756F5F541F6577977BA3A6FD309AED6BCC45730150E4A687B488157E595D1 为门禁，只依赖相邻 generator/43-Lucky-Wheel 的 Java GameRuleCore。

实现原前端使用的 auth/verify（并兼容 auth/session）、config、spin、log-list、log-view 与 ping。余额通过认证、Spin 的 pb 和本地 balance 查询保持一致。Idempotency-Key 请求头用于重试幂等，不增加或伪装原游戏响应字段。

会话、余额、历史、roundKey、已领取 deliveryIndex 和幂等响应原子持久化到 dist/data。每个 Round 由正式 GameRuleCore 一次完整生成；当前确认模式只有一个 Delivery。`bl<5` 使用两个位置并拒绝 md=3，`bl>=5` 使用三个位置并启用已取证的 md=3；只有证据不足的 ss=0 跨请求续局保持安全禁用。

运行 `build-all.cmd --no-pause` 只负责构建 `dist/controller.jar`。正式试玩由游戏中心按 Controller 合同 v3 启动当前游戏唯一受管 Java 进程，并动态注入一个 50000-59999 端口；应用不得派生额外进程。

`dist/demo-controller.properties` 声明 Controller 主类、配置文件、端口参数和本地原前端发布目录。Controller 仅监听平台注入的单一端口，会话、完整 Round 和持久化状态只属于游戏 43。
