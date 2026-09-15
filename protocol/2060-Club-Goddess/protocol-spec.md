# 2060 Club Goddess 协议与状态规则

## 身份与版本

- 游戏 ID：`2060`
- 真实名称：`Club Goddess`
- ID-name：`2060-Club-Goddess`
- 规则版本：`2060-protocol-r1`
- rulesHash：`sha256:dab39055275e95492f5e8339ce38fdff37e09f02f00a38140a0583cb2c1b4d34`
- `initialData.data.game_info.name=Magic Scroll` 是与目录映射、标题图和 Game2060 bundle 冲突的旧元数据，不作为真实名称。

本文件是便于实现核对的文本视图。字段级来源见 `field-evidence-matrix.json`，机器能力清单见 `game-capabilities.json`，逐行为实现与独立验收契约见 `protocol-handoff.json`。

## 传输、序列化与签名

- API 基址：页面协议 + 启动参数 `sip` + `/cp`；现场值为 `https://api.omgapibra.com/cp`。
- 游戏接口使用 HTTPS `POST`，请求体为 `application/x-www-form-urlencoded`。
- 标量按 `key=value` 输出；对象和数组先执行 `JSON.stringify`。当前前端拼接参数时不调用 `encodeURIComponent`。
- 响应是 UTF-8 明文 JSON；应用层未观察到额外压缩或加密。通用二进制 codec bundle 未用于已观察的游戏 HTTP 请求。
- 签名前对尚未包含 `signapt`、`expire` 的原始请求键按 JavaScript 默认字典序排序；标量写成 `key=value`，对象/数组写成 `key=JSON.stringify(value)`，用冒号连接为 canonicalString。
- 签名公式：`MD5(frontendSecret + expireMillis + canonicalString + "aptsignature")`。前端随后先附加 `signapt`，再附加 `expire`，最后输出表单。
- `expireMillis` 使用由 `/config/initialData` 的 `initial_config.current_sys_time` 校准后的毫秒时间。

## 首载请求顺序与端点

1. `POST /config/initialData`：读取下注配置、服务端时钟和解析后的语言。必需字段：`gid,currency,language,ai,signapt,expire`。
2. `POST /account/getUserInfo`：用启动 token 建立会话并取得余额。必需字段：`token,gid,language,deviceid,Type,source,os,ai,signapt,expire`。
3. `POST /single_game.Game/initRoom`：取得当前或可恢复的 result-shaped 快照。首次抓取为 `oid="0"`，不是付费 Round。
4. `POST /activity/getActivity`：活动列表；现场为空，不能等同于 Scatter 免费游戏。
5. `POST /goldgame/single_game_user_gold_history`：七日聚合 History。
6. `POST /single_game.Game/gameResult`：付费普通局；前端代码也为潜在 Free Step 调用同一路径。普通请求字段：`token,bet_gold,level,gid,act_id,language,signapt,expire`。
7. 再次请求 `single_game_user_gold_history` 刷新聚合记录。
8. `POST /goldgame/single_game_user_history`：按日期分页取得订单及 `results[]` 相邻步骤。

## 核心响应字段

`gameResult` 外层为 `code,msg,time,data`。`data` 已观察字段：

- 账务与标识：`oid,start_gold,bet_gold,total_win,change_gold,end_gold,bet,level,type`。
- 牌面与中奖：`odds,props.win_arr`；每个中奖项包含 `odd,pos_arr,tw,ways,wp`。
- 模式状态：`frees.st,frees.tt,frees.twa,frees.m,frees.spe_num`，以及普通样本中仅见默认值、语义未确认的 `frees.ba,frees.bet,frees.l`。
- 其他未确认字段：`setting_id,small_game_type,props.spe_num`。不得为非零值猜测语义。

普通已抓取账务恒等式：`end_gold = start_gold - bet_gold + total_win`。十局聚合为下注 `30`、净变化 `156.4`，与 9 LOSS、1 WIN 一致。

## 牌面与中奖规则

- 可见牌面为 `5 × 3`，共 15 格；索引为列优先：`position = reelZeroBased * 3 + rowZeroBased`。
- 缓冲/隐藏行数量没有证据，保持 `UNKNOWN`。
- 中奖模型字符串为 `WAYS`，共 `243 Ways`；固定线 `paylineCount` 不适用。
- 从第 1 轴开始由左至右，至少连续 3 轴相同符号；Ways 数为各连续轴命中位置数的乘积。
- 基础派彩公式经规则、前端代码和中奖样本交叉验证：`bet × level × odd × ways`。
- Wild 为符号 10，仅出现在第 2、3、4 轴并替代 Scatter 以外的符号；没有 Wild 自身现金奖证据。
- Scatter 为符号 9；仅确认触发规则，没有 Scatter 自身现金奖证据。
- RTP 规则页披露值为 `96.3%`。

## Round、Delivery 与 Step 边界

- 真实付费 Round 起点：用户授权的 `gameResult` 请求返回 `type=1` 且 `oid` 非零。
- `initRoom` 首载 `oid="0"` 是快照，不计入付费 Round，也不进入 `spin-index.jsonl`。
- Round 主键为 `oid`。实时响应没有独立 `deliveryId` 或 `stepId` 字段。
- History 的订单 `results[]` 是当前游戏唯一可见的同一付费订单下相邻步骤容器。
- 已抓取普通 LOSS/WIN 均为单步终止：`type=1 && frees.st==0`，History 中 `results.length=1`。
- 一次完整特殊 Round 必须从付费起点持续到所有后续状态终止；后续 Step 不得计作新的付费 Round。

## 已确认普通状态机

前置状态为 `IDLE` 或 `AUTO_IDLE`，会话有效、下注被接受且余额充足。客户端调用普通 `gameResult`，服务端扣除 `bet_gold`、计算 `props.win_arr` 和 `total_win`，并返回更新余额。`frees.st==0` 时该 Round 立即终止。该契约已有真实 LOSS、WIN、余额连续性及 History 独立证据，可实现并独立复核。

## 未确认且必须禁用的状态

规则页确认 3/4/5 个 Scatter 分别授予 12/15/20 次 Free Spins；规则页也描述 Free 模式倍率从 x2 开始、每收集 3 个 Wild 增加 2、进度重置、最高 x20。前端代码读取 `frees.st`、`frees.m`、`frees.spe_num`，显示 `type=2`，并含继续、重触发及 `st==0` 后显示 `twa` 的分支。

但 10 个真实付费 Round 中未捕获付费触发响应、相邻 `type=2` 响应、非终止/终止 Free Step 或多步骤 History。所以下列行为统一为 `UNKNOWN`，不得生成、交付或按代码分支猜测 wire 状态：

- `BHV-FREE-SPINS-TRIGGER`
- `BHV-FREE-WILD-MULTIPLIER`
- `BHV-FREE-STEP-STATE-MACHINE`

请求失败后的 `gameResult -> initRoom -> retry` 仅有前端代码分支、没有原始相邻请求响应证据，`BHV-REQUEST-RECOVERY` 同样为 `UNKNOWN`，只可作为客户端兼容提示，不能作为已确认服务端契约。

## History 契约

- 聚合端点返回日期维度的 `bet_gold`、`change_gold` 及统计字段 `total_bet_gold,total_change_gold`。
- 明细端点返回付费订单；普通订单 ID 形式为 `oid + "-2060"`，`results[0]` 镜像该局结果且为终止步骤。
- 前端支持翻页显示多项 `results[]` 和 `type=2`，但当前没有特殊 Round 的运行时订单，禁止构造其精确分组规则。

## 实现边界

- 只有 `protocol-handoff.json` 中 `status=CONFIRMED` 的行为可实现；`UNKNOWN` 必须禁用或安全拒绝。
- `symbolWeights`、reel strips、Redis key/member/编码/压缩契约均无当前游戏证据，保持 `UNKNOWN`。
- 抓包与 fixtures 仅作为独立协议 oracle，运行时不得读取、轮播、随机选择、复制或硬编码历史响应。
- 能力允许的普通独立 LOSS 可由正式 Java 生成链路实时随机产生，但必须由独立 ResultUtil 按本文件及字段证据复核。
- 任何未来特殊模式升级都必须先加入相邻原始步骤证据，再同步更新行为契约、能力清单和 rulesHash；当前不得继续 Spin 以补凑场景。
