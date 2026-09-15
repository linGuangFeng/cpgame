@echo off
setlocal
cd /d "%~dp0"
java -jar "%~dp0loader.jar" --config "%~dp0generator.properties" %*
set "LNM_EXIT=%ERRORLEVEL%"
for %%A in (%*) do if /I "%%~A"=="--no-pause" goto done
pause
:done
exit /b %LNM_EXIT%
