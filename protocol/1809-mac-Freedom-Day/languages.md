# 1809 多语言资源验证

游戏配置中实际声明了 9 个资源语言：`bn-bd`、`en-us`、`es-es`、`fr-fr`、`id-id`、`ko-ko`、`pt-pt`、`th-th`、`tr-tr`。平台传入的 `pt-br` 会映射到游戏资源语言 `pt-pt`，因此交付中同时保留 `pt-br` 入口目录和 `pt-pt` 原生资源目录。

## 目录规则

- 独立资源：`resources/1809-Freedom-Day/<language>/static.cpgame.io/fixed/`
- 本地联合运行资源：`resources/1809-Freedom-Day/all-languages/static.cpgame.io/fixed/`
- 页面快照与启动地址：`pages/1809-Freedom-Day/<language>/`
- 截图证据：`evidence/1809-Freedom-Day/languages/`

`all-languages` 是本地服务专用的并集目录，不代表额外语言；各语言独立目录仍保留，方便以后批量处理和差异比对。

最终清点：`all-languages` 为 193 文件、39,953,731 字节；`en-us` 为 185 文件，其余入口语言各 186 文件。`pt-br` 已包含其映射目标 `pt-pt` 的图集，因此也能作为独立资源目录运行。差异来自每个非英语语言各自的一张本地化合成图集；所有目录都已包含新增的 5 张帮助图片。

## 浏览器验证结果

| 入口语言 | 实际资源语言 | 页面验证 | 独有语言图集（相对 `assets/Game2260/`） |
|---|---|---|---|
| `pt-br` | `pt-pt` | 已验证葡语入口 | 使用 `pt-pt` 图集 |
| `bn-bd` | `bn-bd` | 已验证孟加拉语界面、Spin、历史、Feature Buy | `native/11/1184a1165.95051.png` |
| `en-us` | `en-us` | 已验证英语界面、Spin | 英语图集已包含在基础资源中 |
| `es-es` | `es-es` | 已验证西班牙语界面 | `native/11/11f929c42.2ff84.png` |
| `fr-fr` | `fr-fr` | 已验证法语界面 | `native/1b/1be8b53f2.7871a.png` |
| `id-id` | `id-id` | 已验证印尼语界面 | `native/1e/1ebfc5313.f58b1.png` |
| `ko-ko` | `ko-ko` | 已验证韩语界面 | `native/1b/1bfa5626b.79160.png` |
| `pt-pt` | `pt-pt` | 已验证葡语界面 | `native/1d/1da740020.03569.png` |
| `th-th` | `th-th` | 已验证泰语界面 | `native/11/118d0329a.3000f.png` |
| `tr-tr` | `tr-tr` | 已验证土耳其语界面 | `native/1e/1e4caca54.626a9.png` |

## 隐藏资源结论

- 需要触发一次普通 Spin：会拉取游戏音效和旋转阶段资源。
- 需要打开一次历史：会验证历史窗口与详情依赖。
- 需要触发 Feature Buy、Free Spin、Big Win：会覆盖购买确认、Scatter、免费旋转与大奖动画等共享资源。
- 需要完整滚动帮助/Paytable：本轮额外发现并补齐了 5 张帮助图片；只进主界面、Spin 和历史仍会漏资源。
- 不需要对每个语言重复跑 500 局：模式资源是共享路径，已从完成模式验证的基础集合复制；每种语言只需实际进入一次主界面以命中该语言图集。`bn-bd` 另外重复验证了 Spin、历史和 Feature Buy，确认语言切换后功能没有断裂。

所有 Spin 与特殊模式验证均使用本地确定性模拟数据，没有调用线上 `/gameResult`，也没有消耗平台账号余额。

唯一非关键请求错误是 `/favicon.ico` 不存在，不影响初始化、Spin、历史或特殊模式。
