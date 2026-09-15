@echo off
setlocal
rem 仅运行预生成器：生成完整局并事务写入固定 Redis db15。
rem Demo 运行时绝不调用此脚本，也不从本地文件补造 LOSS。
set "NO_PAUSE=0"
if /I "%~1"=="--no-pause" set "NO_PAUSE=1"
java -jar "%~dp0churrasco-generator.jar" --config "%~dp0generator.properties"
set "EXIT_CODE=%ERRORLEVEL%"
if "%NO_PAUSE%"=="0" pause
exit /b %EXIT_CODE%
