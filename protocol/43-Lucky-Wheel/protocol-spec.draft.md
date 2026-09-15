# 43 Lucky Wheel 协议捕获草案

状态：静态入口、规则、奖表、语言和前端接口判定已取证；认证运行时尚未开始。本文件不是最终协议，不得用于 Java 实现。

- 原站入口：`https://hms-paddle.com/`
- 静态入口：`https://static.cpgame.io/43/`
- 当前分配账号：`<redacted>`，未提交登录，未持久化口令或 Token
- ID-name：`43-Lucky-Wheel`，由门户 CP 条目、静态标题和前端 `_gameId=43` 交叉确认
- 语言：`en/es/pt/tr/id/bn/th/vi/fr/ko`，已逐一使用实际 `l` 参数首载
- 结构：4 列单行，前三列组合完整数字，第四列为特殊轮
- 固定线数：`PayLineCount=1`，来自当前 main bundle，不是 Bet Level 推断
- 静态确认模式：`md=1` 倍率轮、`md=2` 免费重转、`md=3` Scatter/Lucky Wheel
- 运行时接口：`auth/session`、`lucky-wheel/config`、`lucky-wheel/spin`、`log-list`、`log-view`
- 付费 Round：0；`spin-index.jsonl` 保持 0 条真实起点

`md=2` 的前端会自动发起相邻 Spin，后续采集必须将所有相邻免费重转并入同一 `roundId`。`ss` 的终止语义、原始 Round ID、字段类型和重触发条件仍须由分配账号的原始相邻 HTTP 证据确认，禁止猜测。

当前硬阻塞：执行器要求用户在获知风险后再次明确批准把已提供的口令提交到 `https://hms-paddle.com/`。获批后只能使用分配账号，不得复用其他账号会话。
