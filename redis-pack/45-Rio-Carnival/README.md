# 45 Rio Carnival — Redis 处置说明

状态：`READY / CONTRACT_V3`。

Redis 固定为 `192.168.10.3:6379` DB15。普通完整局的倍率索引/列表为 `Rio45:v3:normal:ratios` 与 `Rio45:v3:normal:<ratio>`；特殊完整局为 `Rio45:v3:special:ratios` 与 `Rio45:v3:special:<ratio>`。`ratio` 是 `总派彩/总投注×100` 的整数，0 表示未中奖。

每个 LIST member 是 `R45C3|初始免费次数|免费倍率|牌面十六进制串` 的极简 ASCII，一个 member 含同一 Round 的全部有序牌面。Loader 用 `MULTI/EXEC` 内的 `ZADD + RPUSH + LTRIM` 写入；Controller 用 `LPOP` 原子领取且池空即失败，运行时不生成、不回放 fixture。

规则核心、Loader、Controller 共用相同 rulesHash：`sha256:515ffa3a4f803e36d44a1a50da1ce79b4c1df2e75a79893a18cd404aecc0565c`。

Controller 首先选择输赢。WIN 只进入现存的正整数倍率桶；LOSS 可领取 normal:0 或 special:0，免费 0 倍不会进入 WIN 分支。选中的桶被并发取空时直接返回 POOL_EMPTY，不换桶、不补生成。最新正式装载为 2000 普通输、2000 普通赢和 150 免费完整回合（其中 3 个免费 0 倍）。验收与产物哈希见 reports/45-Rio-Carnival/acceptance-final-v3.json。
