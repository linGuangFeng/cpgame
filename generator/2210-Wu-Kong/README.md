# 2210 Wu Kong Java 结果生成器

`GameRuleCore` 是唯一规则实现，`ResultUtil` 独立反推模式与整数倍率，`RoundGenerator` 只从原厂训练集支持的完整联合状态自然抽样。正式 Loader 使用 `SecureRandom`，向 `192.168.10.3:6379 db=15` 写入预生成完整局；0 倍由规则自然产生。Controller 只依赖同一份源码并从 Redis 领取，缓存空不兜底。

构建：`mvn clean package`。交付只取 `dist` 中一个 JAR、`generator.properties`、`run-loader.cmd`。
