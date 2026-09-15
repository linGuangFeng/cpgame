@echo off
chcp 65001 >nul
setlocal EnableExtensions
set "SCRIPT_DIR=%~dp0"
set "LOADER_JAR=%SCRIPT_DIR%lucky-wheel-redis-loader.jar"
set "GENERATOR_CONFIG=%SCRIPT_DIR%generator.properties"
set "NO_PAUSE=0"
if /I "%~1"=="--no-pause" set "NO_PAUSE=1"

if not exist "%LOADER_JAR%" (
  echo [失败] 找不到 Loader JAR：%LOADER_JAR%
  set "EXIT_CODE=2"
  goto :FINISH
)
if not exist "%GENERATOR_CONFIG%" (
  echo [失败] 找不到正式配置：%GENERATOR_CONFIG%
  set "EXIT_CODE=2"
  goto :FINISH
)

java -jar "%LOADER_JAR%" "%GENERATOR_CONFIG%"
set "EXIT_CODE=%ERRORLEVEL%"
if "%EXIT_CODE%"=="0" (
  echo [完成] Lucky Wheel Redis 结果已按实际倍率原子写入并裁剪。
) else (
  echo [失败] Loader 退出码：%EXIT_CODE%
)

:FINISH
if not defined EXIT_CODE set "EXIT_CODE=2"
if "%NO_PAUSE%"=="0" pause
exit /b %EXIT_CODE%