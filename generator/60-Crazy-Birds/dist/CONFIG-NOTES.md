# 60-Crazy-Birds 新生成与缓存说明

## 不兼容旧缓存

- 新 member 没有任何固定前缀、版本号或 rulesHash：普通局只保存 24 个符号字符，免费多 Step 只用 `|` 分隔。旧 `CB60A1`、`CB60B1;rulesHash;...` 都会被新版 Controller 明确拒绝。
- 核心按赔表只做一次“实际倍数 ×100”得到缓存倍率整数；赔付表最低 0.25 倍得到 25。
- 生成器倍率上下限配置直接填写最终缓存整数；当前用户配置为普通 0–25000、免费 500–30000，以 `generator.properties` 为准。配置禁止写 0.25，也不会再对配置值乘100；过滤、ZSET score 和 `BetLog` key 以同一个缓存整数为准。
- 部署时必须成对更新 `crazybirds-loader.jar` 与 `server-api/60-Crazy-Birds/dist/controller.jar`。只重生成缓存但继续运行旧 Controller 不可用。
- 旧 Redis 键由用户自行清理；构建、测试和离线审计都不会连接、清理或写入 Redis。

## 正式生成

- `generation.count` 是候选完整局的总尝试数，默认 100000000；`generation.batch-size` 默认 1000。
- 每批依次使用中性权重、逐牌单独放大权重，再复位中性；每批只放大一张牌，默认 3 倍，不累计。
- 牌面先按权重自然生成，再由独立 Java 核心计奖与分类；不读取任何抓包完整局模板，不按目标奖金拼牌。
- 免费中再次触发按本地默认策略拒绝；倍率越界和策略拒绝都计尝试且不补足。
- 保持原有缓存映射：普通完整局使用 `PerKeyList_008000060` / `BetLog:008000060:倍率`，免费完整局使用 `MaryKeyList_008000060` / `MaryLog:008000060:倍率`。倍率范围只过滤候选局，不改变缓存家族、类型或键名。

正式运行命令：

```text
java -jar crazybirds-loader.jar generator.properties
```

## 离线审计

审计走正式生成、校验、分类和编码链，但不连接 Redis：

```text
java -cp crazybirds-loader.jar com.cpgame.crazybirds.generator.GenerationAuditMain generator.properties 17000 generation-audit.json
```

审计报告中的 `accepted` 是离线接受数，`redisRetainedCount` 固定为空，不能当作 Redis 实际保留量。
