@echo off
setlocal
cd /d "%~dp0"
java -Dfile.encoding=UTF-8 -jar jungle-party-generator-1.0.0.jar --config generator.properties %*
set "EXIT_CODE=%ERRORLEVEL%"
echo Loader exit code: %EXIT_CODE%
echo %* | findstr /I /C:"--no-pause" >nul
if errorlevel 1 pause
exit /b %EXIT_CODE%
