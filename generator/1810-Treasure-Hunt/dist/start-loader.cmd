@echo off
setlocal
set "HERE=%~dp0"
java -jar "%HERE%treasurehunt-loader.jar" "%HERE%generator.properties"
set "RC=%ERRORLEVEL%"
if /I not "%~1"=="--no-pause" pause
exit /b %RC%
