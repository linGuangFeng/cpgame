# 1830 Hotpot Controller

原站静态包来自 `publish/1830-Hotpot`，不改前端、不自制 Demo 页。

规则源码通过 Maven `add-source` 引用 `generator/1830-Hotpot/src/main/java` 里唯一的 `GameRuleCore`。Demo 游戏结果只从 `192.168.10.3:6379` db=15 领取预生成完整局：先随机中或不中，再在对应奖池已有倍率中取一条 member；连消/免费只投影。缓存空直接失败，不准当场出牌。

本游戏无购买。特殊模式只有 Scatter Free Spins，进 Mary 索引。

## 启动

平台注入 `--port`（50000–59999）：

```
java -jar dist/controller.jar --port 5xxxx --config dist/controller.properties --publish D:\work\hd\cpgame\publish\1830-Hotpot
```

描述符：`dist/demo-controller.properties`（`controller.contract-version=3`，`controller.jar=dist/controller.jar`）。
