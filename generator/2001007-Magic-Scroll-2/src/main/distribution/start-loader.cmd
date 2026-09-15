@echo off
setlocal EnableExtensions
chcp 65001 >nul

set "NO_PAUSE=0"
set "EXIT_CODE=0"

if "%~1"=="" goto ARGUMENTS_OK
if /i "%~1"=="--no-pause" goto ARGUMENT_NO_PAUSE
goto UNKNOWN_ARGUMENT

:ARGUMENT_NO_PAUSE
set "NO_PAUSE=1"
shift /1
if not "%~1"=="" goto UNKNOWN_ARGUMENT
goto ARGUMENTS_OK

:UNKNOWN_ARGUMENT
echo [错误] 不支持的参数：%~1
set "EXIT_CODE=2"
goto FINISH

:ARGUMENTS_OK
set "SCRIPT_DIR=%~dp0"
set "LOADER_JAR=%SCRIPT_DIR%magic-scroll2-loader.jar"
set "LOADER_CONFIG=%SCRIPT_DIR%generator.properties"

if not exist "%LOADER_JAR%" (
  echo [错误] 找不到 Java Loader：%LOADER_JAR%
  set "EXIT_CODE=1"
  goto FINISH
)
if not exist "%LOADER_CONFIG%" (
  echo [错误] 找不到正式配置：%LOADER_CONFIG%
  set "EXIT_CODE=1"
  goto FINISH
)

echo [信息] 正在启动 Magic Scroll 2 Java Redis Loader...
java -jar "%LOADER_JAR%" "%LOADER_CONFIG%"
set "EXIT_CODE=%ERRORLEVEL%"

if "%EXIT_CODE%"=="0" (
  echo [成功] Loader 已完成，退出码 0。
) else (
  echo [失败] Loader 退出码：%EXIT_CODE%
)

:FINISH
if "%NO_PAUSE%"=="0" (
  echo.
  echo 按任意键关闭窗口...
  pause >nul
)

endlocal & exit /b %EXIT_CODE%
