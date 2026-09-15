@echo off
setlocal
set "SCRIPT_DIR=%~dp0"
echo [Samba Sensation 2290] 正在连接正式Redis并预生成完整局...
java -jar "%SCRIPT_DIR%samba-sensation-redis-loader.jar" "%SCRIPT_DIR%generator.properties"
set "EXIT_CODE=%ERRORLEVEL%"
if "%EXIT_CODE%"=="0" echo 完成：普通输、普通赢、Free Spins与金币奖励均已由ResultUtil复核。
if not "%EXIT_CODE%"=="0" echo 失败：请先检查Redis网络与generator.properties，不要修改规则出牌。
if /I not "%~1"=="--no-pause" pause
exit /b %EXIT_CODE%
