@echo off
setlocal
cd /d "%~dp0"
java -jar wukong-redis-loader.jar generator.properties
set "exit_code=%ERRORLEVEL%"
echo.
if "%exit_code%"=="0" (echo Redis 完整局生成与写入成功。) else (echo 执行失败，请检查上方网络、配置或规则校验信息。)
if /I not "%~1"=="--no-pause" pause
exit /b %exit_code%
