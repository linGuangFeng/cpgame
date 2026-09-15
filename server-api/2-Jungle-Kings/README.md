# Jungle Kings（raw gid 2）本地 Server API

服务只处理 raw gid `2`，监听 `0.0.0.0`，并复用 generator 工程中的唯一 Java
`GameRuleCore`。运行时不读取 captures、fixtures，也不会连接原站。

已实现证据中的接口：

- `POST /cp/api/v1/auth/verify`
- `POST /cp/api/v1/jungle-kings/config`
- `POST /cp/api/v1/jungle-kings/spin`
- `POST /cp/api/v1/jungle-kings/log-list`
- `POST /cp/api/v1/jungle-kings/log-view`

Spin 按 1400 做法实时一把生成：`ckl` 选倍数列表，请求倍数向下落到列表，再从倍数→牌面
目录取盘并经 `ResultUtil`/`IndependentVerifier` 复核。不读 Redis、不读 fixtures。重复
`request_id` 只重放同一响应。可选 form 字段 `odd`/`odds` 指定目标倍数（Demo 前端不传，
则 28% 抽正倍数否则 0）。
