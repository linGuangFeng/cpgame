@echo off
setlocal
set "BASE=%~dp0"
if not exist "%BASE%lucky-dragon-redis-loader.jar" (
  echo [失败] 找不到 %BASE%lucky-dragon-redis-loader.jar
  exit /b 2
)
"%JAVA_HOME%\bin\java.exe" -version >nul 2>&1
if errorlevel 1 set "JAVA_CMD=java"
if not defined JAVA_CMD set "JAVA_CMD=%JAVA_HOME%\bin\java.exe"
"%JAVA_CMD%" -jar "%BASE%lucky-dragon-redis-loader.jar" "%BASE%generator.properties"
set "EXIT_CODE=%ERRORLEVEL%"
if "%EXIT_CODE%"=="0" (echo [完成] raw gid42 Redis Loader 已正常退出) else (echo [失败] Redis Loader 退出码 %EXIT_CODE%)
if /i not "%1"=="--no-pause" pause
exit /b %EXIT_CODE%
