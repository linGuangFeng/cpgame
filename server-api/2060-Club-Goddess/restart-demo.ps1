param(
    [int]$WaitSeconds = 30,
    [switch]$ValidatePidOnly,
    [switch]$ValidateFreshStartOnly,
    [int]$ExpectedProcessId = 0,
    [string]$ExpectedJar
)

$ErrorActionPreference = 'Stop'
$project = $PSScriptRoot
$dist = Join-Path $project 'dist'
$distScript = Join-Path $dist 'restart-demo.ps1'
$jar = if ([string]::IsNullOrWhiteSpace($ExpectedJar)) {
    Join-Path $dist 'club-goddess-api.jar'
} else {
    [IO.Path]::GetFullPath($ExpectedJar)
}
$config = Join-Path $dist 'application.properties'
$pidFile = Join-Path $dist 'demo-api.pid'
$demoUrlFile = Join-Path $project 'demo-url.txt'
$apiBase = 'http://127.0.0.1:20600'
$signatureSecret = '3fZ8kL2qW9xA4pT7vJ1rQ6yB0sN5mX8h'

if (-not (Test-Path -LiteralPath $distScript) -or
    -not (Test-Path -LiteralPath $jar) -or
    -not (Test-Path -LiteralPath $config) -or
    -not (Test-Path -LiteralPath $demoUrlFile)) {
    exit 2
}

function Get-DemoProcessInfo {
    param([int]$ProcessId)
    return Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
}

function Test-ExpectedDemoProcess {
    param($ProcessInfo, [string]$ExpectedJar)
    if ($null -eq $ProcessInfo) { return $false }
    $isJava = $ProcessInfo.ProcessName -ieq 'java' -or $ProcessInfo.ProcessName -ieq 'javaw'
    if (-not $isJava -or [string]::IsNullOrWhiteSpace([string]$ProcessInfo.Path)) { return $false }
    $jcmd = Join-Path (Split-Path -Parent $ProcessInfo.Path) 'jcmd.exe'
    if (-not (Test-Path -LiteralPath $jcmd)) { return $false }
    $jcmdOutput = & $jcmd $ProcessInfo.Id 'VM.command_line' 2>$null
    if ($LASTEXITCODE -ne 0) { return $false }
    $commandLine = ($jcmdOutput -join "`n")
    $fullJar = [System.IO.Path]::GetFullPath($ExpectedJar)
    $hasJar = $commandLine.IndexOf($fullJar, [System.StringComparison]::OrdinalIgnoreCase) -ge 0
    return $isJava -and $hasJar
}

function Get-DemoListeners {
    $listeners = @()
    foreach ($line in @(& netstat.exe -ano -p tcp)) {
        if ($line -match '^\s*TCP\s+(\S+):20600\s+\S+\s+LISTENING\s+(\d+)\s*$') {
            $listeners += [PSCustomObject]@{
                LocalAddress = [string]$Matches[1]
                ProcessId = [int]$Matches[2]
            }
        }
    }
    return @($listeners)
}

function Test-DemoListenerOwner {
    param([int]$ProcessId, [object[]]$Listeners)
    $ownsAllInterfaces = @($Listeners | Where-Object {
        $_.LocalAddress -eq '0.0.0.0' -and $_.ProcessId -eq $ProcessId
    }).Count -gt 0
    $foreignOwners = @($Listeners | Where-Object { $_.ProcessId -ne $ProcessId }).Count
    return $ownsAllInterfaces -and $foreignOwners -eq 0
}

function Get-Md5Hex {
    param([string]$Value)
    $md5 = [System.Security.Cryptography.MD5]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Value)
        $hash = $md5.ComputeHash($bytes)
        return (($hash | ForEach-Object { $_.ToString('x2') }) -join '')
    } finally {
        $md5.Dispose()
    }
}

function Invoke-SignedPost {
    param([string]$Path, [hashtable]$Parameters)
    $pairs = @($Parameters.GetEnumerator() | Sort-Object Key)
    $canonical = (($pairs | ForEach-Object { "{0}={1}" -f $_.Key, $_.Value }) -join ':')
    $expire = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds().ToString()
    $sign = Get-Md5Hex ($signatureSecret + $expire + $canonical + 'aptsignature')
    $form = @{}
    foreach ($pair in $pairs) { $form[[string]$pair.Key] = [string]$pair.Value }
    $form['expire'] = $expire
    $form['signapt'] = $sign
    $response = Invoke-WebRequest -UseBasicParsing -Uri ($apiBase + $Path) -Method Post `
        -ContentType 'application/x-www-form-urlencoded' -Body $form -TimeoutSec 10
    if ($response.StatusCode -ne 200) { throw "POST $Path returned HTTP $($response.StatusCode)" }
    $document = $response.Content | ConvertFrom-Json
    if ([int]$document.code -ne 0) { throw "POST $Path returned code $($document.code)" }
    return $document
}

$pidFileExists = Test-Path -LiteralPath $pidFile
$listeners = @(Get-DemoListeners)
$oldInfo = $null
if ($pidFileExists) {
    $oldProcessId = 0
    $oldText = (Get-Content -LiteralPath $pidFile -Raw).Trim()
    if (-not [int]::TryParse($oldText, [ref]$oldProcessId)) { exit 3 }
    if ($ExpectedProcessId -gt 0 -and $oldProcessId -ne $ExpectedProcessId) { exit 3 }
    $oldInfo = Get-DemoProcessInfo -ProcessId $oldProcessId
    if ($null -eq $oldInfo) { exit 11 }
    if (-not (Test-ExpectedDemoProcess -ProcessInfo $oldInfo -ExpectedJar $jar)) { exit 4 }
    if (-not (Test-DemoListenerOwner -ProcessId $oldProcessId -Listeners $listeners)) { exit 12 }
} else {
    if ($ValidatePidOnly -or $ExpectedProcessId -gt 0) { exit 10 }
    if ($listeners.Count -gt 0) { exit 13 }
}

if ($ValidateFreshStartOnly) {
    if ($pidFileExists) { exit 14 }
    Write-Output 'FRESH_START_VALIDATION=PASS; PORT_20600=IDLE'
    exit 0
}

if ($ValidatePidOnly) {
    Write-Output 'PID_VALIDATION=PASS; ACTION=NONE'
    exit 0
}

$windowsPowerShell = (Get-Command 'powershell.exe' -ErrorAction Stop).Source
& $windowsPowerShell -NoProfile -ExecutionPolicy Bypass -File $distScript -WaitSeconds $WaitSeconds
$restartExitCode = $LASTEXITCODE
if ($restartExitCode -ne 0) { exit $restartExitCode }

if (-not (Test-Path -LiteralPath $pidFile)) { exit 5 }
$newProcessId = 0
$newText = (Get-Content -LiteralPath $pidFile -Raw).Trim()
if (-not [int]::TryParse($newText, [ref]$newProcessId)) { exit 6 }
$newInfo = Get-DemoProcessInfo -ProcessId $newProcessId
if (-not (Test-ExpectedDemoProcess -ProcessInfo $newInfo -ExpectedJar $jar)) { exit 7 }

$configuredAddress = Get-Content -LiteralPath $config | Where-Object { $_ -match '^server\.address=' } | Select-Object -First 1
if ($configuredAddress -ne 'server.address=0.0.0.0') { exit 8 }
$netstatLines = @(& netstat.exe -ano -p tcp)
$listenerPattern = '^\s*TCP\s+0\.0\.0\.0:20600\s+0\.0\.0\.0:0\s+LISTENING\s+' + $newProcessId + '\s*$'
if (@($netstatLines | Where-Object { $_ -match $listenerPattern }).Count -eq 0) { exit 9 }

$deadline = [DateTime]::UtcNow.AddSeconds($WaitSeconds)
$httpReady = $false
do {
    try {
        $page = Invoke-WebRequest -UseBasicParsing -Uri ($apiBase + '/') -TimeoutSec 2
        $asset = Invoke-WebRequest -UseBasicParsing -Uri ($apiBase + '/versionconfig.js') -TimeoutSec 2
        $httpReady = $page.StatusCode -eq 200 -and $asset.StatusCode -eq 200
    } catch {
        $httpReady = $false
    }
    if (-not $httpReady) { Start-Sleep -Milliseconds 250 }
} while (-not $httpReady -and [DateTime]::UtcNow -lt $deadline)
if (-not $httpReady) { exit 10 }

$ipv4Candidates = @()
foreach ($line in @(& ipconfig.exe)) {
    if ($line -match 'IPv4[^:]*:\s*(?<ip>(?:\d{1,3}\.){3}\d{1,3})\s*$') {
        $ipv4Candidates += $Matches['ip']
    }
}
$lanIp = $ipv4Candidates | Where-Object {
    $_ -match '^10\.' -or $_ -match '^192\.168\.' -or $_ -match '^172\.(1[6-9]|2\d|3[01])\.'
} | Select-Object -First 1
if ([string]::IsNullOrWhiteSpace([string]$lanIp)) {
    $lanIp = $ipv4Candidates | Where-Object { $_ -ne '127.0.0.1' -and $_ -notmatch '^169\.254\.' } | Select-Object -First 1
}
if ([string]::IsNullOrWhiteSpace([string]$lanIp)) { exit 11 }
$lanBase = "http://${lanIp}:20600"
$lanPage = Invoke-WebRequest -UseBasicParsing -Uri ($lanBase + '/') -TimeoutSec 5
$lanAsset = Invoke-WebRequest -UseBasicParsing -Uri ($lanBase + '/versionconfig.js') -TimeoutSec 5
if ($lanPage.StatusCode -ne 200 -or $lanAsset.StatusCode -ne 200) { exit 12 }

$configResult = Invoke-SignedPost -Path '/cp/config/initialData' -Parameters @{
    gid = '2060'; language = 'en-us'
}
if ([int]$configResult.data.game_info.gid -ne 2060) { exit 13 }
$launchToken = 'launcher-smoke-' + [Guid]::NewGuid().ToString()
$userResult = Invoke-SignedPost -Path '/cp/account/getUserInfo' -Parameters @{
    gid = '2060'; token = $launchToken; language = 'en-us'
}
$sessionToken = [string]$userResult.data.token
if ([string]::IsNullOrWhiteSpace($sessionToken)) { exit 14 }
$initResult = Invoke-SignedPost -Path '/cp/single_game.Game/initRoom' -Parameters @{
    gid = '2060'; token = $sessionToken
}
if (@($initResult.data.props.prop).Count -ne 15) { exit 15 }
$spinResult = Invoke-SignedPost -Path '/cp/single_game.Game/gameResult' -Parameters @{
    gid = '2060'; token = $sessionToken; bet_gold = '0.01'; level = '10'; act_id = '0';
    request_id = ('launcher-spin-' + [Guid]::NewGuid().ToString())
}
if ([string]::IsNullOrWhiteSpace([string]$spinResult.data.oid) -or @($spinResult.data.props.prop).Count -ne 15) { exit 16 }

$demoUrl = (Get-Content -LiteralPath $demoUrlFile -Raw).Trim()
if ([string]::IsNullOrWhiteSpace($demoUrl)) { exit 17 }
$lanDemoUrl = $demoUrl -replace 'localhost:20600', "${lanIp}:20600"
Write-Output "PID_VALIDATED=$newProcessId"
Write-Output 'CONFIG_INIT_MINIMAL_SPIN=PASS'
Write-Output 'STATIC_BIND=0.0.0.0:20600'
Write-Output "LAN_STATIC_HTTP=PASS;$lanBase/"
Write-Output "DEMO_URL=$demoUrl"
Write-Output "LAN_DEMO_URL=$lanDemoUrl"
exit 0
