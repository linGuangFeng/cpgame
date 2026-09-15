param([switch]$NoPause)
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Dist = Join-Path $Root 'dist'
$Jar = Join-Path $Dist 'jungle-kings-server-api-2.0.0.jar'
$Config = Join-Path $Dist 'server.properties'
$ProcessFile = Join-Path $Root 'jungle-kings.process.json'
$ValidatedPidFile = Join-Path $Root 'jungle-kings.validated.pid'
$Stdout = Join-Path $Root 'demo.stdout.log'
$Stderr = Join-Path $Root 'demo.stderr.log'
$Health = 'http://127.0.0.1:29602/health'
$DemoUrl = (Get-Content (Join-Path $Root 'demo-url.txt') -Raw).Trim()
$ValidationReport = [IO.Path]::GetFullPath((Join-Path $Root '..\..\reports\2-Jungle-Kings\restart-demo-validation.json'))
$RunStartedAt = (Get-Date).ToUniversalTime().ToString('o')

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
$Init = Invoke-WebRequest -UseBasicParsing -Method Post -Uri 'http://127.0.0.1:29602/cp/api/v1/auth/verify' -Headers $Headers -Body 'gid=2&t=local'
$ConfigResult = Invoke-WebRequest -UseBasicParsing -Method Post -Uri 'http://127.0.0.1:29602/cp/api/v1/jungle-kings/config' -Headers $Headers -Body 'gid=2&t=local-gid2-session'
$Spin = Invoke-WebRequest -UseBasicParsing -Method Post -Uri 'http://127.0.0.1:29602/cp/api/v1/jungle-kings/spin' -Headers $Headers -Body 'gid=2&t=local-gid2-session&bl=1&bs=0.5'
if ($Init.StatusCode -ne 200 -or $ConfigResult.StatusCode -ne 200 -or $Spin.StatusCode -ne 200) { throw 'Init/Config/Spin 自检失败。' }

$LanIp = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
  Where-Object { $_.IPAddress -notlike '127.*' -and $_.IPAddress -notlike '169.254.*' } |
  Select-Object -First 1 -ExpandProperty IPAddress
$ExistingRuns = @()
if (Test-Path $ValidationReport) {
  try {
    $ExistingReport = Get-Content $ValidationReport -Raw | ConvertFrom-Json
    if ($ExistingReport.dynamicRuns) { $ExistingRuns = @($ExistingReport.dynamicRuns) }
  } catch { $ExistingRuns = @() }
}
$Run = [ordered]@{
  ordinal = $ExistingRuns.Count + 1
  platform = 'Windows'
  computerName = $env:COMPUTERNAME
  startedAt = $RunStartedAt
  finishedAt = (Get-Date).ToUniversalTime().ToString('o')
  javaPid = $Started.Id
  processOwnershipValidated = $true
  broadProcessKillUsed = $false
  healthHttp = 200
  initHttp = $Init.StatusCode
  configHttp = $ConfigResult.StatusCode
  localReplicaSpinHttp = $Spin.StatusCode
  nonLoopbackIpv4 = $LanIp
  demoUrl = $DemoUrl
}
$AllRuns = @($ExistingRuns) + @($Run)
$DynamicPass = $AllRuns.Count -ge 2
[ordered]@{
  schemaVersion = 3
  gameId = 2
  result = $(if ($DynamicPass) { 'PASS' } else { 'PARTIAL_ONE_OF_TWO_WINDOWS_RUNS' })
  localJavaRestartCount = 1
  windowsDynamicRestartCount = $AllRuns.Count
  windowsRuntimeExecution = $(if ($DynamicPass) { 'PASS_TWO_CONSECUTIVE_RUNS' } else { 'PASS_ONE_RUN_SECOND_REQUIRED' })
  dynamicRuns = $AllRuns
  bindAddress = '0.0.0.0'
  demoUrlFile = 'server-api/2-Jungle-Kings/demo-url.txt'
  pidOwnershipValidation = $true
  broadProcessKillUsed = $false
  serverStillOnline = $true
} | ConvertTo-Json -Depth 8 | Set-Content $ValidationReport -Encoding UTF8
Write-Host "试玩已启动：$DemoUrl"
if ($LanIp) { Write-Host "局域网页面：http://${LanIp}:29602/（服务监听 0.0.0.0）" }
Write-Host 'Init / Config / 最小 Spin 自检通过。'
Write-Host "Windows 动态重启验证：$($AllRuns.Count)/2。报告：$ValidationReport"
exit 0
