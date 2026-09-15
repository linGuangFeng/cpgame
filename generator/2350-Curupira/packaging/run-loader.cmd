@echo off
setlocal
chcp 65001 >nul
set "SCRIPT_DIR=%~dp0"
set "NO_PAUSE="
if /I "%~1"=="--no-pause" (
  set "NO_PAUSE=1"
  shift
)
if not "%~1"=="" (
  echo [失败] 不支持的参数：%~1
  set "EXIT_CODE=2"
  goto :finish
)
where java >nul 2>&1
if errorlevel 1 (
  echo [失败] 未找到 Java。请安装 Java 21 或更高版本并配置 PATH。
  set "EXIT_CODE=3"
  goto :finish
)
java -jar "%SCRIPT_DIR%curupira-round-loader-all.jar" --config "%SCRIPT_DIR%formal-loader.properties"
set "EXIT_CODE=%ERRORLEVEL%"
if "%EXIT_CODE%"=="0" (
  echo [成功] Curupira 正式 Loader 已正常结束。
) else (
  echo [失败] Curupira 正式 Loader 异常结束，退出码：%EXIT_CODE%
)
:finish
if not defined NO_PAUSE pause
endlocal & exit /b %EXIT_CODE%
