# Jungle Kings（raw gid 2）完整 Round 生成器

本工程是 raw gid `2` 的唯一规则实现所在位置。`server-api` 通过 Maven 依赖复用这里的
`GameRuleCore`，没有第二套前端或服务端规则。

Demo 不读 Redis。`CompleteRoundFactory.generate(random, ckl, size, level, requestedOdd)`
按 1400 做法一把出牌：ckl 选倍数列表，请求倍数向下落到列表，再从
`map<倍数, List<牌面>>` 取 combo 与轴带三连。单轴列表 `0,50,100`，双轴列表
`0,25,50,100,150,200`。独立 `ResultUtil` 复核，对不上就抛，不重试。

`dist/jungle-kings-loader.jar` 只打印 realtime catalog（`generation.cache=false`），
不 RPUSH Redis。双击 `dist/start-loader.cmd` 或 `dist/start-catalog.cmd`，或：

```text
start-loader.cmd --no-pause
```
