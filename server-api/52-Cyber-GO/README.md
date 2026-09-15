# Cyber GO 受管试玩 Controller

本工程交付可由平台启动的独立游戏 Java 进程。平台读取
`demo-controller.properties`（与 `dist/demo-controller.properties` 一致），从 `50000-59999` 动态分配端口，并通过
`--port`、系统属性 `cpgame.demo.port` 或环境变量 `CPGAME_DEMO_PORT` 注入。

Controller 缺少动态端口或收到范围外端口时会拒绝启动；正常启动只创建一个绑定
`0.0.0.0` 的 HTTP 监听器，不派生 Java/Node 子进程或第二端口。`demo-url.txt`
中的 `50000` 只是本机规范占位端口，游戏中心必须改写为实际分配端口及访问者 Host。

会话、幂等记录、完整 Round、`roundKey` 与 `deliveryIndex` 均属于当前受管进程。
Controller 在付费起点只从 DB15 预生成完整局池抽取一个极简 ASCII member，再由正式 `GameRuleCore`
重建并逐 Step 投影；后续免费 Step 继续消费同一局，池空即失败。运行时不生成局，也不读取 fixtures 或历史响应。

构建命令：

```cmd
mvn.cmd test package
```

正式入口类、配置、动态端口合同和页面托管方式以
`dist/demo-controller.properties` 为准。平台管理进程生命周期；本游戏不修改公共平台。

2026-09-08：正式产物为 dist/controller.jar，合同版本 3。History 已补齐时间过滤、10 条分页和汇总字段。当前页面仍有原始首页来源及完整动画覆盖缺口；后端通过不等于原页面验收通过。
