@echo off
setlocal
chcp 65001 >nul
set "SCRIPT_DIR=%~dp0"
set "NO_PAUSE=0"
if /I "%~1"=="--no-pause" set "NO_PAUSE=1"
pushd "%SCRIPT_DIR%"
if not defined PORT (
  echo [ERROR] Set PORT to the platform-assigned value in 50000-59999.
  exit /b 64
)
java -jar "%SCRIPT_DIR%controller.jar" --port "%PORT%" --config "%SCRIPT_DIR%controller.properties" --publish "%SCRIPT_DIR%..\..\..\publish\45-Rio-Carnival"
set "EXIT_CODE=%ERRORLEVEL%"
popd
if "%EXIT_CODE%"=="0" (echo [成功] Rio Carnival Controller 已正常停止。) else (echo [失败] Controller 退出码：%EXIT_CODE%。请检查 Java、动态端口、Redis 和配置文件。)
if "%NO_PAUSE%"=="0" pause
exit /b %EXIT_CODE%
