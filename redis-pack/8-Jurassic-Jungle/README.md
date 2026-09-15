# Jurassic Jungle Redis pack

正式运行读取 `192.168.10.3:6379` database 15。

- 普通未中奖/普通中奖：`PerKeyList_000000008` + `BetLog:000000008:{units}`
- 龙模式完整局：`MaryKeyList_000000008` + `MaryLog:000000008:{units}`

member 为 `JJ8V1.` 前缀的极简 ASCII。Demo 只 LPOP 预生成完整局，禁止当场出牌。
