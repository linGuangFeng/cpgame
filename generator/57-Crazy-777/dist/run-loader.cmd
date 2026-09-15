@echo off
setlocal EnableExtensions DisableDelayedExpansion
set "SCRIPT_DIR=%~dp0"
set "LOADER_JAR=%SCRIPT_DIR%crazy777-loader.jar"
set "LOADER_CONFIG=%SCRIPT_DIR%generator.properties"
set "NO_PAUSE="
if /I "%~1"=="--no-pause" set "NO_PAUSE=1"

if not exist "%LOADER_JAR%" (
  echo [ERROR] Loader JAR not found: %LOADER_JAR%
  goto :failed
)
if not exist "%LOADER_CONFIG%" (
  echo [ERROR] Configuration not found: %LOADER_CONFIG%
  goto :failed
)
where java >nul 2>nul
if errorlevel 1 (
  echo [ERROR] java.exe was not found in PATH. Java 17 or later is required.
  goto :failed
)

echo Starting Crazy 777 Redis round loader...
call java -Dfile.encoding=UTF-8 -jar "%LOADER_JAR%" "%LOADER_CONFIG%"
set "LOADER_EXIT=%ERRORLEVEL%"
if not "%LOADER_EXIT%"=="0" goto :failed
echo [OK] Generation and Redis writes completed.
if not defined NO_PAUSE pause
exit /b 0

:failed
echo [ERROR] Loader did not complete; Redis success will not be reported.
if not defined NO_PAUSE pause
exit /b 1
