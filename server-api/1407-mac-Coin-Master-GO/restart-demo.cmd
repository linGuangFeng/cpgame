@echo off
setlocal EnableExtensions EnableDelayedExpansion

set "PID_FILE=%~dp0..\..\reports\1407-Coin-Master-GO\runtime\api.validated.pid"
if exist "%PID_FILE%" del /f /q "%PID_FILE%" >nul 2>&1

rem Resolve only the PID of the exact game 1407 Java Demo JAR.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0resolve-demo-pid.ps1" -OutputFile "%PID_FILE%"
if errorlevel 1 exit /b 4
if not exist "%PID_FILE%" goto START_DEMO

set "PID="
set /p "PID="<"%PID_FILE%"
echo(%PID%| findstr /r "^[1-9][0-9]*$" >nul
if errorlevel 1 exit /b 5

rem Recheck that the validated PID still exists immediately before the exact PID kill.
set "TASKLIST_PID="
for /f "tokens=2 delims=," %%P in ('tasklist /FI "PID eq %PID%" /FO CSV /NH') do set "TASKLIST_PID=%%~P"
if not "%TASKLIST_PID%"=="%PID%" exit /b 6
taskkill /F /PID %PID% >nul 2>&1
if errorlevel 1 exit /b 7

del /f /q "%PID_FILE%" >nul 2>&1
del /f /q "%~dp0..\..\reports\1407-Coin-Master-GO\runtime\api.process.json" >nul 2>&1

:START_DEMO
if not exist "%~dp0restart-demo.ps1" (
  echo Validated demo runner is missing: %~dp0restart-demo.ps1 1>&2
  exit /b 2
)
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0restart-demo.ps1" %*
set "DEMO_RC=%ERRORLEVEL%"
if not "%DEMO_RC%"=="0" echo Demo restart failed with exit code %DEMO_RC% 1>&2
if not "%DEMO_RC%"=="0" exit /b %DEMO_RC%

if not exist "%~dp0demo-url.txt" (
  echo Demo URL file is missing: %~dp0demo-url.txt 1>&2
  exit /b 3
)
set /p "DEMO_URL="<"%~dp0demo-url.txt"
rem Delayed expansion prevents '&' in the query string from being reparsed as CMD commands.
echo PAGE=!DEMO_URL!
exit /b %DEMO_RC%
