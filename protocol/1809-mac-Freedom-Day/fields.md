# API 与字段注释

## 公共请求字段

| 字段 | 含义 |
|---|---|
| `token` | 启动接口签发的游戏会话令牌；归档请求中必须脱敏 |
| `gid` | CP 内部游戏 ID，本游戏为 `2260` |
| `ai` | 商户/接入标识，本次样本为 `luck_single_10229` |
| `language` | 接口语言；由公共 HTTP 层自动附加 |
| `page` / `page_size` | 历史记录分页参数，客户端固定每页 30 条 |
| `day` | 某日历史的 Unix 时间值/日期键，以真实响应为准 |

## 接口

| 路径 | 方法 | 功能 | 特有参数 |
|---|---|---|---|
| `/cp/single_game.Game/initRoom` | POST form | 初始化、赔率、当前/未完成局状态 | `token`, `gid` |
| `/cp/single_game.Game/gameResult` | POST form | 普通 Spin、免费 Spin 或购买功能 | `bet_gold`, `level`, 可选 `act_id`, `bet_type` |
| `/cp/goldgame/single_game_user_gold_history` | POST form | 按日汇总 | `page=1..n`, `page_size=30` |
| `/cp/goldgame/single_game_user_history` | POST form | 某日逐局明细 | `day`, `page=1..n`, `page_size=30` |

## 初始化/Spin 响应核心字段

以下字段由 1809 客户端源码直接读取；实际类型和值域需由抓包样本进一步确认。

| 字段 | 客户端用途 |
|---|---|
| `oid` | 局/订单标识；初始化时用于判断是否有未完成局 |
| `bet_gold` | 本局总押注；免费/活动局可能有额外逻辑 |
| `level` | 押注等级 |
| `change_gold` | 本局净变化；历史列表显示输赢 |
| `end_gold` | 本局完成后余额；主界面据此更新余额 |
| `win_gold` | 当局派奖/展示赢分；客户端会结合 `change_gold`、`bet_gold` 修正展示 |
| `props` | 每次消除/掉落阶段的棋盘及中奖数据数组 |
| `prop_odds` | 图标 ID 到数量/赔率的配置表 |
| `frees` | 免费旋转状态对象，详见下表 |

## `props[]` 常见字段

| 字段 | 客户端用途 |
|---|---|
| `prop` | 竖向主棋盘的图标序列；客户端按列重新展开 |
| `prop_h` | 顶部横向区域/额外棋盘序列（名称依实际响应确认） |
| `win_arr` | 中奖组合数组 |
| `win_arr[].wm` | 该中奖组合金额，客户端累加阶段赢分 |
| `tw` | 当前掉落/阶段总赢分 |

## `frees` 免费旋转字段

| 字段 | 客户端用途 |
|---|---|
| `st` | 剩余免费旋转次数；`st > 0` 表示免费模式继续 |
| `tt` | 本轮累计/总免费旋转次数；触发和追加时用于动画及计数 |
| `twa` | 免费旋转累计赢分；结束弹窗显示总赢分 |
| `lwa` | 上一段/附加累计赢分，客户端在恢复状态时与 `twa` 组合 |
| `m` | 免费模式当前倍数 |

## 历史响应

历史 UI 直接读取响应数据体中的：

- `list[]`：按日或逐局记录。
- `statistics.total_bet_gold`：汇总押注。
- `statistics.total_change_gold`：汇总净输赢。
- 逐局记录常用字段：`order_id`、`bet_gold`/`bet`、`change_gold`、`results`、`extend`。
- `extend.act_id` 存在且非零时，历史详情会把该局标识为活动/免费下注来源。

尚未获得真实响应的字段不会在本文凭源码猜测其精确业务含义；抓包后应在字段表中补充真实样例、类型、可空性和观察到的范围。
