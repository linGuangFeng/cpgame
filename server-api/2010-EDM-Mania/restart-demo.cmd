@echo off
setlocal
set "BASE=%~dp0"
set "JAR=%BASE%dist\game-2010-evidence-gated-server.jar"
set "NO_PAUSE=0"
if /I "%~1"=="--no-pause" set "NO_PAUSE=1"
echo [gid 2010] 当前仅启动证据门禁 Java API，不代表游戏已可试玩。
java --add-modules jdk.httpserver -Dserver.address=0.0.0.0 -Dserver.port=20110 -jar "%JAR%"
set "EXIT_CODE=%ERRORLEVEL%"
if "%NO_PAUSE%"=="0" pause
exit /b %EXIT_CODE%
