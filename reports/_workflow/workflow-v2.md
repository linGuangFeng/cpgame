# CPGame 工作流 V2 目录约定

唯一根目录为 `D:\work\hd\cpgame`。

- `resources/ID-name`：原始抓取归档，保留 Host、查询参数与取证结构，不可直接上传。
- `publish/ID-name`：唯一静态发布目录。根部必须直接包含 `index.html` 和 `publish-manifest.json`，不得包含 `_hosts`；S3 只上传这一整个目录。
- `captures/ID-name`：HAR、请求顺序和原始链路证据。
- `fixtures/ID-name`：接口回放样本和分类结果。
- `protocol/ID-name`：字段、状态机、模式和未覆盖项说明。
- `server-api/shared`：唯一常驻 Java Server API；通过 Game ID 路由多个游戏适配器，不得为每款游戏长期占用独立端口。
- `server-api/ID-name`：该游戏的协议适配、fixture、临时验收入口；允许 Python/JS 作为静态或 HTTP 兼容外壳，但禁止包含随机出牌、判奖、连消、免费局等游戏规则。
- `generator/ID-name`：该游戏唯一 Java Core 源码，包括随机牌面、确定性反推 Util、完整 Round Factory 和 Codec。
- `generator/ID-name/dist`：每个游戏独立交付一个 `游戏-redis-loader.jar` 和一个 `generator.properties`。
- `redis-pack/ID-name`：可选的中奖/特殊完整 Round 离线审计包，不是 Redis 加载前置步骤。
- `screenshots/ID-name`：浏览器验收证据。
- `reports/ID-name/current-status.json`：唯一机器可读状态，必须如实列出缺口。

## 单一规则源

一个游戏只能有一份 Java Core。共享 Server API 的游戏适配器和该游戏自己的 Redis Loader JAR 必须引用同一 Core；两者必须暴露相同的 `rulesVersion` 和 `rulesHash`，验收不一致即失败。禁止维护 Python/Java、Java/Java 两套可独立演进的规则实现。

## 运行方式

- 日常/生产：只启动一个共享 Server API，按 Game ID 承载多款游戏。
- 工作流验收：允许为当前游戏临时启动 Demo/静态服务和隔离端口，自动测试完成后必须停止并验证端口释放；不要求所有游戏 Demo 同时运行。
- Redis：一个游戏一个 Loader JAR。用户只执行 `java -jar 游戏-redis-loader.jar generator.properties`；Loader 边生成边按默认100局一个 `MULTI/EXEC` 事务直接写缓存，结束后退出。
- Redis 随机策略：每次自然随机生成完整局并由同一 ResultUtil 反推实际倍率；0倍跳过、不写 Redis，所有正倍数按实际倍率 Key 写入。禁止配置普通/特殊最小最大倍率或 `max-attempts-per-member`，禁止为了命中指定倍率反复搜索。
- Redis 每倍率容量：只使用一个 `generation.max-members-per-multiplier`（默认300）限制普通/特殊各自每个实际倍率本次最多生成数量；计数从本次启动的内存0开始，不读取 Redis 现有数量。达到上限后跳过该倍率；每次 `RPUSH` 后必须在同一 `MULTI/EXEC` 中执行 `LTRIM key -上限 -1`，溢出时删除最旧牌面、只留最新上限数量；普通和特殊配置目标全部完成后主动退出。
- 独立无奖：全部逻辑必须写在当前游戏的专用构造式生成器内。先识别玩法：Ways/无固定连线游戏按列内任意位置阻断连续符号；Payline/固定连线游戏按每条有效中奖线逐条阻断，严禁混用。Wild 按万能替代处理，并确保 Scatter/Bonus 等特殊奖牌不达到任何特殊模式触发条件；之后仍由同一 ResultUtil 复核。至少测试100000张，首次成功率不得低于90%，生成器源码内必须包含最多5次的 `for` 循环和恰好10张已校验预置无奖牌面，并在报告中说明实测首次成功率。

执行顺序必须先完成资源抓取，再把原始归档标准化为 `publish` 并使用普通静态 HTTP 服务验收；之后才能进入协议、唯一 Java Core、共享 Server API 适配器、单游戏 Redis Loader 和全链路验收。静态页面不得依赖 Java 路由补丁才能加载。

旧根目录 `D:\work\hd\cpgame-workflow` 和本次临时目录 `D:\work\hd\cpgame2` 迁移完成后只保留弃用说明，不再作为新任务输出位置。
