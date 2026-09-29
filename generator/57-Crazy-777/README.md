# Crazy 777 Redis Loader

`dist/` 交付：

- `crazy777-loader.jar`
- `generator.properties`（无 seed）
- `run-ordinary.cmd`：普通奖穷举
- `run-mary.cmd`：玛丽概率生成
- `run-loader.cmd [ordinary|mary|both]`（支持 `--no-pause`；省略路口时两池都跑）

`run-ordinary.cmd` / `run-mary.cmd` 仍只写一池。runRedis 只传 `generator.properties`，先普通奖再玛丽。普通奖写入 PerKeyList；玛丽写入 MaryKeyList。
