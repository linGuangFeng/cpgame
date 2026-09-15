# 2210 Wu Kong Controller v3

单一 Java 进程监听平台注入的动态 50000–59999 端口，直接提供原始 publish 和原协议端点。每次付费局先用 `SecureRandom` 选择 LOSS/WIN，再从 Redis db15 对应类别已有整数倍率中随机选一个并只读取一次完整局 member。Controller 依赖 generator 工程中的同一个 `GameRuleCore`，不读取 fixtures，不固定轮播，不接受定向模式参数，缓存空立即 503。

构建：`mvn clean package`。平台入口固定为 `dist/controller.jar`，配置描述符为 `dist/demo-controller.properties`。
