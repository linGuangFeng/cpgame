param([switch]$NoPause)
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Dist = Join-Path $Root 'dist'
$Jar = Join-Path $Dist 'jungle-fruit-server-api-1.0.0.jar'
$Config = Join-Path $Dist 'server.properties'
$ProcessFile = Join-Path $Root 'jungle-fruit.process.json'
$ValidatedPidFile = Join-Path $Root 'jungle-fruit.validated.pid'
$Stdout = Join-Path $Root 'demo.stdout.log'
$Stderr = Join-Path $Root 'demo.stderr.log'
$Health = 'http://127.0.0.1:29616/health'
$DemoUrl = (Get-Content (Join-Path $Root 'demo-url.txt') -Raw).Trim()

if (Test-Path $ProcessFile) {
  try {
    $Saved = Get-Content $ProcessFile -Raw | ConvertFrom-Json
    $Candidate = Get-CimInstance Win32_Process -Filter "ProcessId = $($Saved.pid)"
    if ($Candidate -and $Candidate.CommandLine -like "*$([IO.Path]::GetFileName($Jar))*") {
      $Running = Get-Process -Id $Saved.pid -ErrorAction Stop
      $ActualStartTime = $Running.StartTime.ToUniversalTime().ToString('o')
      if ($ActualStartTime -eq $Saved.startTime) {
        $Candidate.ProcessId | Set-Content $ValidatedPidFile
        Stop-Process -Id $Candidate.ProcessId -Force
        $Running.WaitForExit()
      }
    }
  } catch { Write-Host "旧进程校验未通过，不会结束无关进程：$($_.Exception.Message)" }
}

$JavaArgs = @('-jar', $Jar, '--config', $Config, '--server.address=0.0.0.0')
$Started = Start-Process -FilePath 'java' -ArgumentList $JavaArgs -WorkingDirectory $Root -RedirectStandardOutput $Stdout -RedirectStandardError $Stderr -PassThru
$Started.Refresh()
$StartedInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $($Started.Id)"
[ordered]@{ pid=$Started.Id; startTime=$Started.StartTime.ToUniversalTime().ToString('o'); commandLine=$StartedInfo.CommandLine; jar=$Jar } |
  ConvertTo-Json | Set-Content $ProcessFile -Encoding UTF8

$Ready = $false
for ($Attempt = 0; $Attempt -lt 60; $Attempt++) {
  try { Invoke-WebRequest -UseBasicParsing -Uri $Health -TimeoutSec 2 | Out-Null; $Ready = $true; break }
  catch { Start-Sleep -Milliseconds 500 }
}
if (-not $Ready) { throw 'Java Demo 未在预期时间内就绪。' }

$Headers = @{ 'Content-Type'='application/x-www-form-urlencoded' }
$Init = Invoke-WebRequest -UseBasicParsing -Method Post -Uri 'http://127.0.0.1:29616/cp/api/v1/auth/verify' -Headers $Headers -Body 'gid=16&t=local'
$ConfigResult = Invoke-WebRequest -UseBasicParsing -Method Post -Uri 'http://127.0.0.1:29616/cp/api/v1/jungle-fruit/config' -Headers $Headers -Body 'gid=16&t=local-gid16-session'
$Spin = Invoke-WebRequest -UseBasicParsing -Method Post -Uri 'http://127.0.0.1:29616/cp/api/v1/jungle-fruit/spin' -Headers $Headers -Body 'gid=16&t=local-gid16-session&bl=1&bs=0.05&request_id=restart-smoke'
if ($Init.StatusCode -ne 200 -or $ConfigResult.StatusCode -ne 200 -or $Spin.StatusCode -ne 200) { throw 'Init/Config/最小 Spin 自检失败。' }

$LanIp = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
  Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' } |
  Select-Object -First 1 -ExpandProperty IPAddress
Write-Host "试玩已启动：$DemoUrl"
if ($LanIp) { Write-Host "局域网页面：http://${LanIp}:29616/（服务监听 0.0.0.0）" }
Write-Host 'Init / Config / 最小 Spin 自检通过。'
exit 0
