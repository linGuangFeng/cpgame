@echo off
setlocal EnableExtensions
set "PROJECT_DIR=%~dp0"
call mvn.cmd -f "%PROJECT_DIR%pom.xml" clean package
if errorlevel 1 exit /b 1
if not exist "%PROJECT_DIR%dist" mkdir "%PROJECT_DIR%dist"
del /q "%PROJECT_DIR%dist\*" >nul 2>nul
copy /y "%PROJECT_DIR%target\saci-redis-loader.jar" "%PROJECT_DIR%dist\saci-redis-loader.jar" >nul
copy /y "%PROJECT_DIR%src\dist\generator.properties" "%PROJECT_DIR%dist\generator.properties" >nul
copy /y "%PROJECT_DIR%src\dist\run-generator.cmd" "%PROJECT_DIR%dist\run-generator.cmd" >nul
endlocal
