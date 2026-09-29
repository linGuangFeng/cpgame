---
name: sync-ai-rules
description: 同步全部模型规则
---

# 同步全部模型规则

修改 ai/source 后执行 `python3 ai/sync-ai-rules.py`，再执行 `python3 ai/sync-ai-rules.py --check`。不要编辑生成物。同步不会修改游戏代码、properties或Redis。
