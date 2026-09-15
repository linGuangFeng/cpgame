@echo off
setlocal
set "DIST_DIR=%~dp0"
set "NO_PAUSE=0"
if /I "%~1"=="--no-pause" set "NO_PAUSE=1"

cd /d "%DIST_DIR%"
java -jar "%DIST_DIR%club-goddess-api.jar" --spring.config.additional-location="file:%DIST_DIR%application.properties"
set "EXIT_CODE=%ERRORLEVEL%"

if "%EXIT_CODE%"=="0" (
  powershell.exe -NoProfile -EncodedCommand VwByAGkAdABlAC0ASABvAHMAdAAgACcAWwAQYp9SXQAgAEoAYQB2AGEAIABBAFAASQAgAA1noVLyXWNrOF7Tfl9nAjAnAA==
) else (
  powershell.exe -NoProfile -EncodedCommand VwByAGkAdABlAC0ASABvAHMAdAAgACgAJwBbADFZJY1dACAASgBhAHYAYQAgAEEAUABJACAADWehUi9UqFIWYtCPTIgxWSWNDP8AkPpRAXga/ycAIAArACAAJABlAG4AdgA6AEUAWABJAFQAXwBDAE8ARABFACAAKwAgACcAAjAnACkA
)

if "%NO_PAUSE%"=="0" (
  powershell.exe -NoProfile -EncodedCommand VwByAGkAdABlAC0ASABvAHMAdAAgACcACWP7Tg9hLpVzUe2Vl3rjUyYgJiAnAA==
  pause >nul
)

endlocal & exit /b %EXIT_CODE%
