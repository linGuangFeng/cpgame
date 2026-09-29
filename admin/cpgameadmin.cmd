@echo off
rem Backward-compatible entry point. New deployments should use package-restart.cmd.
call "%~dp0package-restart.cmd" %*
exit /b %errorlevel%
