@echo off
setlocal EnableExtensions EnableDelayedExpansion
chcp 65001 >nul
set "PROJECT_DIR=%~dp0"

set "PAUSE_ON_EXIT=1"
if /I "%~1"=="--no-pause" (
  set "PAUSE_ON_EXIT=0"
  shift
)
if not "%~1"=="" (
  echo [失败] 不支持的参数：%~1 1>&2
  set "EXIT_CODE=2"
  goto finish
)

set "DIST_DIR=%PROJECT_DIR%dist"
set "JAR_FILE=%DIST_DIR%\saci-server-api.jar"
set "CONFIG_FILE=%DIST_DIR%\server.properties"
set "PID_FILE=%DIST_DIR%\server.pid"
set "LOG_DIR=%PROJECT_DIR%logs"
set "STDOUT_FILE=%LOG_DIR%\demo.out.log"
set "STDERR_FILE=%LOG_DIR%\demo.err.log"
set "DEMO_URL_FILE=%PROJECT_DIR%demo-url.txt"
set "BASE_URL=http://192.168.10.3:19610"

if not exist "%JAR_FILE%" (
  echo [失败] 缺少 Saci Demo JAR。 1>&2
  goto failed
)
if not exist "%CONFIG_FILE%" (
  echo [失败] 缺少 Saci Demo 正式配置。 1>&2
  goto failed
)
if not exist "%DEMO_URL_FILE%" (
  echo [失败] 缺少 demo-url.txt。 1>&2
  goto failed
)
if not exist "%PROJECT_DIR%restart-demo.ps1" (
  echo [失败] 缺少同目录 restart-demo.ps1。 1>&2
  goto failed
)

findstr /L /C:"bind.address=0.0.0.0" "%CONFIG_FILE%" >nul
if errorlevel 1 (
  echo [失败] Java API 和静态服务必须监听所有网卡。 1>&2
  goto failed
)

rem demo-url.txt must contain exactly one canonical local HTTP page address.
powershell.exe -NoProfile -Command "$lines=@(Get-Content -LiteralPath '%DEMO_URL_FILE%' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) });if($lines.Count -ne 1){exit 1};$url=[uri]$lines[0].Trim();if($url.Scheme -ne 'http' -or $url.AbsolutePath -ne '/demo' -or $url.Port -le 0 -or -not [string]::IsNullOrEmpty($url.Query) -or -not [string]::IsNullOrEmpty($url.Fragment)){exit 1};exit 0"
if errorlevel 1 (
  echo [失败] demo-url.txt 必须只包含一个有效的本机 HTTP /demo 地址。 1>&2
  goto failed
)

rem Validate server.pid and the exact Saci JAR/config command line before stop.
if not exist "%PID_FILE%" goto old_process_stopped
set "OLD_PID="
set /p OLD_PID=<"%PID_FILE%"
if not defined OLD_PID (
  echo [失败] server.pid 内容为空。 1>&2
  goto failed
)
powershell.exe -NoProfile -Command "$checkedPid=0;if(-not [int]::TryParse('!OLD_PID!',[ref]$checkedPid)-or $checkedPid -le 0){exit 1};$p=Get-Process -Id $checkedPid -ErrorAction SilentlyContinue;if($null -eq $p){exit 0};if($p.ProcessName -ne 'java'){exit 1};$info=Get-CimInstance Win32_Process -Filter ('ProcessId='+$checkedPid) -ErrorAction SilentlyContinue;$command=[string]$info.CommandLine;if($null -eq $info -or $command.IndexOf([IO.Path]::GetFullPath('%JAR_FILE%'),[StringComparison]::OrdinalIgnoreCase) -lt 0 -or $command.IndexOf([IO.Path]::GetFullPath('%CONFIG_FILE%'),[StringComparison]::OrdinalIgnoreCase) -lt 0){exit 1};exit 0"
if errorlevel 1 (
  echo [失败] server.pid 不属于当前 Saci Java Demo，已拒绝停止该进程。 1>&2
  goto failed
)
tasklist /FI "PID eq !OLD_PID!" /NH | findstr /R /C:"[ ]!OLD_PID![ ]" >nul
if not errorlevel 1 taskkill /PID !OLD_PID! /T /F >nul
for /L %%A in (1,1,20) do (
  tasklist /FI "PID eq !OLD_PID!" /NH | findstr /R /C:"[ ]!OLD_PID![ ]" >nul
  if errorlevel 1 goto old_process_stopped
  timeout /t 1 /nobreak >nul
)
echo [失败] 等待旧 Saci Demo 进程 !OLD_PID! 退出超时。 1>&2
goto failed

:old_process_stopped
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

rem Start this game's Java Demo and persist its PID.
powershell.exe -NoProfile -Command "$p=Start-Process -FilePath 'java.exe' -ArgumentList @('-jar',[IO.Path]::GetFullPath('%JAR_FILE%'),[IO.Path]::GetFullPath('%CONFIG_FILE%')) -WorkingDirectory ([IO.Path]::GetFullPath('%DIST_DIR%')) -WindowStyle Hidden -RedirectStandardOutput ([IO.Path]::GetFullPath('%STDOUT_FILE%')) -RedirectStandardError ([IO.Path]::GetFullPath('%STDERR_FILE%')) -PassThru;[IO.File]::WriteAllText([IO.Path]::GetFullPath('%PID_FILE%'),[string]$p.Id+[Environment]::NewLine)"
if errorlevel 1 (
  echo [失败] 无法启动 Saci Java Demo。 1>&2
  goto failed
)

set "DEMO_PID="
set /p DEMO_PID=<"%PID_FILE%"
if not defined DEMO_PID (
  echo [失败] 新 server.pid 未写入。 1>&2
  goto failed
)

rem Wait until the all-interface HTTP service is ready.
for /L %%A in (1,1,60) do (
  curl.exe --fail --silent --show-error --max-time 2 "%BASE_URL%/health" >nul 2>&1
  if not errorlevel 1 goto http_ready
  timeout /t 1 /nobreak >nul
)
echo [失败] Saci Java Demo 未在规定时间内完成 HTTP 就绪。 1>&2
taskkill /PID !DEMO_PID! /T /F >nul 2>&1
goto failed

:http_ready
rem Execute Init, Config and a real minimum Spin against the running Java API.
powershell.exe -NoProfile -Command "$ErrorActionPreference='Stop';$base='%BASE_URL%';$launch='restart-smoke-'+[guid]::NewGuid().ToString('N');$init=Invoke-RestMethod -Method Post -Uri ($base+'/api/init') -ContentType 'application/x-www-form-urlencoded' -Body @{ai='local_61';btt='1';gid='61';t=$launch} -TimeoutSec 5;if($init.code -ne 200 -or [string]::IsNullOrWhiteSpace([string]$init.data.token)){throw 'Init failed'};$token=[string]$init.data.token;$config=Invoke-RestMethod -Method Post -Uri ($base+'/api/config') -ContentType 'application/x-www-form-urlencoded' -Body @{gid='61';t=$token} -TimeoutSec 5;if($config.code -ne 200 -or -not ($config.data.bll -contains 1) -or -not ($config.data.bsl -contains 0.02)){throw 'Config failed'};$spin=Invoke-RestMethod -Method Post -Uri ($base+'/api/spin') -ContentType 'application/x-www-form-urlencoded' -Body @{gid='61';t=$token;bl='1';bs='0.02';request_id=('restart-'+[guid]::NewGuid().ToString('N'))} -TimeoutSec 5;if($spin.code -ne 200 -or [decimal]$spin.data.ba -ne [decimal]0.4){throw 'Minimum Spin failed'};Write-Output 'Health=PASS Init=PASS Config=PASS MinimumSpin=PASS'"
if errorlevel 1 (
  echo [失败] Init、Config 或最小 Spin 冒烟检查失败。 1>&2
  taskkill /PID !DEMO_PID! /T /F >nul 2>&1
  goto failed
)

rem Validate the new PID and exact command line after HTTP smoke checks.
powershell.exe -NoProfile -Command "$checkedPid=0;if(-not [int]::TryParse('!DEMO_PID!',[ref]$checkedPid)-or $checkedPid -le 0){exit 1};$p=Get-Process -Id $checkedPid -ErrorAction SilentlyContinue;if($null -eq $p -or $p.ProcessName -ne 'java'){exit 1};$info=Get-CimInstance Win32_Process -Filter ('ProcessId='+$checkedPid) -ErrorAction SilentlyContinue;$command=[string]$info.CommandLine;if($null -eq $info -or $command.IndexOf([IO.Path]::GetFullPath('%JAR_FILE%'),[StringComparison]::OrdinalIgnoreCase) -lt 0 -or $command.IndexOf([IO.Path]::GetFullPath('%CONFIG_FILE%'),[StringComparison]::OrdinalIgnoreCase) -lt 0){exit 1};exit 0"
if errorlevel 1 (
  echo [失败] 新 PID 不属于当前 Saci Java Demo。 1>&2
  goto failed
)

rem Require the new Java PID to own an IPv4 listener on every network interface.
netstat -ano -p TCP | findstr /L /C:"0.0.0.0:19610" | findstr /R /C:"LISTENING *!DEMO_PID!" >nul
if errorlevel 1 (
  echo [失败] 新 Saci Java Demo 进程 !DEMO_PID! 未监听 0.0.0.0:19610。 1>&2
  goto failed
)

rem Keep the same-directory PowerShell entry as an independent read-only check.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%PROJECT_DIR%restart-demo.ps1" -ValidateOnly
if errorlevel 1 (
  echo [失败] PowerShell 只读复核未通过。 1>&2
  goto failed
)

set "DEMO_URL="
set /p DEMO_URL=<"%DEMO_URL_FILE%"
if not defined DEMO_URL (
  echo [失败] demo-url.txt 未包含页面地址。 1>&2
  goto failed
)
curl.exe --fail --location --silent --show-error --max-time 10 "!DEMO_URL!" >nul
if errorlevel 1 (
  echo [失败] demo-url.txt 中的页面地址尚未就绪。 1>&2
  goto failed
)

echo [成功] Saci Java Demo 已安全重启。PID=!DEMO_PID!，监听地址=0.0.0.0。
echo 试玩地址：!DEMO_URL!
set "EXIT_CODE=0"
goto finish

:failed
set "EXIT_CODE=1"

:finish
if "%PAUSE_ON_EXIT%"=="1" (
  echo 按任意键关闭窗口，自动化调用请使用 --no-pause。
  pause >nul
)
endlocal & exit /b %EXIT_CODE%
