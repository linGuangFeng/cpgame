@echo off
setlocal EnableExtensions EnableDelayedExpansion
chcp 65001 >nul
set "SCRIPT_DIR=%~dp0"
set "NO_PAUSE=0"
set "VERIFY_ARG="
set "EXIT_CODE=1"
for %%A in (%*) do (
  if /I "%%~A"=="--no-pause" set "NO_PAUSE=1"
  if /I "%%~A"=="--verify" set "VERIFY_ARG=--verify"
)
if not exist "%SCRIPT_DIR%cyber-go-loader.jar" (
  echo [失败] 未找到同目录正式Loader JAR：%SCRIPT_DIR%cyber-go-loader.jar
  goto :finish
)
if not exist "%SCRIPT_DIR%generator.properties" (
  echo [失败] 未找到同目录正式配置：%SCRIPT_DIR%generator.properties
  goto :finish
)
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "%SCRIPT_DIR%cyber-go-loader.jar" "%SCRIPT_DIR%generator.properties" !VERIFY_ARG!
set "EXIT_CODE=!ERRORLEVEL!"
if "!EXIT_CODE!"=="0" (
  if defined VERIFY_ARG (echo [成功] Cyber GO正式生成链验证通过。) else (echo [成功] Cyber GO完整Round已直接写入Redis。)
) else (
  echo [失败] Loader退出码：!EXIT_CODE!。请检查Java、Redis连接和generator.properties。
)
:finish
if "%NO_PAUSE%"=="0" pause
exit /b %EXIT_CODE%
