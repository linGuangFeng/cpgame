@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
set "LOADER_JAR=%SCRIPT_DIR%blessing-ice-fire-loader.jar"
set "LOADER_CONFIG=%SCRIPT_DIR%generator.properties"
if not exist "%LOADER_JAR%" (
  echo [ERROR] JAR not found: %LOADER_JAR%
  goto :failed
)
where java >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Java was not found in PATH.
  goto :failed
)
echo Blessing of Ice and Fire realtime catalog
java -jar "%LOADER_JAR%" "%LOADER_CONFIG%"
if errorlevel 1 goto :failed
echo [OK]
if /I not "%~1"=="--no-pause" pause
exit /b 0
:failed
if /I not "%~1"=="--no-pause" pause
exit /b 1
