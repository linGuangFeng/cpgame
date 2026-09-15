# Jurassic Jungle contract v3 controller

`dist/controller.jar` 接受平台注入的 `50000-59999` 端口：

```
java -jar dist/controller.jar --config=dist/controller.properties --port=50008 --publish=../../../publish/8-Jurassic-Jungle
```

`dist/demo-controller.properties` 声明 `controller.contract-version=3`。规则核心与 Loader 共用 `com.cpgame.batcha.g8.GameRuleCore`。
