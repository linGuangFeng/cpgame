@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
set "LOADER_JAR=%SCRIPT_DIR%bee-workshop-redis-loader.jar"
set "LOADER_CONFIG=%SCRIPT_DIR%generator.properties"
set "BEE_JAVA=java"
if defined JAVA_HOME set "BEE_JAVA=%JAVA_HOME%\bin\java.exe"

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

echo Starting Bee Workshop Redis round generator...
echo Config: %LOADER_CONFIG%
echo Redis must already be listening at redis.host:redis.port in generator.properties
echo Default is 192.168.10.3:6379 DB15.
echo.
"%BEE_JAVA%" -jar "%LOADER_JAR%" "%LOADER_CONFIG%" %*
set "BEE_EXIT=%ERRORLEVEL%"
if not "%BEE_EXIT%"=="0" goto :failed

echo.
echo [OK] Generation and Redis loading completed.
set "BEE_PAUSE=1"
for %%A in (%*) do if /I "%%~A"=="--no-pause" set "BEE_PAUSE=0"
if "%BEE_PAUSE%"=="1" pause
exit /b 0

:failed
echo.
echo [FAILED] Redis loading did not complete.
set "BEE_PAUSE=1"
for %%A in (%*) do if /I "%%~A"=="--no-pause" set "BEE_PAUSE=0"
if "%BEE_PAUSE%"=="1" pause
exit /b 1
