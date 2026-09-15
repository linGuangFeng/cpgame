# 1809 - Freedom Day（CP 内部 gid 2260）

## 地址

- 游戏：`http://127.0.0.1:18090/?ai=luck_single_10229&btt=1&gid=2260&l=pt&language=pt-br&sip=127.0.0.1%3A18090&t=local-replay&token=local-replay`
- 本地出奖控制台：`http://127.0.0.1:18090/local-control.html`
- 多语言：把地址中的 `l` 和 `language` 改为 `bn-bd`、`en-us`、`es-es`、`fr-fr`、`id-id`、`ko-ko`、`pt-pt`、`th-th` 或 `tr-tr`；也可直接使用 `pages/1809-Freedom-Day/<language>/launch-url.txt`。

先运行根目录 `start-1809-local.ps1`。当前服务已在 `18090` 端口运行。

## 当前 Demo 出奖方式（无鉴权）

- 页面中的 `local-replay` 只是客户端必填参数的固定占位，不做登录、Token、Redis或余额账户验证。
- 普通 Spin 和 Feature Buy 不再轮询 `replay-results.json`，每次由 `tools/freedom_day_board_generator.py` 实时随机生成 30 个主盘格和 5 个横盘格。
- `tools/freedom_day_result_util.py` 是独立、无随机行为的反推函数，仅根据传入牌面、押注和当前倍率计算 Ways、中奖位置、奖金、Scatter和免费次数。
- `tools/freedom_day_demo_engine.py` 只负责连消、免费局队列和客户端协议封装；页面响应带 `_source=random-board+deterministic-result-util` 便于辨别。
- 测试：在 `tools` 目录执行 `python -m unittest -v test_freedom_day_demo.py`。

## 已完成的本地闭环

- 页面、核心静态资源、初始化 API。
- 500 个随机牌面反推一致性测试，以及实时随机 Spin 与运行期历史汇总/列表。
- 游戏内 Feature Buy：确认窗显示成本 R$15.00，实际请求带 `bet_type=3`。
- Feature Buy 触发四个 Scatter，进入并自动完成 10 次 Free Spin。
- 免费旋转使用 `frees.st=10...0`、`tt=10`、`twa/lwa/m` 连续状态，最终显示 TOTAL WIN R$10.00。
- 控制台可指定下一局 50 倍 Big Win、自然触发 10 次 Free Spin、活动/必中模拟。
- 同一服务会话余额连续结算，不再使用每个 fixture 的固定余额。
- 已拉取并浏览器验证 9 个原生资源语言；`pt-br` 作为平台别名映射到 `pt-pt`。
- 已完整滚动 Paytable，补齐 5 张只在帮助内容中请求的隐藏图片。

最终端到端余额实测：

`1000.00 → Feature Buy -15.00 → 985.00 → Free Spin +10.00 → 995.00 → Big Win +9.80 → 1004.80 → 活动必中 +1.80 → 1006.60`

## 如何测试出奖

1. 打开游戏，点击 GET STARTED。
2. 购买模式：直接点击游戏里的 FEATURE BUY，再点 START。
3. 其他模式：在“本地出奖控制台”点一种模式，回游戏点击一次 Spin。
4. 服务器重启后余额恢复 R$1,000.00；同一次运行中的每局会连续累计。

## 文件

- `api/1809-Freedom-Day/replay-results.json`：500 局普通 Spin fixture。
- `api/1809-Freedom-Day/simulation-scenarios.json`：特殊模式完整响应序列。
- `api/1809-Freedom-Day/local-simulation-results/results.ndjson`：实际发给客户端的特殊结果记录。
- `api/1809-Freedom-Day/local-replay-requests/requests.ndjson`：本地请求记录，令牌不落盘。
- `evidence/1809-Freedom-Day/special-modes/`：购买、免费旋转、Big Win、活动必中和控制台截图。
- `evidence/1809-Freedom-Day/languages/`：各语言实际渲染截图。
- `docs/1809-Freedom-Day/languages.md`：语言映射、独有图集及隐藏资源判定。

## 数据边界

这些是明确标注的本地确定性模拟结果，不是线上提供方真实抓包，也不用于验证真实 RNG/赔率。真实特殊模式响应、真实 `act_id` 活动请求和详细连消 `win_arr` 样本仍需在线抓包阶段补充，不能把本地模拟 fixture 冒充真实协议样本。
