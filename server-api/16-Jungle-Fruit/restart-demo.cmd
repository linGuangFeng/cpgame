@echo off
setlocal EnableExtensions
chcp 65001 >nul
set "SCRIPT_DIR=%~dp0"
set "NO_PAUSE=0"
for %%A in (%*) do if /I "%%~A"=="--no-pause" set "NO_PAUSE=1"
if not exist "%SCRIPT_DIR%restart-demo.ps1" (
  echo [失败] 未找到 PowerShell 启动脚本：%SCRIPT_DIR%restart-demo.ps1
  set "EXIT_CODE=2"
  goto :finish
)
set "PS_ARGS="
if "%NO_PAUSE%"=="1" set "PS_ARGS=-NoPause"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%restart-demo.ps1" %PS_ARGS%
set "EXIT_CODE=%ERRORLEVEL%"
if "%EXIT_CODE%"=="0" (
  echo [成功] Jungle Fruit raw gid 16 已重启，Init / Config / Spin 自检通过。
) else (
  echo [失败] Jungle Fruit 重启或自检失败，退出码 %EXIT_CODE%。
)
:finish
if "%NO_PAUSE%"=="0" pause
exit /b %EXIT_CODE%
