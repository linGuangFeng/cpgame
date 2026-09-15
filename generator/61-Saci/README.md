# 61 Saci Redis Loader

完整局由 `GameRuleCore` 从抓包联合 kernel 抽样生成，`ResultUtil` 独立复核后写入 Redis。

```
dist\run-loader.cmd
dist\run-loader.cmd --no-pause
```

正式配置：`dist/generator.properties`（无 seed）。Redis：`192.168.10.3:6379` database `15`。
