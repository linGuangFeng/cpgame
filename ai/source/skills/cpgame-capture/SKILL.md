---
name: cpgame-capture
description: >
  CPGame 原站抓包、补遗漏静态资源和采集完整局。
  Use when the user says 抓包, 补资源, 抓遗漏, 缺图, 缺音频, 静态 404,
  补漏, capture, fill missing assets, or runs /cpgame-capture.
---

业务硬约束以根 AGENTS.md、复刻要求.md、抓包要求.md 为准；本 skill 保留 CP 取证/发布操作，不覆盖新版采集完成标准或生成真实性要求。


# 抓包与补漏

长流程按仓库根目录 `抓包要求.md` 执行。本 skill 只补充默认厅账号，以及补漏后必须上传 S3。

## 默认厅账号

用户没指定账号时用：

- 大厅：https://hms-paddle.com/
- 用户：`abc221`
- 密码：`112233`

用户给了别的账号或大厅地址则用用户的，不混用。

## 执行顺序

1. 盘点该游戏已有 `resources`、`captures`、`fixtures`、`publish`、`protocol`、`screenshots`。有效内容复用，禁止清空重抓。
2. 按 `抓包要求.md` 补缺口：真实点击拿到 URL，再按观测到的 URL 下载；禁止猜测或拼接 URL。
3. 静态资源落到能对应原站路径的目录（通常 `resources/<ID>-<Name>/` 下按 `static.cpgame.io` 或 `/v2/<rid>/` 的相对路径）。
4. **补漏完成后立刻上传 S3**，按 `.grok/skills/cpgame-s3-upload/SKILL.md`。不要等用户再说「传到 S3」。
5. 完整局采集目标、3000 局上限、History 条数仍以 `抓包要求.md` 为准。采集的 JSON/fixtures 不上传 S3，只上传原站静态资源（以及用户明确要求的 publish 包）。
