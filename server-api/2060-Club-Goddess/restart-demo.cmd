@echo off
setlocal EnableExtensions EnableDelayedExpansion
set "PROJECT_DIR=%~dp0"
set "NO_PAUSE=0"
if /I "%~1"=="--no-pause" set "NO_PAUSE=1"
set "PID_FILE=%PROJECT_DIR%dist\demo-api.pid"
set "EXPECTED_JAR=%PROJECT_DIR%dist\club-goddess-api.jar"
set "EXIT_CODE=0"

rem PID safety gate: validate demo-api.pid, Java process and this game's JAR before restart.
rem A mismatched live PID is never stopped; the validator returns a non-zero exit code.
if not exist "%PID_FILE%" (
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%PROJECT_DIR%restart-demo.ps1" -ValidateFreshStartOnly -ExpectedJar "%EXPECTED_JAR%"
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
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%PROJECT_DIR%restart-demo.ps1" -ValidatePidOnly -ExpectedProcessId !DEMO_PROCESS_ID! -ExpectedJar "%EXPECTED_JAR%"
  set "PID_CHECK_EXIT=!ERRORLEVEL!"
  if not "!PID_CHECK_EXIT!"=="0" (
    set "EXIT_CODE=!PID_CHECK_EXIT!"
    goto :show_result
  )
)

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%PROJECT_DIR%restart-demo.ps1" -ExpectedJar "%EXPECTED_JAR%"
set "EXIT_CODE=%ERRORLEVEL%"

:show_result
if "%EXIT_CODE%"=="0" (
  powershell.exe -NoProfile -EncodedCommand VwByAGkAdABlAC0ASABvAHMAdAAgACcAWwAQYp9SXQAgANWLqXNZlwFgDWehUg5OIABKAGEAdgBhACAAQQBQAEkAIADyXYxbEGLNkS9UAjAnAA==
  powershell.exe -NoProfile -EncodedCommand VwByAGkAdABlAC0ASABvAHMAdAAgACcA1Yupc3WYYpcwV0BXCP9lZ+qBIABkAGUAbQBvAC0AdQByAGwALgB0AHgAdAAJ/xr/JwA=
  type "%PROJECT_DIR%demo-url.txt"
) else (
  powershell.exe -NoProfile -EncodedCommand VwByAGkAdABlAC0ASABvAHMAdAAgACgAJwBbADFZJY1dACAA1Yupcw1noVLNkS9UMVkljQz/AJD6UQF4Gv8nACAAKwAgACQAZQBuAHYAOgBFAFgASQBUAF8AQwBPAEQARQAgACsAIAAnAAIwJwApAA==
)

if "%NO_PAUSE%"=="0" (
  powershell.exe -NoProfile -EncodedCommand VwByAGkAdABlAC0ASABvAHMAdAAgACcACWP7Tg9hLpVzUe2Vl3rjUyYgJiAnAA==
  pause >nul
)

endlocal & exit /b %EXIT_CODE%
