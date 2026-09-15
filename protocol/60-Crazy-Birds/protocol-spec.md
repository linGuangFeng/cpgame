# Crazy Birds（60）协议与状态机规格

- ID-name：`60-Crazy-Birds`
- 当前前端版本：`v1.5.10.250430`
- 规则哈希：`sha256:60crazybirds-v1510250430-ways4096`
- 能力来源：[game-capabilities.json](game-capabilities.json)
- 行为交接：[protocol-handoff.json](protocol-handoff.json)

规则只来自当前游戏赔表、帮助、`GameGlobalConfig`/`GameDataCommon` 和 abc225 原站抓包。

## 1. 入口与请求顺序

1. 大厅启动 `/60/?ai&btt&gid=60&l&language&sip=api.omgapibra.com&t`
2. `POST /cp/api/v1/auth/verify`（表单 `ai,btt,t,gid`）
3. `POST /cp/api/v1/crazy-birds/config`（表单 `t,gid`）
4. `POST /cp/api/v1/crazy-birds/spin`（表单 `bl,bs,t,gid`）
5. History：`log-list` 再 `log-view`

编码：`application/x-www-form-urlencoded`。成功封装 `{code:200,info:"ok",data:{...}}`。`web-token`/`game-id` 写入表单 `t`/`gid`。

## 2. Config

| 字段 | 当前值 |
|---|---|
| auto | `[10,30,50,100,500]` |
| bll | `1..10` |
| bsl | `[1,5,50]` |
| dbs | `1` |
| dbl | `50`（不在 bll，前端回退索引 0） |
| cc/cs | `BRL` / `R$` |
| spl | 见下 |
| last | 最近一局快照，含 `rskl,ss,fsn,nfsc,wmkl,wskl,pxl` |

最低押注 `bl=1, bs=1`，`ba=1`。

## 3. 牌面与中奖

6 轴 × 4 行，`rskl` 长度 24，reel-major：`index=reel*4+row`。坐标 `reel*10+row`（如 `13`）。4096 ways。

符号：`9,10,J,Q,K,A,S5,S4,S3,S2,S1,WILD,WILDX2,WILDX3,WILDX5,SC`。

左到右相邻轴 ways：S1–S5 至少 2 轴，低分符号至少 3 轴。ways = 各轴命中格数之积。WILD 系列替代普通符号。赔付：

`spl[symbol][reelCount] * ba * ways * Π(pxl on cells)`

`pxl` 为坐标到倍率的对象，来自 `WILDX2/3/5`。

Scatter：至少 3 个不同轴出现 `SC` 触发免费，当前样本 `fsn=8`。免费 Step 的 `gt=2`、`small_game_type=2`。`ss=0` 表示同 Round 还有后续 Step；`ss=1` 且 `fsn==nfsc` 或 `fsn=0` 为终局。

## 4. Spin 字段

`ba,fsn,gt,nfsc,pb,pxl,rskl,rwa,small_game_type,ss,wa,wmkl,wskl`

`wmkl` 是 ways 分组数组，`wskl[i]` 是对应符号。

## 5. Redis Demo

Demo 只从 `192.168.10.3:6379/15` 领取预生成完整局 ASCII member（前缀 `CB60A1`）。空池返回 503。
