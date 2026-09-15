# 本地出奖模式说明

## Feature Buy

- 入口：游戏主界面 FEATURE BUY。
- 购买成本：总押注 `R$0.20 × 75 = R$15.00`。
- 请求差异：`bet_type=3`。
- 返回链：购买触发局（type=3、四 Scatter、st=10）后接 10 个免费结果（type=2、st=9...0）。

## 自然 Free Spin

- 在控制台点“下一局：自然触发 10 次 Free Spin”，再回游戏点 Spin。
- 第一局为普通类型 type=1，包含四 Scatter；后续复用已验证的 10 局免费状态链。

## Big Win

- 控制台预设后，下一次普通 Spin 返回 R$10.00。
- 最小总押注 R$0.20，对应 50 倍，客户端播放 Big/Mega Win 动画。

## 活动/必中模拟

- 控制台预设后，下一次普通 Spin 固定返回 R$2.00。
- 用于验证“服务器指定下一局出奖”的本地能力；不声称等同于线上真实 `act_id` 活动协议。

## 余额规则

服务端保存当前运行期间的模拟余额，每次按 `change_gold` 累加并重写 `end_gold`。重启本地服务后恢复初始 R$1,000.00。

## 记录规则

普通请求记录在 `local-replay-requests/requests.ndjson`；实际下发的特殊模式完整响应记录在 `local-simulation-results/results.ndjson`。所有 fixture 带 `_fixture` 和 `_scenario` 标记，防止与真实抓包混淆。
