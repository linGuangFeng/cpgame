@echo off
setlocal
chcp 65001 >nul
set "SCRIPT_DIR=%~dp0"
call "%SCRIPT_DIR%dist\start-loader.cmd" %*
exit /b %ERRORLEVEL%
