@echo off
setlocal
chcp 65001 >nul
set "DIST_DIR=%~dp0"
set "LOADER_JAR=%DIST_DIR%club-goddess-redis-loader.jar"
set "LOADER_CONFIG=%DIST_DIR%generator.properties"
set "NO_PAUSE=0"
if /I "%~1"=="--no-pause" set "NO_PAUSE=1"

if not exist "%LOADER_JAR%" (
  echo [失败] 找不到 Loader JAR：%LOADER_JAR%
  set "EXIT_CODE=2"
  goto :finish
)
if not exist "%LOADER_CONFIG%" (
  echo [失败] 找不到配置：%LOADER_CONFIG%
  set "EXIT_CODE=3"
  goto :finish
)

java -jar "%LOADER_JAR%" "%LOADER_CONFIG%"
set "EXIT_CODE=%ERRORLEVEL%"
if "%EXIT_CODE%"=="0" (
  echo [成功] 完整 Round 已写入 Redis。
) else (
  echo [失败] Loader 退出码：%EXIT_CODE%
)

:finish
if "%NO_PAUSE%"=="0" pause
endlocal & exit /b %EXIT_CODE%
