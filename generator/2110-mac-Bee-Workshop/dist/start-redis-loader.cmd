@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
set "LOADER_JAR=%SCRIPT_DIR%bee-workshop-redis-loader.jar"
set "LOADER_CONFIG=%SCRIPT_DIR%generator.properties"
set "BEE_JAVA=java"
if defined JAVA_HOME set "BEE_JAVA=%JAVA_HOME%\bin\java.exe"
if not exist "%LOADER_JAR%" (
  echo [ERROR] JAR not found: %LOADER_JAR%
  exit /b 1
)
"%BEE_JAVA%" -jar "%LOADER_JAR%" "%LOADER_CONFIG%" %*
set "BEE_EXIT=%ERRORLEVEL%"
set "BEE_PAUSE=1"
for %%A in (%*) do if /I "%%~A"=="--no-pause" set "BEE_PAUSE=0"
if "%BEE_PAUSE%"=="1" if not "%BEE_EXIT%"=="0" pause
if "%BEE_PAUSE%"=="1" if "%BEE_EXIT%"=="0" pause
exit /b %BEE_EXIT%
