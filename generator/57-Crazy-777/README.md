# Crazy 777 Redis Loader

`dist/` 只交付：

- `crazy777-loader.jar`
- `generator.properties`（无 seed）
- `run-loader.cmd`（可双击，支持 `--no-pause`）

正式 Loader 使用 SecureRandom，从训练集完整联合 kernel 生成完整局并写入 Redis `192.168.10.3:6379/15`。
