# Crazy Piggy Controller v3

本服务锁定 `rulesHash=7edd2945da54923875932aa9feb1bfd657c9c67cca832637060f8419573480ab`，提供原页面、表单 Codec、`auth/verify`、`config`、`spin`、`log-list`、`log-view`、`ping`、`balance` 与 `session`。

生产 Spin 不生成结果，也不读取 fixture。Controller 按配置的输/赢/特殊权重做确定性轮转，再按 Redis 中已有的整数倍率索引选择列表，并用 `LPOP` 领取一个 `CP56A1` US-ASCII 完整 Round member。解码后统一经 `GameRuleCore`、`RoundVerifier` 与 `ResultUtil` 复核；池空或 Redis 不可用时返回 503，不回退生成。

默认配置监听全部网卡的 `55056`，根入口会根据请求 `Host` 自动补同源 `sip`。运行：

```powershell
cd dist
java -jar controller.jar demo-controller.properties
```

试玩入口：`http://localhost:55056/?gid=56&t=demo-launch&ai=luck_single_10229&btt=1&l=en&language=en`

构建与测试：`mvn clean package`。正式交付只使用 `dist/controller.jar` 与 `dist/demo-controller.properties`。
