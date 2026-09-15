$ErrorActionPreference = 'Stop'
$Base = Split-Path -Parent $MyInvocation.MyCommand.Path
$Jar = (Resolve-Path (Join-Path $Base 'dist/controller.jar')).Path
$Config = (Resolve-Path (Join-Path $Base 'dist/controller.properties')).Path
$Publish = [System.IO.Path]::GetFullPath((Join-Path $Base '../../publish/1910-Churrasco'))
$PidFile = Join-Path $Base 'controller.pid'
$Port = 51910
$RulesHash = '4b8bc4cc855608dffb6069f45cae4ff98f4f07391b7907689d1c303366bf5915'

if (Test-Path $PidFile) {
    $RecordedPid = [int](Get-Content $PidFile -Raw)
    $Owned = Get-CimInstance Win32_Process -Filter "ProcessId=$RecordedPid" -ErrorAction SilentlyContinue
    if ($Owned -and $Owned.Name -eq 'java.exe' -and $Owned.CommandLine -like "*$Jar*") {
        Stop-Process -Id $RecordedPid -Force
        Wait-Process -Id $RecordedPid -ErrorAction SilentlyContinue
    }
}

$Process = Start-Process -FilePath java -ArgumentList @('-jar',$Jar,'--config',$Config,'--port',[string]$Port,'--bind','0.0.0.0','--publish',$Publish) -WorkingDirectory $Base -WindowStyle Hidden -PassThru
Set-Content -LiteralPath $PidFile -Value $Process.Id -NoNewline
$Deadline = [DateTime]::UtcNow.AddSeconds(20)
do {
    Start-Sleep -Milliseconds 250
    try { $Health = Invoke-RestMethod "http://127.0.0.1:$Port/health" -TimeoutSec 2 } catch { $Health = $null }
} until ($Health -or [DateTime]::UtcNow -ge $Deadline)
if (-not $Health) { throw 'Controller 未在期限内就绪。' }
if ([int]$Health.gameId -ne 1910 -or [string]$Health.rulesHash -ne $RulesHash) { throw '监听进程身份校验失败。' }

$ConfigProbe = Invoke-RestMethod "http://127.0.0.1:$Port/cp/config/initialData?gid=1910&language=pt-br"
$InitProbe = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$Port/cp/single_game.Game/initRoom" -Body 'bet_gold=0.5&level=1'
Invoke-RestMethod "http://127.0.0.1:$Port/__qa/force?mode=loss" | Out-Null
$SpinProbe = Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:$Port/cp/single_game.Game/gameResult" -Body 'bet_gold=0.5&level=1'
if ($ConfigProbe.code -ne 0 -or $InitProbe.code -ne 0 -or $SpinProbe.code -ne 0) { throw 'Config/Init/Spin 自检失败。' }
$DemoUrl = "http://127.0.0.1:$Port/"
Set-Content -LiteralPath (Join-Path $Base 'demo-url.txt') -Value $DemoUrl -NoNewline
Write-Host "[gid 1910] Controller v3 已就绪：$DemoUrl"
