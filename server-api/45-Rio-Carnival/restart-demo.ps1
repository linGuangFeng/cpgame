param(
    [switch]$NoPause,
    [string]$PidFile,
    [string]$DemoUrlFile
)

Write-Error 'Legacy launcher retired. Use the platform controller v3 lifecycle with an injected 50000-59999 port. No process was stopped or started.'
exit 64

$ErrorActionPreference = 'Stop'
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$apiDist = Join-Path $scriptDir 'dist'
$jar = [IO.Path]::GetFullPath((Join-Path $apiDist 'rio-carnival-server.jar'))
$config = [IO.Path]::GetFullPath((Join-Path $apiDist 'application.properties'))
if ([string]::IsNullOrWhiteSpace($PidFile)) { $PidFile = Join-Path $scriptDir 'demo.pid' }
if ([string]::IsNullOrWhiteSpace($DemoUrlFile)) { $DemoUrlFile = Join-Path $scriptDir 'demo-url.txt' }
$PidFile = [IO.Path]::GetFullPath($PidFile)
$DemoUrlFile = [IO.Path]::GetFullPath($DemoUrlFile)
$tempDir = Join-Path $apiDist 'tmp'
$exitCode = 0
$startedProcess = $null
$zhSuccess = "$([char]0x6210)$([char]0x529F)"
$zhFailure = "$([char]0x5931)$([char]0x8D25)"
$zhNotice = "$([char]0x63D0)$([char]0x793A)"
$zhSelfCheck = "$([char]0x81EA)$([char]0x68C0)"
$zhDemoUrl = "$([char]0x8BD5)$([char]0x73A9)$([char]0x5730)$([char]0x5740)"

function Get-JavaCommandLine([int]$ProcessId) {
    $process = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if ($null -eq $process -or $process.ProcessName -notmatch '^javaw?$' -or [string]::IsNullOrWhiteSpace($process.Path)) { return $null }
    $jcmd = Join-Path (Split-Path -Parent $process.Path) 'jcmd.exe'
    if (-not (Test-Path -LiteralPath $jcmd)) { return $null }
    $output = @(& $jcmd $ProcessId VM.command_line 2>$null)
    if ($LASTEXITCODE -ne 0) { return $null }
    return ($output -join "`n")
}

function Test-RioCarnivalProcess([int]$ProcessId) {
    $process = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
    if ($null -eq $process -or $process.ProcessName -notmatch '^javaw?$') { return $false }
    $commandLine = Get-JavaCommandLine $ProcessId
    if ([string]::IsNullOrWhiteSpace($commandLine)) { return $false }
    return $commandLine.IndexOf("java_command: $jar", [StringComparison]::OrdinalIgnoreCase) -ge 0 `
        -and $commandLine.IndexOf("java_class_path (initial): $jar", [StringComparison]::OrdinalIgnoreCase) -ge 0 `
        -and $commandLine.IndexOf($config, [StringComparison]::OrdinalIgnoreCase) -ge 0
}

function Get-ListenerLines([int]$Port) {
    $pattern = ":$Port\s+.*LISTENING\s+(\d+)\s*$"
    return @(& "$env:SystemRoot\System32\netstat.exe" -ano -p tcp | Where-Object { $_ -match $pattern })
}

function Get-ListeningPids([int]$Port) {
    $ids = @()
    foreach ($line in @(Get-ListenerLines $Port)) {
        if ($line -match 'LISTENING\s+(\d+)\s*$') { $ids += [int]$Matches[1] }
    }
    return @($ids | Select-Object -Unique)
}

function Get-NonLoopbackIpv4Candidates {
    $addresses = @()
    foreach ($networkInterface in [Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces()) {
        if ($networkInterface.OperationalStatus -ne [Net.NetworkInformation.OperationalStatus]::Up) { continue }
        foreach ($unicast in $networkInterface.GetIPProperties().UnicastAddresses) {
            if ($unicast.Address.AddressFamily -ne [Net.Sockets.AddressFamily]::InterNetwork) { continue }
            $address = $unicast.Address.ToString()
            if ($address -eq '127.0.0.1' -or $address.StartsWith('169.254.')) { continue }
            $addresses += $address
        }
    }
    return @($addresses | Sort-Object -Unique)
}

function Invoke-FormJson([string]$BaseUrl, [string]$Path, [hashtable]$Fields, [hashtable]$Headers = @{}) {
    $parts = foreach ($entry in $Fields.GetEnumerator()) {
        [Uri]::EscapeDataString([string]$entry.Key) + '=' + [Uri]::EscapeDataString([string]$entry.Value)
    }
    $response = Invoke-WebRequest -UseBasicParsing -Method Post -Uri ($BaseUrl + $Path) `
        -ContentType 'application/x-www-form-urlencoded' -Body ($parts -join '&') -Headers $Headers -TimeoutSec 5
    if ($response.StatusCode -ne 200) { throw "$Path returned HTTP $($response.StatusCode)." }
    return $response.Content | ConvertFrom-Json
}

try {
    if (-not (Test-Path -LiteralPath $jar)) { throw "Java API JAR not found: $jar" }
    if (-not (Test-Path -LiteralPath $config)) { throw "Production config not found: $config" }
    if (-not (Test-Path -LiteralPath $DemoUrlFile)) { throw "demo-url.txt not found: $DemoUrlFile" }

    $demoRaw = (Get-Content -Raw -LiteralPath $DemoUrlFile).Trim()
    $demoLines = @($demoRaw -split "\r?\n" | Where-Object { $_.Trim().Length -gt 0 })
    if ($demoLines.Count -ne 1) { throw 'demo-url.txt must contain exactly one non-empty line.' }
    $demoUrl = $demoLines[0].Trim()
    try { $demoUri = [Uri]$demoUrl } catch { throw 'demo-url.txt is not a valid URI.' }
    if ($demoUri.Scheme -ne 'http' -or -not $demoUri.IsLoopback -or $demoUri.Port -ne 19545 -or $demoUri.AbsolutePath -ne '/demo') {
        throw 'demo-url.txt must contain one local HTTP /demo address on port 19545.'
    }

    New-Item -ItemType Directory -Path $tempDir -Force | Out-Null
    if (Test-Path -LiteralPath $PidFile) {
        $oldPidValue = 0
        $oldPidText = (Get-Content -Raw -LiteralPath $PidFile).Trim()
        if ([int]::TryParse($oldPidText, [ref]$oldPidValue) -and $oldPidValue -gt 0) {
            $oldProcess = Get-Process -Id $oldPidValue -ErrorAction SilentlyContinue
            if ($null -ne $oldProcess) {
                if (Test-RioCarnivalProcess $oldPidValue) {
                    Stop-Process -Id $oldPidValue -Force -ErrorAction Stop
                    if (-not $oldProcess.WaitForExit(10000)) { throw "Timed out stopping Rio Carnival PID $oldPidValue." }
                    Write-Host "[$zhNotice] Verified and stopped previous Rio Carnival PID $oldPidValue."
                } else {
                    Write-Host "[$zhNotice] Stale PID $oldPidValue belongs to another process; it was not stopped." -ForegroundColor Yellow
                }
            }
        } else {
            Write-Host "[$zhNotice] Invalid stale demo.pid was ignored; no process was stopped." -ForegroundColor Yellow
        }
    }

    $busyPids = @(Get-ListeningPids 19545)
    if ($busyPids.Count -gt 0) { throw "Port 19545 is already owned by untracked PID(s): $($busyPids -join ',')." }

    $java = (Get-Command 'java' -ErrorAction Stop).Source
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $java
    $startInfo.Arguments = "`"-Djava.io.tmpdir=$tempDir`" -jar `"$jar`" `"--spring.config.additional-location=file:$config`""
    $startInfo.WorkingDirectory = $apiDist
    $startInfo.UseShellExecute = $true
    $startInfo.WindowStyle = [System.Diagnostics.ProcessWindowStyle]::Hidden
    $startedProcess = [System.Diagnostics.Process]::Start($startInfo)
    Set-Content -LiteralPath $PidFile -Value $startedProcess.Id -Encoding ascii

    $startup = $null
    for ($i = 0; $i -lt 80; $i++) {
        Start-Sleep -Milliseconds 250
        if ($startedProcess.HasExited) { break }
        try {
            $readyResponse = Invoke-WebRequest -UseBasicParsing -Uri 'http://localhost:19545/api/game/startup?game_id=1401' -TimeoutSec 2
            $readyJson = $readyResponse.Content | ConvertFrom-Json
            if ($readyResponse.StatusCode -eq 200 -and [string]$readyJson.code -eq '200') { $startup = $readyJson; break }
        } catch { }
    }
    if ($null -eq $startup) { throw 'Java API did not become HTTP-ready on port 19545.' }
    if (-not (Test-RioCarnivalProcess $startedProcess.Id)) {
        $observedCommandLine = Get-JavaCommandLine $startedProcess.Id
        throw "Started PID does not belong to the expected Rio Carnival JAR. Expected=$jar Observed=$observedCommandLine"
    }
    $listenerLines = @(Get-ListenerLines 19545)
    $listenerPids = @(Get-ListeningPids 19545)
    if ($listenerPids -notcontains $startedProcess.Id) { throw 'HTTP listener PID does not match the newly started Java process.' }
    if (-not ($listenerLines | Where-Object { $_ -match '0\.0\.0\.0:19545|\[::\]:19545' })) { throw 'Java API is not listening on all interfaces.' }

    $page = Invoke-WebRequest -UseBasicParsing -Uri $demoUrl -TimeoutSec 8
    if ($page.StatusCode -ne 200 -or $page.Content -notmatch 'GameCanvas') { throw 'Local demo page readiness check failed.' }

    $lanAddress = $null
    $lanPage = $null
    foreach ($candidate in @(Get-NonLoopbackIpv4Candidates)) {
        try {
            $candidatePage = Invoke-WebRequest -UseBasicParsing -Uri ("http://${candidate}:19545/demo?l=en") -TimeoutSec 5
            if ($candidatePage.StatusCode -eq 200 -and $candidatePage.Content -match 'GameCanvas') {
                $lanAddress = $candidate
                $lanPage = $candidatePage
                break
            }
        } catch { }
    }
    if ([string]::IsNullOrWhiteSpace($lanAddress)) { throw 'Demo page is not reachable through any non-loopback IPv4 address.' }
    $lanBase = "http://${lanAddress}:19545"

    $scriptAsset = Invoke-WebRequest -UseBasicParsing -Uri ($lanBase + '/45/assets/main/index.76d7e.js') -TimeoutSec 8
    if ($scriptAsset.StatusCode -ne 200 -or $scriptAsset.RawContentLength -lt 1024) { throw 'LAN JavaScript static asset check failed.' }
    $fontAsset = Invoke-WebRequest -UseBasicParsing -Uri ($lanBase + '/45/assets/resources/native/b3/b349a2ad-736f-4ab9-8228-145936dcbe1b.60a6c/FORTE.ttf') -TimeoutSec 8
    if ($fontAsset.StatusCode -ne 200 -or $fontAsset.RawContentLength -ne 61776) { throw 'LAN font static asset check failed.' }

    $lanStartupResponse = Invoke-WebRequest -UseBasicParsing -Uri ($lanBase + '/api/game/startup?game_id=1401') -TimeoutSec 5
    $lanStartup = $lanStartupResponse.Content | ConvertFrom-Json
    if ($lanStartupResponse.StatusCode -ne 200 -or [string]$lanStartup.code -ne '200') { throw 'LAN Startup verification failed.' }
    $gameUrl = [string]$lanStartup.data.game_url
    try { $gameUri = [Uri]$gameUrl } catch { throw 'LAN Startup returned an invalid game_url.' }
    if ($gameUri.Host -ne $lanAddress -or $gameUri.Port -ne 19545) { throw 'LAN Startup did not rewrite game_url to the visitor Host.' }
    if ($gameUrl -notmatch '[?&]sip=([^&]+)' -or [Net.WebUtility]::UrlDecode($Matches[1]) -ne "${lanAddress}:19545") {
        throw 'LAN Startup did not rewrite sip to the visitor Host.'
    }
    if ($gameUrl -notmatch '[?&]t=([^&]+)') { throw 'Startup response does not contain launch token.' }
    $launchToken = [Net.WebUtility]::UrlDecode($Matches[1])
    $init = Invoke-FormJson $lanBase '/cp/api/v1/auth/verify' @{ai='local'; btt=1; t=$launchToken; gid=45}
    if ([string]$init.code -ne '200' -or [string]::IsNullOrWhiteSpace([string]$init.data.token)) { throw 'Init/auth verification failed.' }
    $providerToken = [string]$init.data.token
    $configJson = Invoke-FormJson $lanBase '/cp/api/v1/rio-carnival/config' @{t=$providerToken; gid=45}
    if ([string]$configJson.code -ne '200' -or @($configJson.data.bsl) -notcontains 0.02 -or @($configJson.data.bll) -notcontains 1) {
        throw 'Config verification failed.'
    }
    $spinKey = 'launcher-selfcheck-' + [Guid]::NewGuid().ToString('N')
    $spin = Invoke-FormJson $lanBase '/cp/api/v1/rio-carnival/spin' @{bl=1; bs='0.02'; t=$providerToken; gid=45} @{'Idempotency-Key'=$spinKey}
    if ([string]$spin.code -ne '200' -or [decimal]$spin.data.ba -ne [decimal]0.5 -or @($spin.data.rskl).Count -ne 15) {
        throw 'Minimum Spin verification failed.'
    }

    Write-Host "[$zhSuccess] Rio Carnival Java Demo restarted safely. PID=$($startedProcess.Id)" -ForegroundColor Green
    Write-Host "[$zhSuccess] 0.0.0.0 listener / LAN page / static assets / Host rewrite / Init / Config / minimum Spin $zhSelfCheck PASS. LAN=$lanAddress" -ForegroundColor Green
    Write-Host "${zhDemoUrl}: $demoUrl"
} catch {
    $exitCode = 1
    if ($null -ne $startedProcess -and -not $startedProcess.HasExited -and (Test-RioCarnivalProcess $startedProcess.Id)) {
        Stop-Process -Id $startedProcess.Id -Force -ErrorAction SilentlyContinue
        $startedProcess.WaitForExit(5000) | Out-Null
    }
    if ($null -ne $startedProcess -and (Test-Path -LiteralPath $PidFile)) {
        $recordedPid = (Get-Content -Raw -LiteralPath $PidFile).Trim()
        if ($recordedPid -eq [string]$startedProcess.Id) { Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue }
    }
    Write-Host "[$zhFailure] $($_.Exception.Message)" -ForegroundColor Red
}

if (-not $NoPause) { Read-Host 'Press Enter to close this window' | Out-Null }
exit $exitCode
