# Crazy 777 Controller v3

平台注入 `--port`（50000–59999）、`--config`、`--publish`。

```
java -jar dist/controller.jar --port 55057 --config dist/controller.properties --publish ../../publish/57-Crazy-777
```

Demo 只从 Redis 预生成完整局取 member，禁止当场出牌。
