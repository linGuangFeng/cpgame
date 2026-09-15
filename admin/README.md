# CPGame admin

独立游戏库：扫 `D:\work\hd\cpgame` 的 publish / generator / reports，提供试玩启动和 `runRedis`。

默认 http://127.0.0.1:8000

agent-ai 里的游戏库先保留，确认这边没问题后再删。

Windows：

```bat
package-restart.cmd
restart.cmd
```

macOS：

```sh
sh package-restart.sh
sh restart.sh
```

`package-restart` 会先打包，再部署并重启；`restart` 会选磁盘上最新的 admin jar 启动，不会继续用被锁住的旧 `var\cpgame-admin.jar`。
实际运行文件在 `var\run\cpgame-admin-时间戳.jar`。旧入口 `start.cmd` 仍然可用，等同于 `package-restart.cmd`。进程 PID 和日志保存在 `var` 下。

环境变量可选：`CPGAME_ARTIFACT_ROOT`、`CPGAME_ADMIN_PORT`、`CPGAME_ADMIN_ADDRESS`、`CPGAME_ADMIN_RUNTIME`。
