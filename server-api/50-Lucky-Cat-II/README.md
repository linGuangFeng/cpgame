# Lucky Cat II Controller v3

唯一 Java 进程。平台注入 `--port`（50000-59999）。Demo 结果只从 Redis `192.168.10.3:6379` db 15 领取预生成完整局；Redis 为空时明确失败，不现场出牌、不读 fixtures。

交付：

- `dist/controller.jar`
- `dist/demo-controller.properties`（`controller.contract-version=3`）
- `dist/controller.properties`（无 seed、无写死端口）
