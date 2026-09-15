@echo off
setlocal
chcp 65001 >nul
set "SCRIPT_DIR=%~dp0"
set "PID_FILE=%SCRIPT_DIR%demo.pid"
set "DEMO_URL_FILE=%SCRIPT_DIR%demo-url.txt"
set "NO_PAUSE=0"
if /I "%~1"=="--no-pause" set "NO_PAUSE=1"
rem PowerShell 入口按 demo.pid 校验 PID/JAR 归属，等待 HTTP 后执行 Init、Config、minimum Spin，并读取 demo-url.txt。
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%restart-demo.ps1" -PidFile "%PID_FILE%" -DemoUrlFile "%DEMO_URL_FILE%" -NoPause
set "EXIT_CODE=%ERRORLEVEL%"
if "%NO_PAUSE%"=="0" pause
exit /b %EXIT_CODE%
