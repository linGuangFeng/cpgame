@echo off
setlocal
cd /d "%~dp0"
java -jar redis-loader.jar generator.properties
set "RESULT=%ERRORLEVEL%"
echo.
if "%RESULT%"=="0" (echo Redis 完整局生成完成。) else (echo 生成失败，错误码 %RESULT%。请先检查 Redis 网络和配置，不要修改出牌规则。)
if /i not "%~1"=="--no-pause" pause
exit /b %RESULT%

