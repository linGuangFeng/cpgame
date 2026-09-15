@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0restart-demo.ps1" %*
if errorlevel 1 exit /b %errorlevel%
echo Treasure Hunt demo is ready.
if /I not "%~1"=="--no-pause" pause
