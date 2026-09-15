@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
set "LOADER_JAR=%SCRIPT_DIR%lucky-panda-redis-loader.jar"
set "LOADER_CONFIG=%SCRIPT_DIR%generator.properties"
set "PAUSE_ON_END=1"

if /I "%~1"=="--no-pause" (
  set "PAUSE_ON_END=0"
  shift
)

if not exist "%LOADER_JAR%" (
  echo [ERROR] JAR not found:
  echo %LOADER_JAR%
  goto :failed
)

if not exist "%LOADER_CONFIG%" (
  echo [ERROR] Config not found:
  echo %LOADER_CONFIG%
  goto :failed
)

where java >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Java was not found in PATH.
  echo Install Java or add java.exe to PATH, then retry.
  goto :failed
)

echo Starting Lucky Panda 41 Redis round generator...
echo Config: %LOADER_CONFIG%
echo Redis must already be listening at redis.host:redis.port in generator.properties
echo Demo cache is 192.168.10.3:6379 database 15. Connection refused is a network/config issue, not a rule bug.
echo.

java -jar "%LOADER_JAR%" "%LOADER_CONFIG%"
if errorlevel 1 goto :failed

echo.
echo [OK] Generation and Redis loading completed.
if "%PAUSE_ON_END%"=="1" pause
exit /b 0

:failed
echo.
echo [FAILED] Redis loading did not complete.
if "%PAUSE_ON_END%"=="1" pause
exit /b 1
