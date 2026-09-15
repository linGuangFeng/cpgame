# Crazy Piggy Redis 结果包

当前游戏证据没有确认 Redis Key、member 编码或编码长度，因此本目录明确保持安全禁用，不能猜测写入格式。

完整 Round 的最小事实编码和恢复校验由 Java generator 提供，但在 Redis 契约获得当前游戏权威证据前，Loader 会在任何写入发生前拒绝启动 Redis 模式。详见 `redis-disabled.json`。
