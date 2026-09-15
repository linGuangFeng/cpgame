# 1809 Freedom Day Redis Generator

交付目录 `dist/`：`freedom-day-redis-loader.jar`、`generator.properties`、`start-redis-loader.cmd`。

## 运行

1. Redis 必须已在 `generator.properties` 的 `redis.host`:`redis.port` 监听（默认 `127.0.0.1:6379`）。没启动会报「无法连接 Redis」。
2. 改好 `redis.host` / `redis.port` / `redis.game-id` / 生成数量。
3. 双击 `start-redis-loader.cmd`，或：

```powershell
java -jar freedom-day-redis-loader.jar generator.properties
```

自然生成完整局，ResultUtil 反推整数倍率；0 倍按实际倍率写入 Redis，独立未中奖 Spin 编为 `#`；正倍数低于最小或超过最大倍数时丢弃。每批 `MULTI/EXEC` 提交 `ZADD+RPUSH+LTRIM`。

## 分层（换游戏时抄这个，不要抄 1809 的牌面）

| 层 | 1809 类 | 职责 |
|---|---|---|
| 出牌 | `FreedomDayBoardGenerator` | 只出当前游戏可见状态（1809：prop/trl/grids/gf/sl） |
| 无奖 | `FreedomDayIndependentLossGenerator` | 按玩法构造独立 0 倍盘，ResultUtil 复核 |
| 判奖 | `FreedomDayResultUtil` | 确定性反推，禁止第二套规则 |
| 整局 | `CompleteRoundFactory` | 付费起点一次生成到连消/免费结束 |
| 极简 member | `CompleteRoundCodec` | 只存不可反推事实；还原后必须还能画出可见状态 |
| 写入 | `RedisDirectLoader` | 连 Redis、分普通/特殊、按实际整数倍率写入 |

Redis member：独立未中奖 Spin 用 `#`；其他每页 68 字符（34 符号 + 占格/框），Spin 用 ASCII `|`。只有单页、无中奖、无免费触发且不改变后续状态的 Spin 可压缩。连消链含结束盘整体保留。解析器遇到整个 token `#` 才调用零倍生成器，物化后的完整局应缓存复用，不能每次交付或 History 再随机解码。旧 34/68 字符编码仍可读取，`0` 不是特殊标记。倍率 Key 为 `BetLog:0%08d:%06d` / `MaryLog:%09d:%06d`。

权重来自抓包第一页计数，不是手填。特殊入口只把开局 Scatter 权重 ×10。购买/摆牌不进这条 Redis 路径。

## 换游戏不要带过去的

- 1809 的 30 格、13 个符号、空 grids、小数倍率、JSON member
- 本机没 Redis 就当生成器坏了
- 用自己 Factory 的输出当唯一验收
