# 58 Crazy Gems：一局怎么结束

证据：`captures/58-Crazy-Gems/rules-text-pt.json`、`paytable-text-pt.json`、`history-details.jsonl`（1160 局）、`fixtures/58-Crazy-Gems/first-load/0001-post-config.json`、真实请求 `POST /cp/api/v1/crazy-gems/spin`（`bl,bs,t,gid`）。

## 一局的边界

这是 3 轴 × 3 行、5 条固定赔付线的单 Step 付费局。服务端一次 Spin 就是一整局。

- 请求：`POST /cp/api/v1/crazy-gems/spin`，表单 `bl,bs,t,gid=58`
- 成功字段：`ba,pb,rpx,rskl,wa,wmkl`（History detail 另有 `baf,bid,bl,bs,ca,gt`）
- **终局条件**：响应里没有 truthy `spin_status`。前端 `onGameEnd` 只在 `!spin_status` 时收局。1160 局全部 `spin_status` 缺失，因此一次付费 Spin 即完整局，没有连消续页、没有免费 Step。
- 余额只在这一次终局改：`pb = 上一局 pb - ba + wa`

牌面 `rskl` 恒为 9 个符号，reel-major：`flatIndex = reel * 3 + row`。第 4 列只是矿车倍率展示，不是中奖轴。

5 条线（每轴行号）：

```
0: [0,0,0]  1: [1,1,1]  2: [2,2,2]  3: [0,1,2]  4: [2,1,0]
```

WILD 可替任意普通符号；三格全 WILD 按 WILD 赔。每条线只付最高（本游戏只有 3 连）。

```
wa = Σ spl[wmkl[line]] × bs × bl × rpx
```

`spl`：WILD=5, H1=4, H2=3, H3=2.4, H4=2, H5=1.6, H6=1, H7=0.4。

## 未中奖 / 中奖 / 特殊(mali) / 购买

| 结果 | 判定 | 抓包 |
|---|---|---|
| 普通未中奖 | `wa=0` 且 `wmkl={}` 且 `rpx=1` | 177 / 1160 |
| 普通中奖 | `wa>0` 且 `wmkl` 非空 且 `rpx=1` | 0 / 1160（1160 局里所有中奖都带矿车倍率） |
| 特殊 mali（已确认唯一特殊） | `rpx ∈ {2,3,5,10,15}`。矿车倍率是同一付费 Step 上的正交字段，不新开 Round/Step。未中奖也可以有 mali | 983 / 1160；其中未中奖 782、中奖 201 |
| 购买 | 无。规则、奖表、bundle、场景树都没有 Feature Buy 入口 | 0 |

`rpx=1` 仍会下发，表示矿车停在 1x，不是“没有矿车字段”。

免费：规则页无入口，1160 局无 `fsn/frwa/fbt`。不实现臆造免费。

## 样本覆盖（已有抓包，不再重拉）

付费起点 1160（上限 5000），普通未中奖 959（含 mali 未中奖）、普通中奖 201、mali 983。目标 200/200/30 已满。History 每种已确认结果保留列表+详情即可，不必为每局 spin 再补全量 his。
