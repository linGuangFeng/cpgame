# 1809 单一 Java Core

## 当前结构

- 唯一规则源码：`generator/1809-Freedom-Day/src/main/java`。
- 最终单游戏 JAR：`generator/1809-Freedom-Day/dist/freedom-day-redis-loader.jar`。
- Redis：JAR 默认入口 `RedisDirectLoader`，读取 `generator.properties`，边生成边分批写入。
- 本地试玩：`server-api/1809-Freedom-Day/dist/controller.jar` 在同一受管 Java 进程内直接调用共享 Core，维护 Delivery、余额、幂等与 History。
- 正式运行时不派生 Java/Node/Python 子进程，也不读取 fixtures 或历史响应。

## 一致性身份

- `rulesVersion`: `freedom-day-1809-v2-defect-fix`
- `rulesHash`: `235af93ec1e76cecf4816ab889bb1924113501faa79714b372473261367e6986`

HTTP 普通、购买和免费 Spin 都返回这两个值；Redis Loader 完成日志也输出同一值。工作流验收必须比较它们，一旦不一致立即失败。

## 多游戏运行约束

每个游戏单独构建自己的 Redis Loader JAR，但日常/生产不为每款游戏常驻一个 Demo。共享 Java Server API 按 Game ID 注册多个游戏适配器。单游戏 Demo 端口只在该游戏自动验收期间临时启动，测试结束后释放。

## 已执行验证

- Java：4项测试通过，包括完整 Round协议、最终无奖页、Redis原子分批和离线包确定性。
- Python兼容层：2项测试通过，只验证调用Java Core后的普通/购买/免费Delivery。
- HTTP：`initRoom`、普通 Spin、购买、后续免费 Spin通过；三类响应 `source=shared-java-core` 且规则哈希一致。
