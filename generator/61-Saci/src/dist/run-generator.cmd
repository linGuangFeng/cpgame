@echo off
chcp 65001 >nul
setlocal EnableExtensions
set "DIST_DIR=%~dp0"
set "NO_PAUSE=0"
set "EXIT_CODE=0"

if /I "%~1"=="--no-pause" (
  set "NO_PAUSE=1"
  shift
)
if not "%~1"=="" (
  echo [失败] 仅支持可选参数 --no-pause
  set "EXIT_CODE=2"
  goto finish
)
if not exist "%DIST_DIR%saci-redis-loader.jar" (
  echo [失败] 未找到 %DIST_DIR%saci-redis-loader.jar
  set "EXIT_CODE=1"
  goto finish
)
if not exist "%DIST_DIR%generator.properties" (
  echo [失败] 未找到 %DIST_DIR%generator.properties
  set "EXIT_CODE=1"
  goto finish
)

java -jar "%DIST_DIR%saci-redis-loader.jar" "%DIST_DIR%generator.properties"
set "EXIT_CODE=%ERRORLEVEL%"
if "%EXIT_CODE%"=="0" (echo [成功] Saci Redis Loader 执行完成。) else (echo [失败] Loader 退出码：%EXIT_CODE%)

:finish
if "%NO_PAUSE%"=="0" pause
endlocal & exit /b %EXIT_CODE%
