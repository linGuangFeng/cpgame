@echo off
setlocal
chcp 65001 >nul
set "SCRIPT_DIR=%~dp0"
set "NO_PAUSE="
if /I "%~1"=="--no-pause" set "NO_PAUSE=-NoPause"
if defined CPGAME_PLATFORM_BASE_URL (
  powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%restart-demo.ps1" -PlatformBaseUrl "%CPGAME_PLATFORM_BASE_URL%" %NO_PAUSE%
) else (
  powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%restart-demo.ps1" %NO_PAUSE%
)
set "EXIT_CODE=%ERRORLEVEL%"
if not "%EXIT_CODE%"=="0" echo [失败] 当前游戏的受管 Java Demo 未通过安全重启和最小接口闭环，退出码：%EXIT_CODE%
exit /b %EXIT_CODE%
