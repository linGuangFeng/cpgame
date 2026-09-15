$ErrorActionPreference='Stop'
$root=Split-Path -Parent $MyInvocation.MyCommand.Path
$jar=Join-Path $root 'dist\jurassic-jungle-server.jar'; $pidFile=Join-Path $root 'jurassic-jungle.pid'
if(Test-Path $pidFile){$oldPid=[int](Get-Content $pidFile);$process=Get-Process -Id $oldPid -ErrorAction SilentlyContinue;if($process -and $process.ProcessName -match 'java'){ $line=& jcmd $oldPid VM.command_line 2>$null;if(($line -join ' ').Contains('.jar')){Stop-Process -Id $oldPid -Force}}}
$args=@('-Dserver.address=0.0.0.0','-Dserver.port=18008','-Dpublish.dir=../../publish/8-Jurassic-Jungle','-jar',$jar);$p=Start-Process java -ArgumentList $args -WorkingDirectory $root -PassThru;Set-Content $pidFile $p.Id
$url=(Get-Content (Join-Path $root 'demo-url.txt')).Trim();for($i=0;$i -lt 40;$i++){try{Invoke-WebRequest ($url+'health') -UseBasicParsing|Out-Null;break}catch{Start-Sleep -Milliseconds 250}}
Invoke-WebRequest ($url+'api/init') -UseBasicParsing|Out-Null;Invoke-WebRequest ($url+'api/config') -UseBasicParsing|Out-Null;Invoke-WebRequest ($url+'api/spin') -Method Post -ContentType 'application/json' -Body '{"bet":0.20}' -UseBasicParsing|Out-Null
Write-Host "Demo ready (0.0.0.0 LAN): $url";Start-Process $url
