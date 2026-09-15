@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
set "LOADER_JAR=%SCRIPT_DIR%freedom-day-redis-loader.jar"
set "LOADER_CONFIG=%SCRIPT_DIR%generator.properties"

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

echo Starting Freedom Day Redis round generator...
echo Config: %LOADER_CONFIG%
echo Redis must already be listening at redis.host:redis.port in generator.properties
echo Default is 127.0.0.1:6379. Connection refused means Redis is not running.
echo.
java -jar "%LOADER_JAR%" "%LOADER_CONFIG%"
if errorlevel 1 goto :failed

echo.
echo [OK] Generation and Redis loading completed.
pause
exit /b 0

:failed
echo.
echo [FAILED] Redis loading did not complete.
pause
exit /b 1
