@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
set "LOADER_JAR="
for /f "delims=" %%F in ('dir /b /a-d /o-d "%SCRIPT_DIR%crazygems-redis-loader*.jar"') do if not defined LOADER_JAR set "LOADER_JAR=%SCRIPT_DIR%%%F"
set "LOADER_CONFIG=%SCRIPT_DIR%generator.properties"
if not exist "%LOADER_JAR%" (
  echo [ERROR] JAR not found: %LOADER_JAR%
  goto :failed
)
if not exist "%LOADER_CONFIG%" (
  echo [ERROR] Config not found: %LOADER_CONFIG%
  goto :failed
)
where java >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Java was not found in PATH.
  goto :failed
)
echo Starting Crazy Gems Redis round generator...
java -jar "%LOADER_JAR%" "%LOADER_CONFIG%"
if errorlevel 1 goto :failed
echo [OK] Generation and Redis loading completed.
pause
exit /b 0
:failed
echo [FAILED] Redis loading did not complete.
pause
exit /b 1
