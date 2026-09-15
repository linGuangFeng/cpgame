@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
echo [Monster Slayer 2300] Generating, validating and committing complete Rounds in batches...
java -jar "%SCRIPT_DIR%monster-slayer-redis-loader.jar" "%SCRIPT_DIR%generator.properties"
set "EXIT_CODE=%ERRORLEVEL%"
if "%EXIT_CODE%"=="0" echo Complete: all requested batches were committed.
if not "%EXIT_CODE%"=="0" echo Failed: review the specific error above. Previously committed batches remain in Redis.
if /I not "%~1"=="--no-pause" pause
exit /b %EXIT_CODE%
