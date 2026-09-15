# 2060 Club Goddess Redis 交付说明

当前游戏的已验收能力清单将 Redis 契约标记为 `UNKNOWN_NOT_EVIDENCED`，并明确规定 `implementationAllowed=false`。现有证据没有 Redis Key、数据结构、member 编码、Round/Delivery 信封或压缩协议。

因此本目录不提供猜测或伪造的 Redis member。正式 Java Loader 和最小事实 Codec 已按能力门禁安全禁用 Redis 写入；若将 `redis.enabled` 改为 `true`，Loader 会明确拒绝启动。

机器可读交接见 `redis-pack-manifest.json`。待后续取得当前游戏的正式 Redis 平台契约后，必须先更新 `game-capabilities.json`、`protocol-handoff.json` 与 `rulesHash`，才能生成真实结果包。
