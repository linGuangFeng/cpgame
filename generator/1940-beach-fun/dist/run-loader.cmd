@echo off
setlocal
cd /d "%~dp0"
set "NO_PAUSE=0"
if /I "%~1"=="--no-pause" set "NO_PAUSE=1"
echo Starting Beach Fun Java complete-Round generator...
java -jar redis-loader.jar --config generator.properties --no-pause
set "RC=%ERRORLEVEL%"
if "%RC%"=="0" (echo Generation and independent validation complete.) else (echo Loader failed with exit code %RC%.)
if "%NO_PAUSE%"=="0" pause
exit /b %RC%
