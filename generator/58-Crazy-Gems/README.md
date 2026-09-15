# Crazy Gems Redis 结果生成器

3×3 排面、5 条中奖线、矿车倍率 `rpx`。一次付费 Spin 即终局，无免费旋转。

```sh
java -jar crazygems-redis-loader.jar generator.properties
```

## 一个缓存池

普通结果与矿车奖励统一写入 `PerKeyList_0%08d` 索引，列表为 `BetLog:0%08d:%06d`；游戏 ID 填 8 位，最后 6 位是最终中奖倍数×10。结果页显示一个 `PerKeyList_0` 分组，不再写入 Mary 或 `_1`。

例如 `redis.game-id=8000058`，最终中奖 20 倍的所有排面都写入 `BetLog:008000058:000200`，索引为 `PerKeyList_008000058`。

每条结果仍为 10 位 ASCII：9 位符号 + 1 位矿车倍率。倍率编码 `1/2/3/5/A/F` 对应 `1/2/3/5/10/15`。矿车独立抽一次，不依次叠乘。同一排面不同矿车倍率是不同结果；最终奖金相同的结果共用一个桶。

## 内存去重与数量

生成器用一个 `HashSet<String>` 保存已接收的完整 10 位结果，跨批次保留。重复结果不入队、不占目标条数或桶配额；满桶后拒绝的候选不占去重内存。

`generation.clear-existing=false` 时，先把当前统一池的已缓存结果载入集合，防止再次追加相同内容；现有重复条目不会自动删除。去重面向单生成进程，不协调同时写入的多个生成器。

`generation.clear-existing=true` 时，先清空统一池，并按旧索引清理本游戏遗留的 Mary / `_1` 列表，然后重建。旧池不会被 Controller 继续读取；切换后需重新生成缓存。生成器启动脚本不会自动执行迁移。

## 配置

- `generation.total-members`：本次接收的唯一结果总目标。旧配置缺失此项时兼容读取 `normal-count + special-count`，不再分配两种结果的配额。
- `generation.max-members-per-multiplier`：所有结果共用的每桶上限，生成计数和 `LTRIM` 一致。追加生成时超出容量会淘汰最早条目。
- `generation.min-win-multiplier` / `generation.max-win-multiplier`：统一最终奖金范围，单位为中奖倍数×10，包含边界。原分池范围和 special 上限不再生效。
- `generation.symbol.<符号>.normal-weight`：实际读取的符号权重，所有结果共用。
- `generation.rpx.<1/2/3/5/10/15>.weight`：普通生成入口矿车权重；增强入口将 `rpx>1` 的权重乘 10，每生成 1000 次切换一次入口。生成入口不再决定缓存 key。
- 负权重、全零权重、增强权重溢出会在连接 Redis 前报错。

0 倍结果不入缓存；Controller 按 `round.loss-probability` 使用独立无奖生成器。中奖仍由同一 `CrazyGemsResultUtil` 校验。

总目标不能超过可生成的唯一结果数量及分桶容量，否则生成循环无法达到目标。当前发行配置继承原两个目标之和，没有擅自降低生成规模。

构建使用 `mvn package`；保留已存在的 `dist/generator.properties`，仅首次打包复制默认配置。生成器和 Controller 需要使用同一版本及相同 Redis/game-id 配置。
