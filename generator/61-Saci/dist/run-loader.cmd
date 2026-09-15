@echo off
setlocal EnableExtensions DisableDelayedExpansion
set "SCRIPT_DIR=%~dp0"
set "LOADER_JAR=%SCRIPT_DIR%saci-loader.jar"
set "LOADER_CONFIG=%SCRIPT_DIR%generator.properties"
set "NO_PAUSE="
if /I "%~1"=="--no-pause" set "NO_PAUSE=1"

if not exist "%LOADER_JAR%" (
  echo [ERROR] 找不到 Loader JAR: %LOADER_JAR%
  goto :failed
)
if not exist "%LOADER_CONFIG%" (
  echo [ERROR] 找不到配置: %LOADER_CONFIG%
  goto :failed
)
where java >nul 2>nul
if errorlevel 1 (
  echo [ERROR] PATH 中没有 java.exe，需要 Java 17 或更高版本。
  goto :failed
)

echo 正在启动 Saci Redis 完整局 Loader...
call java -Dfile.encoding=UTF-8 -jar "%LOADER_JAR%" "%LOADER_CONFIG%"
set "LOADER_EXIT=%ERRORLEVEL%"
if not "%LOADER_EXIT%"=="0" goto :failed
echo [OK] 生成并写入 Redis 完成。
if not defined NO_PAUSE pause
exit /b 0

:failed
echo [ERROR] Loader 未完成；不会报告 Redis 写入成功。
if not defined NO_PAUSE pause
exit /b 1
