# Beach Fun Java complete-Round generator

`GameRuleCore` is the single rules authority. Build with `mvn clean test package`; copy `target/redis-loader.jar` to `dist/redis-loader.jar`.

Redis 生成配置是 `dist/generator.properties`（1809 同款键：`generation.normal-count` / `special-count` / `batch-size` / 倍率帽 / 符号权重）。实验室 BetLog 读写这份文件。出牌仍用 `beachfun-distribution.tsv` 联合列模型。

启动：`dist/start-redis-loader.cmd`，或 `dist/run-loader.cmd --no-pause`。没有 seed，fixtures 不是运行时输入。
