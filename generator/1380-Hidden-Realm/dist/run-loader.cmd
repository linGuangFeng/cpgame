@echo off
setlocal
cd /d "%~dp0"
java -jar hidden-realm-loader.jar generator.properties
set "RESULT=%ERRORLEVEL%"
echo.
if "%RESULT%"=="0" (echo Hidden Realm Redis complete-round pool loaded.) else (echo Loader failed with exit code %RESULT%.)
if /i not "%~1"=="--no-pause" pause
exit /b %RESULT%
