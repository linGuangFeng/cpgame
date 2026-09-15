@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
java -jar "%SCRIPT_DIR%christmas-gift-loader.jar" --config "%SCRIPT_DIR%generator.properties" %*
set "EXIT_CODE=%ERRORLEVEL%"
echo.
echo Christmas Gift generator finished with exit code %EXIT_CODE%.
if /I not "%~1"=="--no-pause" pause
exit /b %EXIT_CODE%
