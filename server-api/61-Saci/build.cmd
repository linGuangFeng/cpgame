@echo off
setlocal
set "PROJECT_DIR=%~dp0"
set "GENERATOR_DIR=%PROJECT_DIR%..\..\generator\61-Saci"
call mvn.cmd -f "%GENERATOR_DIR%\pom.xml" clean install
if errorlevel 1 exit /b 1
call mvn.cmd -f "%PROJECT_DIR%pom.xml" clean package
if errorlevel 1 exit /b 1
if not exist "%PROJECT_DIR%dist\lib" mkdir "%PROJECT_DIR%dist\lib"
copy /y "%PROJECT_DIR%target\saci-server-api-1.0.0.jar" "%PROJECT_DIR%dist\saci-server-api.jar" >nul
copy /y "%GENERATOR_DIR%\target\saci-generator-1.0.0.jar" "%PROJECT_DIR%dist\lib\saci-generator-1.0.0.jar" >nul
endlocal
