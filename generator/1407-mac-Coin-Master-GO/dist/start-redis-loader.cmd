@echo off
chcp 936 >nul
setlocal EnableExtensions
set "NO_PAUSE="
set "LOADER_EXIT=1"
if /I "%~1"=="--no-pause" set "NO_PAUSE=1"

pushd "%~dp0"
if errorlevel 1 (
  echo [失败] 无法进入生成器目录：%~dp0
  set "LOADER_EXIT=1"
  goto FINISH
)

where java.exe >nul 2>&1
if errorlevel 1 (
  echo [失败] 未找到 Java，请安装或配置 Java 21。
  set "LOADER_EXIT=2"
  goto LEAVE_DIRECTORY
)

echo [启动] 使用正式 SecureRandom 链路生成完整 Round 并写入 Redis……
set "LOADER_JAR="
for /f "delims=" %%F in ('dir /b /a-d /o-d "%~dp0coin-master-go-redis-loader*.jar"') do if not defined LOADER_JAR set "LOADER_JAR=%~dp0%%F"
java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -jar "%LOADER_JAR%" "%~dp0generator.properties"
set "LOADER_EXIT=%ERRORLEVEL%"
if "%LOADER_EXIT%"=="0" (
  echo [完成] Redis Loader 已正常结束。
) else (
  echo [失败] Redis Loader 返回代码 %LOADER_EXIT%，请检查上方信息和 generator.properties。
)

:LEAVE_DIRECTORY
popd
:FINISH
if not defined NO_PAUSE pause
exit /b %LOADER_EXIT%
