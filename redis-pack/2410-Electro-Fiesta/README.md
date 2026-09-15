# Redis 结果包合同

本目录只描述消费者合同，不保存 JSONL 或历史响应。正式写入由 `generator/.../dist/redis-loader.jar` 完成，正式读取由 Controller 完成。`sourceGameId` 与可覆盖的 `redis.game-id` 分开记录。

