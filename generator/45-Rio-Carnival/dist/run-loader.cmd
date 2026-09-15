@echo off
setlocal
chcp 65001 >nul
set "SCRIPT_DIR=%~dp0"
set "NO_PAUSE=0"
if /I "%~1"=="--no-pause" set "NO_PAUSE=1"
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "%SCRIPT_DIR%rio-carnival-loader.jar" "%SCRIPT_DIR%generator.properties"
set "EXIT_CODE=%ERRORLEVEL%"
if "%EXIT_CODE%"=="0" (
  echo [成功] Rio Carnival 完整 Round 已写入 Redis。
) else (
  echo [失败] Loader 退出码：%EXIT_CODE%。请检查 Java 和配置文件。
)
if "%NO_PAUSE%"=="0" pause
exit /b %EXIT_CODE%
