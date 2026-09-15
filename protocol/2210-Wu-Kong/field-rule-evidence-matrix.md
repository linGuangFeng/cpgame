# 字段/规则—来源—代码判定—样本印证

| 项目 | 原始来源 | 独立判定 | 1,122 局印证 |
|---|---|---|---|
| Round 边界 | gameResult 抓包、前端事件 | 一次付费请求是一完整局；内部动画到 `Game_All_Anim_End` | 1,122 个唯一 oid/round_id，stepCount 全 1 |
| 普通结果 | chess.normal/result | 去 null 后有序拼接 | totalWin=0 共 919，正数共 203 |
| X2/X5 | extend 与前端分支 | base×2 / base×5 | 83 / 97 局逐局相符，包含合法 0 倍特殊状态 |
| RESPIN | extend/respin 与前端状态机 | 第二组两位整体重转，两个 result 相加 | 33 局；无第二 HTTP 请求 |
| 互斥状态 | extend、respin 是否数组 | NONE/X2/X5/RESPIN 四分区；非法组合拒绝 | 1,122 局完整落入唯一分区 |
| 位置域/上限 | 相邻原始状态与全批次 | 初始/重转均恰 2 位；每局最多一个 extend token | 全批次未越界，bonus 恒 0 |
| 结算 | start/end/change/bet/total_win | `end=start+change`, `change=win-bet` | 0 个断裂 |
| History | 三类 History 抓包、前端入口 | recent→日汇总→日详情→返回 | 列表与详情字段可追溯 |

原始批次 SHA-256 `f6e64730a2de33b4d605b3aa0d781e76e911536271dc307c367302b4bfaa0d27`；原前端 SHA-256 `a497b4918fb6657fb269022beea4f5af9e3b877bbe5e0f99c0dc260d4dee87f0`。
