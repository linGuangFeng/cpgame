@echo off
setlocal
chcp 65001 >nul
set "SCRIPT_DIR=%~dp0"
set "NO_PAUSE="
if /I "%~1"=="--no-pause" (
  set "NO_PAUSE=1"
  shift
)
if not "%~1"=="" (
  echo [失败] 不支持的参数：%~1
  set "EXIT_CODE=2"
  goto :finish
)
echo [信息] 正在按 PID 校验并安全重启当前游戏 Java Demo...
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%restart-demo.ps1"
set "EXIT_CODE=%ERRORLEVEL%"
if not "%EXIT_CODE%"=="0" goto :failed
set /p "DEMO_URL="<"%SCRIPT_DIR%demo-url.txt"
echo [成功] HTTP 就绪，Config、Init 和最小 Spin 均已通过。
echo [试玩地址] %DEMO_URL%
goto :finish
:failed
echo [失败] Curupira 试玩重启或接口验收失败，退出码：%EXIT_CODE%
:finish
if not defined NO_PAUSE pause
endlocal & exit /b %EXIT_CODE%
