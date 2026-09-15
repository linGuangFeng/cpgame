@echo off
chcp 65001 >nul
setlocal EnableExtensions EnableDelayedExpansion
set "SCRIPT_DIR=%~dp0"
set "PID_FILE=%SCRIPT_DIR%dist\demo-server.pid"
set "DEMO_URL_FILE=%SCRIPT_DIR%demo-url.txt"
set "NO_PAUSE=0"
if /I "%~1"=="--no-pause" set "NO_PAUSE=1"
if not "%~2"=="" (
  echo [失败] 仅支持 --no-pause 参数。 1>&2
  set "EXIT_CODE=2"
  goto finish
)

where pwsh.exe >nul 2>nul
if "%ERRORLEVEL%"=="0" (
  set "POWERSHELL_EXE=pwsh.exe"
) else (
  set "POWERSHELL_EXE=powershell.exe"
)

if not exist "%SCRIPT_DIR%restart-demo.ps1" (
  echo [失败] 缺少同目录 restart-demo.ps1。 1>&2
  set "EXIT_CODE=1"
  goto finish
)
if not exist "%DEMO_URL_FILE%" (
  echo [失败] 缺少 demo-url.txt。 1>&2
  set "EXIT_CODE=1"
  goto finish
)
set "DEMO_URL="
set /p "DEMO_URL="<"%DEMO_URL_FILE%"
if /I not "!DEMO_URL!"=="http://localhost:19557/launch" (
  echo [失败] demo-url.txt 必须只保存当前游戏的本机 HTTP 页面地址。 1>&2
  set "EXIT_CODE=1"
  goto finish
)

echo [Crazy 777] 正在按 PID 安全重启 Java Demo，并执行 HTTP 就绪、Init、Config、最小 Spin 校验...

rem PID 文件存在时，CMD 先要求 PowerShell 校验 PID 与当前游戏 JAR、主类、配置的精确归属。
if exist "%PID_FILE%" (
  set "EXPECTED_PID="
  set /p "EXPECTED_PID="<"%PID_FILE%"
  echo(!EXPECTED_PID!| findstr /R /X "[1-9][0-9]*" >nul
  if errorlevel 1 (
    echo [失败] PID 文件不是有效正整数：%PID_FILE% 1>&2
    set "EXIT_CODE=1"
    goto finish
  )
  "!POWERSHELL_EXE!" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%restart-demo.ps1" -ValidatePidOnly -ExpectedPid !EXPECTED_PID!
  if errorlevel 1 (
    echo [失败] PID !EXPECTED_PID! 不属于当前 Crazy 777 Java Demo，已安全拒绝停止。 1>&2
    set "EXIT_CODE=1"
    goto finish
  )
)

"!POWERSHELL_EXE!" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%restart-demo.ps1" -NoPause
set "EXIT_CODE=!ERRORLEVEL!"
if not "!EXIT_CODE!"=="0" goto finish

set "ACTIVE_PID="
set /p "ACTIVE_PID="<"%PID_FILE%"
echo(!ACTIVE_PID!| findstr /R /X "[1-9][0-9]*" >nul
if errorlevel 1 (
  echo [失败] 重启后 PID 文件无效。 1>&2
  set "EXIT_CODE=1"
  goto finish
)
"!POWERSHELL_EXE!" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%restart-demo.ps1" -ValidatePidOnly -ExpectedPid !ACTIVE_PID!
if errorlevel 1 (
  echo [失败] 重启后的 PID 归属复核失败。 1>&2
  set "EXIT_CODE=1"
  goto finish
)

echo [成功] PID=!ACTIVE_PID!；HTTP_READY=PASS；INIT=PASS；CONFIG=PASS；MIN_SPIN=PASS
echo [成功] 试玩页面地址（读取自 demo-url.txt）：
type "%DEMO_URL_FILE%"

:finish
if not defined EXIT_CODE set "EXIT_CODE=0"
if not "%EXIT_CODE%"=="0" echo [Crazy 777] 一键重启或冒烟校验失败，退出码 %EXIT_CODE%。 1>&2
if "%NO_PAUSE%"=="0" (
  echo.
  echo 按任意键关闭窗口；试玩服务将继续在后台运行...
  pause >nul
)
endlocal & exit /b %EXIT_CODE%