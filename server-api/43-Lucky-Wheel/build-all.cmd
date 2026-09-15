@echo off
chcp 65001 >nul
setlocal
set "PROJECT_DIR=%~dp0"
set "GENERATOR_DIR=%PROJECT_DIR%..\..\generator\43-Lucky-Wheel"
set "PAUSE_AT_END=1"
if /I "%~1"=="--no-pause" set "PAUSE_AT_END=0"
call mvn.cmd "-Dmaven.repo.local=%USERPROFILE%\.m2\repository" -f "%GENERATOR_DIR%\pom.xml" clean install
if errorlevel 1 (
  echo [失败] Java结果生成器构建失败。
  set "EXIT_CODE=1"
  goto :finish
)
copy /Y "%GENERATOR_DIR%\target\lucky-wheel-redis-loader.jar" "%GENERATOR_DIR%\dist\lucky-wheel-redis-loader.jar" >nul
call mvn.cmd "-Dmaven.repo.local=%USERPROFILE%\.m2\repository" -f "%PROJECT_DIR%pom.xml" clean package
if errorlevel 1 (
  echo [失败] Java服务端构建失败。
  set "EXIT_CODE=1"
  goto :finish
)
copy /Y "%PROJECT_DIR%target\controller.jar" "%PROJECT_DIR%dist\controller.jar" >nul
echo [OK] Controller v3 artifacts updated.
set "EXIT_CODE=0"
:finish
if "%PAUSE_AT_END%"=="1" pause
exit /b %EXIT_CODE%
