# Controller contract v3

平台应为每次启动注入一个空闲的 50000–59999 端口。Controller 是单 Java 进程、单监听端口；运行时新付费局先随机选择中/不中，再从所选 Redis 索引中的实际倍率随机领取一条完整局。激活 Round 的后续请求只按 `deliveryIndex` 投影，缓存为空直接返回 503，不读取 fixtures、不当场出牌。
