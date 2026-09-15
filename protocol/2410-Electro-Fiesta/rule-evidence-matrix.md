# 字段/规则—来源—代码判定—样本印证

| 字段或规则 | 规则/配置来源 | 前端判定 | 真实样本印证 |
|---|---|---|---|
| 3×3、5 固定线 | GameConstant `ROW_NUM=3,COL_NUM=3,LINE=5` | `winLine2SymbolIdx` 五组位置 | 520 局全部 `res.ps.length=9`，`wa.l` 为 1..5 |
| 三连赔率 | GamePropType `2/3/5/10/20/50` | `wa.o × b × l` | 普通 50 牌四线：`4×50×0.8=160` |
| 付费/后续 | PlayerMgr `GameBetType.Normal=1,Free=2` | `gameResult` 请求的 `type` | 相邻抓包首步 1，`cf>0` 后续均 2 |
| 普通态 | 响应 `f=[]` | `f instanceof Array` 映射 NORMAL | 普通输 426、普通赢 11 |
| 重转态 | 响应 `f.t=1,pr,ps,p` | `getRespinCol/getRespinSb` | 71 个完整 Round；后续只替换 `pr` 列 |
| 粘性倍率态 | 响应 `f.t=2,pcn,pcp` | `getFreeMul/getFreeMulAdded` | 12 个完整 Round；新增位置累计且终步 `cf=0` |
| Round 终止 | `f.cf` | `isFreeEnd(): f.cf==0` | 索引器按相邻步骤重建 520 个完整 Round |
| Buy 关系 | 双遍控件遍历与 Button_Buy 源码 | 打开后无购买请求/确认动作 | 资源审计标记 `BUY_MENU_ENTRY` inert |

权重仅是当前训练样本估计，不代表原厂长期概率或 RTP。初始牌、重转列新牌、倍率材质分别统计，不相互套用。

