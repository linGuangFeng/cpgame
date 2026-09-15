# Fishing GO Java Controller

游戏 54（Fishing GO），rulesHash：

`23e7a379b8a38d25d638cd0726cab97c49a6c425c88e2dd371c82007f9f81463`

Contract v3。平台读取 `demo-controller.properties`，JAR 为 `dist/controller.jar`。

Demo 只从 Redis `192.168.10.3:6379` database `15` 领取预生成完整局。空池返回 HTTP 503，不在运行时出牌，不读取 fixtures。
