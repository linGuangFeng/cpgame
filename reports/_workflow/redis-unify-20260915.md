# 全游戏 Redis 配置同步记录

日期：2026-09-15

## 交付与验证

- 38 个页面游戏及 demo 统一使用 `18.234.101.161:8021`、DB `0`，其余连接参数按用户要求设置。密码不记录在本报告。
- `redis.game-id = 8000000 + 页面展示 ID`，游戏协议 ID 保持原值。
- 已同步 123 个配置文件，共 196 个源码/配置文件；69 个 jar 交付位置通过 SHA256 校验。
- 38/38 页面缓存查询的地址、端口、数据库和缓存编号核对通过。
- 36 个 Redis 生成器隔离启动检查通过；2、1400 的既有生成器为实时目录工作流，不连接 Redis。
- 11 个 jar 缓存键检查通过；后台 29 个测试、2300 Controller 4 个测试通过。
- 41 内存接口返回支持（测试键为空，0 bytes）；2300 的 BetLog:008002300:000010 返回支持，12237 bytes。
- 修正 2300 写死的旧 Redis 错误提示，现显示实际连接参数。

## 运行状态

- demo 41、58、60、2470 已恢复，页面均显示 RUNNING。
- 2300 demo 尚未恢复：实际连接 DB 0、gameId=8002300，但缺少普通局 0 倍结果池，启动校验拒绝启动。未迁移、删除或伪造缓存数据。
- 管理后台仍运行 cpgame-admin-20260915-105441.jar；新版已交付 admin/target 与 admin/var/cpgame-admin.jar.next。需在 Windows 上运行 admin\restart.cmd 加载新版后台。
- 未中断已有生成任务；已启动任务使用启动时加载的配置，后续新任务使用新配置。

## ID 对照

| 游戏目录 | 页面 ID | redis.game-id |
|---|---:|---:|
| 2-Jungle-Kings | 2 | 8000002 |
| 8-Jurassic-Jungle | 8 | 8000008 |
| 16-Jungle-Fruit | 16 | 8000016 |
| 32-Jungle-Treasure | 32 | 8000032 |
| 33-Jungle-Party | 33 | 8000033 |
| 41-Lucky-Panda | 41 | 8000041 |
| 42-Lucky-Dragon | 42 | 8000042 |
| 43-Lucky-Wheel | 43 | 8000043 |
| 45-Rio-Carnival | 45 | 8000045 |
| 50-Lucky-Cat-II | 50 | 8000050 |
| 52-Cyber-GO | 52 | 8000052 |
| 54-Fishing-GO | 54 | 8000054 |
| 56-Crazy-Piggy | 56 | 8000056 |
| 57-Crazy-777 | 57 | 8000057 |
| 58-Crazy-Gems | 58 | 8000058 |
| 60-Crazy-Birds | 60 | 8000060 |
| 61-Saci | 61 | 8000061 |
| 1090-sharpshooter | 1090 | 8001090 |
| 1380-Hidden-Realm | 1380 | 8001380 |
| 1400-Blessing-of-Ice-and-Fire | 1400 | 8001400 |
| 1407-mac-Coin-Master-GO | 1407 | 8001407 |
| 1670-Christmas-Gift | 1670 | 8001670 |
| 1780-Glacier-Treasure | 1780 | 8001780 |
| 1809-mac-Freedom-Day | 1809 | 8001809 |
| 1810-Treasure-Hunt | 1810 | 8001810 |
| 1830-Hotpot | 1830 | 8001830 |
| 1910-Churrasco | 1910 | 8001910 |
| 1940-beach-fun | 1940 | 8001940 |
| 2010-EDM-Mania | 2010 | 8002010 |
| 2060-Club-Goddess | 2060 | 8002060 |
| 2110-Bee-Workshop | 2110 | 8002110 |
| 2210-Wu-Kong | 2210 | 8002210 |
| 2290-Samba-Sensation | 2290 | 8002290 |
| 2300-Monster-Slayer | 2300 | 8002300 |
| 2350-Curupira | 2350 | 8002350 |
| 2410-Electro-Fiesta | 2410 | 8002410 |
| 2470-Lucky-Night-Market | 2470 | 8002470 |
| 2001007-Magic-Scroll-2 | 2001007 | 10001007 |
