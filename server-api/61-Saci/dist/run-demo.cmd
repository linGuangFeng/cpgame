@echo off
setlocal EnableExtensions
chcp 65001 >nul
set "DIST_DIR=%~dp0"

set "PAUSE_ON_EXIT=1"
if /I "%~1"=="--no-pause" (
  set "PAUSE_ON_EXIT=0"
  shift
)
if not "%~1"=="" (
  echo [失败] 不支持的参数：%~1 1>&2
  set "EXIT_CODE=2"
  goto finish
)

if not exist "%DIST_DIR%saci-server-api.jar" (
  echo [失败] 缺少服务端 JAR：%DIST_DIR%saci-server-api.jar 1>&2
  set "EXIT_CODE=1"
  goto finish
)
if not exist "%DIST_DIR%server.properties" (
  echo [失败] 缺少正式配置：%DIST_DIR%server.properties 1>&2
  set "EXIT_CODE=1"
  goto finish
)

echo 正在启动 Saci Java API；关闭本窗口可停止前台服务。
java -jar "%DIST_DIR%saci-server-api.jar" "%DIST_DIR%server.properties"
set "EXIT_CODE=%errorlevel%"
if "%EXIT_CODE%"=="0" (
  echo [成功] Saci Java API 已正常停止。
) else (
  echo [失败] Saci Java API 异常退出，退出码：%EXIT_CODE%。 1>&2
)

:finish
if "%PAUSE_ON_EXIT%"=="1" (
  echo 按任意键关闭窗口，自动化调用请使用 --no-pause。
  pause >nul
)
endlocal & exit /b %EXIT_CODE%
