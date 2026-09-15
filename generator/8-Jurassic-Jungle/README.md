# Jurassic Jungle formal Loader

`dist/jurassic-jungle-loader.jar` 与 `dist/generator.properties` 可独立运行。Loader 与 Java Demo 共用同一套 `com.cpgame.batcha.g8.GameRuleCore`。

双击 `dist/start-loader.cmd`，或：

```
java -jar dist/jurassic-jungle-loader.jar --config=dist/generator.properties
```

`--no-pause` 可跳过结束暂停。正式 Loader 使用 `SecureRandom`，配置禁止 seed。
