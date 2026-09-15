# Lucky Dragon Java Controller

本目录按工作流 v37 交付 raw gid42 Controller。固定发行文件为 `dist/controller.jar`，`dist/demo-controller.properties` 声明 `controller.contract-version=3`、`managed-process` 与 `--port` 动态注入。

平台为每个游戏启动一个独立受管 Host JVM，并从 50000-59999 分配唯一端口。`ServerMain` 在该 JVM 内校验注入端口并通过平台虚拟 HTTP Provider 注册路由；它不会创建第二个监听器、派生 Java/Node 子进程或退化为不受管 standalone 服务。

Controller、独立复核器和 Redis Loader 共享 `com.cpgame.luckydragon.core.GameRuleCore` 与同一个 `rulesHash`。Loader 使用 `SecureRandom` 从完整 INITIAL 状态联合模型预生成 Round；Controller 每个付费开局先随机 WIN/LOSS，再在该侧已有整数倍率桶中随机并只以 Redis `LPOP` 领取一次 member。幂等重放只投影同一 member，缓存为空即失败。跨进程续局仅持久化启动别名与幂等键的 SHA-256，不落盘原始令牌。运行时不读取 captures、fixtures 或历史响应，不包含 seed、固定牌面、demoScript 或按局号场景轮播。
