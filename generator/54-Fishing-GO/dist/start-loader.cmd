@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"
echo Fishing GO 正式结果 Loader 正在启动……
java -jar "%~dp0fishing-go-loader.jar" "%~dp0generator.properties"
set "EXIT_CODE=%ERRORLEVEL%"
if not "%EXIT_CODE%"=="0" echo Loader 执行失败，退出码：%EXIT_CODE%
if /I not "%~1"=="--no-pause" pause
exit /b %EXIT_CODE%
