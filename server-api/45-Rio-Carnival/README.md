# Rio Carnival Java API

本工程按已验收 `rulesHash` 和 19 个 behavior 契约实现原前端使用的 verify、config、spin、History、余额、会话恢复和幂等。业务源码位于标准 `src/main/java`；`app` 构建模块直接依赖同一 Reactor 中的 Java `rio-carnival-generator`。Controller 不生成结果，只从 Redis DB15 原子领取一个预生成完整 Round，所有后续 Delivery 都投影同一个 ASCII member。

运行时状态只保存已领取的完整 Round、`roundKey`、`deliveryIndex`、余额和 History；不会扫描或读取 fixtures、captures、历史响应。平台必须用 `--port` 或 `PORT` 注入 50000–59999 的动态端口；`/demo` 会按访问者 Host 重定向到带正确 `sip` 的原页面。

构建：在本目录执行 `mvn package`，产物为 `dist/controller.jar`、`dist/controller.properties` 和 `demo-controller.properties`。平台必须注入动态端口；手动前台运行 `dist/start-demo.cmd` 前也必须设置 `PORT`。旧 restart-demo 启动器已停用，由平台管理启停。
