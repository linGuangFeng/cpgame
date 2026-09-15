# Samba Sensation Controller

这是游戏 2290 的 Controller 合同 v3 Java 工程。Controller 依赖同一 Maven 产物 `samba-sensation-rule-engine:1.0.0` 中的 `GameRuleCore`、`ResultUtil` 和极简 member Codec；工程内没有第二套判奖。

正式交付位于 `dist/controller.jar`、`dist/controller.properties`、`dist/demo-controller.properties`。平台必须通过 `--port <50000-59999>`、系统属性 `cpgame.demo.port` 或环境变量 `CPGAME_DEMO_PORT` 注入端口。JAR 只启动一个受管 HTTP 进程，不派生其它服务。

Demo 固定连接 `192.168.10.3:6379 db=15`。普通正奖从缓存领取，按 `0/1/2` 前缀对应一/二/三层：`BetLog:000002290:倍率`、`BetLog:100002290:倍率`、`BetLog:200002290:倍率`，对应索引为 `PerKeyList_000002290`、`PerKeyList_100002290`、`PerKeyList_200002290`。Mary 缓存不分层。调用方传入普通输赢权重、中奖上限分布、符号和金币权重，实际总奖允许低于上限；普通正奖缺货直接报错，绝不实时搜索中奖牌面。

实时生成仅负责 0 倍页、触发首屏和收集增量。缓存普通牌面目前筛选无 Scatter 的模板，旧 member 的金币增量不沿用；Scatter 按实时概率逐格尝试加入，每格最多校验一次，必须保持缓存奖励金额不变且不补满普通局进度。Mary 首屏为实时 0 倍页，按进度和实际符号判断自然/集满触发，然后拼缓存五步 Free。金币满盘先扣除金币奖励，例如 99 倍占用 100 倍上限后，只从当前楼层缓存取不超过 1 倍的正奖，没有合适结果则实时生成 0 倍页。购买继续使用缓存完整局。

所有符号、金币、Mary 和中奖上限权重均由调用方传入 `RuntimeSpinGenerator.Parameters`/`RuntimeRoundComposer`。`DemoRuntimePolicy` 只保存演示参数；正式环境应从配置中心构造参数，禁止把概率写死在生成器内。最终拼接结果必须再次经过共享 `ResultUtil` 判奖与 `GameRuleCore` 状态校验。

构建命令：`mvn test`、`mvn package`。运行示例：`java -jar dist/controller.jar --port 52290 --config dist/controller.properties`。
