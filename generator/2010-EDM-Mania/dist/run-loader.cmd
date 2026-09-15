@echo off
setlocal
cd /d "%~dp0"
set "LOADER_JAR=%~dp0edm-mania-redis-loader.jar"
set "LOADER_CONFIG=%~dp0generator.properties"
if not exist "%LOADER_JAR%" (
  echo [ERROR] JAR not found: %LOADER_JAR%
  if /i not "%~1"=="--no-pause" pause
  exit /b 1
)
if not exist "%LOADER_CONFIG%" (
  echo [ERROR] Config not found: %LOADER_CONFIG%
  if /i not "%~1"=="--no-pause" pause
  exit /b 1
)
where java >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Java was not found in PATH.
  if /i not "%~1"=="--no-pause" pause
  exit /b 1
)
echo Starting EDM Mania Redis complete-round loader...
java -jar "%LOADER_JAR%" "%LOADER_CONFIG%"
set "RESULT=%ERRORLEVEL%"
echo.
if "%RESULT%"=="0" (echo [OK] Redis load completed.) else (echo [FAILED] exit code %RESULT%.)
if /i not "%~1"=="--no-pause" pause
exit /b %RESULT%
