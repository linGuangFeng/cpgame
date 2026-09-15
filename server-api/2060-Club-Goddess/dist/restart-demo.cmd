@echo off
setlocal EnableExtensions EnableDelayedExpansion
set "DIST_DIR=%~dp0"
set "NO_PAUSE=0"
if /I "%~1"=="--no-pause" set "NO_PAUSE=1"
set "PID_FILE=%DIST_DIR%demo-api.pid"
set "EXPECTED_JAR=%DIST_DIR%club-goddess-api.jar"
set "ROOT_PID_VALIDATOR=%DIST_DIR%..\restart-demo.ps1"
set "DEMO_PROCESS_ID=0"
set "EXIT_CODE=0"

rem Normalize duplicate Path/PATH entries before Windows PowerShell Start-Process.
set "DEMO_ORIGINAL_PATH=%PATH%"
set "PATH="
set "Path=%DEMO_ORIGINAL_PATH%"
set "DEMO_ORIGINAL_PATH="

rem PID safety gate: read the recorded PID and verify Java plus this exact game JAR.
rem A malformed PID, changed PID or different live process aborts before any stop action.
if not exist "%PID_FILE%" (
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%ROOT_PID_VALIDATOR%" -ValidateFreshStartOnly -ExpectedJar "%EXPECTED_JAR%"
  set "PID_CHECK_EXIT=!ERRORLEVEL!"
  if not "!PID_CHECK_EXIT!"=="0" (
    set "EXIT_CODE=!PID_CHECK_EXIT!"
    goto :show_result
  )
)
if exist "%PID_FILE%" (
  set "DEMO_PROCESS_ID="
  set /p "DEMO_PROCESS_ID="<"%PID_FILE%"
  echo(!DEMO_PROCESS_ID!| "%SystemRoot%\System32\findstr.exe" /R /X "[0-9][0-9]*" >nul
  if errorlevel 1 (
    set "EXIT_CODE=3"
    goto :show_result
  )
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%ROOT_PID_VALIDATOR%" -ValidatePidOnly -ExpectedProcessId !DEMO_PROCESS_ID! -ExpectedJar "%EXPECTED_JAR%"
  set "PID_CHECK_EXIT=!ERRORLEVEL!"
  if not "!PID_CHECK_EXIT!"=="0" (
    set "EXIT_CODE=!PID_CHECK_EXIT!"
    goto :show_result
  )
)

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%DIST_DIR%restart-demo.ps1"
set "EXIT_CODE=%ERRORLEVEL%"

:show_result
if "%EXIT_CODE%"=="0" (
  powershell.exe -NoProfile -EncodedCommand VwByAGkAdABlAC0ASABvAHMAdAAgACcAWwAQYp9SXQAgANWLqXNZlwFgDWehUg5OIABKAGEAdgBhACAAQQBQAEkAIADyXYxbEGLNkS9UAjAnAA==
) else (
  powershell.exe -NoProfile -EncodedCommand VwByAGkAdABlAC0ASABvAHMAdAAgACgAJwBbADFZJY1dACAA1Yupcw1noVLNkS9UMVkljQz/AJD6UQF4Gv8nACAAKwAgACQAZQBuAHYAOgBFAFgASQBUAF8AQwBPAEQARQAgACsAIAAnAAIwJwApAA==
)

if "%NO_PAUSE%"=="0" (
  powershell.exe -NoProfile -EncodedCommand VwByAGkAdABlAC0ASABvAHMAdAAgACcACWP7Tg9hLpVzUe2Vl3rjUyYgJiAnAA==
  pause >nul
)

endlocal & exit /b %EXIT_CODE%
