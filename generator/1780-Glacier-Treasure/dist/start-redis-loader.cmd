@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
set "LOADER_JAR=%SCRIPT_DIR%glacier-treasure-loader.jar"
set "LOADER_CONFIG=%SCRIPT_DIR%generator.properties"
set "NO_PAUSE=0"
if /I "%~1"=="--no-pause" set "NO_PAUSE=1"

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
  goto :failed
)

echo Starting Glacier Treasure Redis round generator...
echo Config: %LOADER_CONFIG%
java -jar "%LOADER_JAR%" "%LOADER_CONFIG%"
if errorlevel 1 goto :failed
echo.
echo [OK] Generation and Redis loading completed.
if "%NO_PAUSE%"=="0" pause
exit /b 0

:failed
echo.
echo [FAILED] Redis loading did not complete.
if "%NO_PAUSE%"=="0" pause
exit /b 1
