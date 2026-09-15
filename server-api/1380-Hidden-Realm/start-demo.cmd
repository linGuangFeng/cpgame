@echo off
setlocal
cd /d "%~dp0"
if "%PORT%"=="" set PORT=51380
java -jar dist\controller.jar --config dist\controller.properties --port %PORT% --publish ..\..\publish\1380-Hidden-Realm
endlocal
