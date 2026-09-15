@echo off
setlocal EnableExtensions
set "SCRIPT_DIR=%~dp0"
set "NO_PAUSE="
for %%A in (%*) do if /I "%%~A"=="--no-pause" set "NO_PAUSE=1"

java -jar "%SCRIPT_DIR%jurassic-jungle-loader.jar" "--config=%SCRIPT_DIR%generator.properties"
set "EXIT_CODE=%ERRORLEVEL%"
if "%EXIT_CODE%"=="0" (
  echo Jurassic Jungle raw gid 8 complete-Round generation succeeded.
) else (
  echo Jurassic Jungle raw gid 8 complete-Round generation failed with exit code %EXIT_CODE%.
)

if not defined NO_PAUSE pause
exit /b %EXIT_CODE%
