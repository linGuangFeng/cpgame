@echo off
setlocal
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0restart-demo.ps1" %*
set "EXIT_CODE=%ERRORLEVEL%"
echo Demo exit code: %EXIT_CODE%
echo %* | findstr /I /C:"--no-pause" >nul
if errorlevel 1 pause
exit /b %EXIT_CODE%
