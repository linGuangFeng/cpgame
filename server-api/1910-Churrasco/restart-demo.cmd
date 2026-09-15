@echo off
setlocal
set "BASE=%~dp0"
set "NO_PAUSE=0"
if /I "%~1"=="--no-pause" set "NO_PAUSE=1"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%BASE%restart-demo.ps1"
set "EXIT_CODE=%ERRORLEVEL%"
if "%NO_PAUSE%"=="0" pause
exit /b %EXIT_CODE%
