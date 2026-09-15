# gid42 Lucky Dragon 协议规格（冻结证据，交付 v18）

本规格只引用 `ORIGINAL_AUTHORIZED_HTTP` 的 1131 个付费起点、1131 个终态和 1131 个 History 1:1 记录，以及公开静态资源证据。采集已冻结；复刻运行时不得读取这些响应或任何 fixture。

## HTTP

- Origin：`https://api.omgapibra.com`；请求 `POST application/x-www-form-urlencoded`。
- 表单按字段插入顺序编码，`encodeURIComponent` 后把 `%20` 替换为 `+`；没有确认的业务签名。
- Auth：`/cp/api/v1/auth/verify`，字段 `ai,btt,t,gid`。
- Config：`/cp/api/v1/lucky-dragon/config`，字段 `t,gid`。
- Spin：`/cp/api/v1/lucky-dragon/spin`，字段 `bl,bs,t,gid`，可选 `ec`。
- History list：`/cp/api/v1/lucky-dragon/log-list`，字段 `page_index,begin_at,end_at,t,gid`。
- History detail：`/cp/api/v1/lucky-dragon/log-view`，字段 `transfer_id,t,gid`。
- Ping：`/cp/api/v1/ping`，字段 `t,gid`。

Spin `data` 必须提供 `ba,bl,bs,ca,gt,pb,rpx,rskl,wa,wsk`；History list 的 `ll[]` 行必须提供 `ba,baf,bid,ca,fe,gm,gt,tis,wa`；History detail 必须提供 `ba,baf,balance_after,bet_level,bet_size,bid,bl,bs,ca,cc,created_at,cs,gt,pb,rpx,rskl,wa,wsk`。

## 规则核心

`bs` 只能为 0.50、5.00、20.00 BRL，`bl` 为 1–10；付费金额为 `bs * bl`。冻结 Config 奖表为 H0=0、H1=111、H2=21、H3=5、H4=1、WILD=1111。三轴结果以 WILD 替代非 WILD 符号；中心 WILD 的 `rpx` 为 3、5、9，中奖时派彩为 `paidBet * payMultiplier * rpx`（无倍率时乘 1）。冻结样本实测 H1 为 55.50（0.50 bet），H2 为 10.50，中奖中心 WILD 倍率样本分别为 X3/X5/X9。

`rpx` 不是单独的中奖或特殊分类依据：1131 局中有 10 局中心 WILD 带 3/5/9 倍率但三符号不匹配、`wa=0`，这些必须仍归类 `ORDINARY_LOSS`。只有 WILD 替代后形成三符号匹配且 `wa>0` 才分类为 `WILD_MULTIPLIER_X3/X5/X9`，对应完整 Round 数为 80/40/35。

每个付费 Spin 都是一个终态 Round；冻结证据没有 continuation step。完整 Round 只通过 Redis `LPOP` 领取一次，`deliveryIndex=0`、`terminal=true`，History 行的 `tis` 是 transferId。幂等重放只返回已领取 member 的同一投影，不再次领取。Controller 不实时出牌；正式 server-api 和 Redis Loader 使用同一 `GameRuleCore`，并由 `IndependentRoundVerifier` 复算。

## v18 运行时边界

`server-api/42-Lucky-Dragon/dist/demo-controller.properties` 使用 Controller 合同 v3，固定声明 `controller.jar=dist/controller.jar`、`demo.mode=managed-process`、`controller.port-argument=--port` 和 `controller.port-range=50000-59999`，不写死 `controller.api-port`。平台为当前游戏启动唯一受管 Host JVM 和一个动态 5xxxx 监听端口；Controller 在同一 JVM 内通过虚拟 Provider 注册路由，严格校验注入端口，不创建第二监听器或派生 Java/Node 子进程。没有平台 Provider 时主动拒绝 standalone 启动。

2026-09-04 Windows 正式验收在 PID 23420、动态端口 `50055` 启动合同 v3 Controller，同一游戏只有一个 Java PID 和一个监听端口，且无 Java/Node 子进程；`192.168.10.3:50055` 的原页面、Auth、Config、Spin、History、余额均通过。端口冲突测试占用 50028 后平台分配 50029。进程池从 42 起顺序启动 11 个游戏，第 11 个 `2001007-MagicScroll2` 明确淘汰最早的 `42-Lucky-Dragon`，淘汰后 PID/监听均消失；单停与全停均零残留。双游戏测试中 42 与 1830 使用独立 PID 23420/488 和端口 50055/50056，停止 1830 不影响 42。完整机器证据见 `reports/42-Lucky-Dragon/replication-stage-status.json`。

## 明确未知项

原厂 reel strips、原厂符号概率、原厂 RTP/波动率以及 H4 的真实轴位置均为 `UNKNOWN`。`generator.properties` 的正数权重只来自冻结样本经验频数并可调，明确不代表上述原厂参数；运行时不得从其他 gid 猜测或补齐这些未知项。
