$ErrorActionPreference = 'Stop'
$Base = Split-Path -Parent $MyInvocation.MyCommand.Path
$PidFile = Join-Path $Base 'dist/treasure-hunt.pid'
$Jar = Join-Path $Base 'dist/controller.jar'
$Config = Join-Path $Base 'dist/controller.properties'
$Publish = (Resolve-Path (Join-Path $Base '../../publish/1810-Treasure-Hunt')).Path

# Java Controller 自身监听 0.0.0.0；此脚本只选择空闲的动态 5xxxx 端口。
if (Test-Path -LiteralPath $PidFile) {
  $OldPid = [int](Get-Content -LiteralPath $PidFile -Raw)
  $Owned = Get-CimInstance Win32_Process -Filter "ProcessId=$OldPid" -ErrorAction SilentlyContinue
  if ($Owned -and $Owned.Name -match '^java(w)?\.exe$' -and $Owned.CommandLine -like '*dist\controller.jar*') { Stop-Process -Id $OldPid -Force }
}
if (-not (Test-Path -LiteralPath $Jar)) { throw "Missing $Jar" }
$Port = $null
foreach ($Candidate in (Get-Random -InputObject (50000..59999) -Count 200)) { $Listener=[System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Any,$Candidate);try{$Listener.Start();$Port=$Candidate;break}catch{}finally{$Listener.Stop()} }
if ($null -eq $Port) { throw 'No free 5xxxx port found' }
$Process = Start-Process java -ArgumentList @('-jar',$Jar,'--config',$Config,'--port',"$Port",'--publish',$Publish) -PassThru -WindowStyle Hidden
$Process.Id | Set-Content -LiteralPath $PidFile
$Url = "http://127.0.0.1:$Port/index.html?gid=1810&token=local-1810&language=en&sip=127.0.0.1%3A$Port"
$Url | Set-Content -LiteralPath (Join-Path $Base 'demo-url.txt')
$Ready=$false
for($Attempt=0;$Attempt -lt 60;$Attempt++){try{Invoke-RestMethod "http://127.0.0.1:$Port/api/health"|Out-Null;$Ready=$true;break}catch{Start-Sleep -Milliseconds 500}}
if(-not $Ready){throw 'Controller HTTP readiness timeout'}
Invoke-RestMethod -Method Post "http://127.0.0.1:$Port/cp/config/initialData" -Body @{gid=1810;language='en'} | Out-Null
Invoke-RestMethod -Method Post "http://127.0.0.1:$Port/cp/account/getUserInfo" -Body @{gid=1810;token='local-1810'} | Out-Null
Invoke-RestMethod -Method Post "http://127.0.0.1:$Port/cp/single_game.Game/initRoom" -Body @{gid=1810;token='local-1810'} | Out-Null
Invoke-RestMethod -Method Post "http://127.0.0.1:$Port/api/spin" -Body @{gid=1810;token='local-1810';bet_gold='0.05';level='10'} | Out-Null
Write-Host "PAGE $Url"
