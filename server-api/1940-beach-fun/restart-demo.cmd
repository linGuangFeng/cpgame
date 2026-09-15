@echo off
setlocal
cd /d "%~dp0dist"
java -jar controller.jar --config controller.properties --port 51940 --publish "%~dp0..\..\..\publish\1940-beach-fun" %*
exit /b %ERRORLEVEL%
