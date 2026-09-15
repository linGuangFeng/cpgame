# Freedom Day 1809 Redis Pack

每个 Redis LIST member 是一局从付费开始到全部连消/免费局结束的完整事实牌面；中奖结果由 `CompleteRoundCodec` 使用 `FreedomDayResultUtil` 反推。

生成参数：`sourceGameId=1809`，`redisGameId=1809`，普通倍率 `1..10000000`，特殊倍率 `1..10000000`，最大连续中奖 `10`。

```powershell
mvn.cmd -q test
mvn.cmd -q exec:java -Dexec.mainClass=com.cpgame.replica.freedomday.RedisPackCli -Dexec.args="--output redis-pack/1809-Freedom-Day --seed 18092260 --normal-count 100 --special-count 30 --redis-game-id 1809 --normal-min-mul 1 --normal-max-mul 10000000 --special-min-mul 1 --special-max-mul 10000000 --max-consecutive-wins 10"
Get-Content redis-pack/1809-Freedom-Day/redis-import.resp -Raw | redis-cli --pipe
```

普通索引：`PerKeyList_000001809`；特殊索引：`MaryKeyList_000001809`。
