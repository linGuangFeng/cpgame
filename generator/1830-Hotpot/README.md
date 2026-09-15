# Hotpot 1830 完整局生成器

本目录是 Java 结果引擎：同一套 `GameRuleCore` / `HotpotResultUtil` 负责判奖，一次生成从付费开始到免费结束的完整局，并以极简 ASCII member 写入 Redis。

## 规则边界

- 6x6 列优先，任意 8 个相同赔付符号中奖，不是 payline / ways
- Scatter=11；主游戏 3 个奖励 10 次免费，免费中 2 个再奖励 5 次
- 倍率牌 12–23 只在连消后的末页求和后乘该次 Spin 累计 odd
- 没有购买入口；特殊结果只进 `MaryKeyList` / `MaryLog`
- 未中奖完整局进 `BetLog:000001830:000000`

## dist 交付

`dist/` 只含：

1. `hotpot-redis-loader.jar`
2. `generator.properties`
3. `start-redis-loader.cmd`（自定位，支持 `--no-pause`）

```bat
cd dist
start-redis-loader.cmd --no-pause
```

默认写入 `192.168.10.3:6379` db=15。连不上只查网络和配置，不准改出牌。

## 共享规则核心

`com.hd.pg.appapi.business.model.cpgame.hotpot.GameRuleCore` 必须被 server-api 与 Loader 同时引用（server-api 用 Maven `add-source` 指向本工程 `src/main/java`）。Demo 只投影缓存 member，禁止当场出牌。


## 独立无奖标记（2026-09-14）

普通及免费模式中，只有一页的完整独立零奖 Spin 可替换为 `#`，如 `原编码|#|原编码`，普通独立零奖整局可直接为 `#`。免费 Spin 仍占原位置并消耗一次免费次数。

按模式判定：普通盘须少于 3 个 Scatter；免费盘须少于 2 个 Scatter，避免漏掉追加免费次数。生成器同样按当前模式构造零奖盘面，并用 ResultUtil 复核。连消整段和其中无奖收尾页均保留；不能因末页未中奖就替换，因为末页还可能携带本 Spin 的结算倍率。

新解析器兼容已有完整编码。每次领取 Redis member 后只物化一次，校验、后续交付、重试与历史共用该事实；Redis 为空时仍失败。零奖生成最多尝试 5 次，并有 10 个已校验默认盘（41 按 PAN 数量分别保存）。先更新消费端 JAR，再使用新 Loader 写入带标记的数据。Loader JAR 通过 Maven package 交付到本游戏 dist 目录。
