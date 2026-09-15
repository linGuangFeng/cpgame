# Crazy Gems Controller

原包静态页 + Redis 缓存中奖结果；无奖使用独立生成器。

```
java -jar dist/controller.jar --port 50058 --config dist/controller.properties --publish ../../../publish/58-Crazy-Gems
```

入口：`http://host:50058/58/?gid=58&l=pt&t=demo`
主 JS：`/58/src/settings.78db3.js`、`/asset/cocos2d-js-min.55e56.js`。
API：`/cp/api/v1/crazy-gems/{config,spin,log-list,log-view}`，结果只从 Redis `192.168.10.3:6379` db=15 领取。

普通和矿车奖励统一从 `PerKeyList_0%08d` / `BetLog:0%08d:%06d` 读取。先按列表长度加权选最终奖金桶，再随机取一条完整结果；不再按 0.847 概率选择 Mary 池。切换版本需同步重建缓存，旧 Mary / `_1` 不再读取。
