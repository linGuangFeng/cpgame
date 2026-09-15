[CmdletBinding()]
param(
    [Nullable[long]]$Seed = $null,
    [switch]$ResetState,
    [string]$StateFile = ""
)

$ErrorActionPreference = 'Stop'
$reportRoot = 'D:\work\hd\cpgame\reports\1407-Coin-Master-GO'
$runtimeRoot = Join-Path $reportRoot 'runtime'
$lastRunPath = Join-Path $runtimeRoot 'restart-demo-last-run.json'
$publishRoot = 'D:\work\hd\cpgame\publish\1407-Coin-Master-GO'
$apiRoot = 'D:\work\hd\cpgame\server-api\1407-Coin-Master-GO'
$apiJar = Join-Path $apiRoot 'target\coin-master-go-server-api-1.0.0.jar'
$demoUrlPath = Join-Path $apiRoot 'demo-url.txt'
$staticScript = Join-Path $reportRoot 'tools\static-server.mjs'
$edge = 'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe'
$javaExe = (Get-Command 'java.exe' -ErrorAction Stop).Source
$nodeExe = (Get-Command 'node.exe' -ErrorAction Stop).Source
New-Item -ItemType Directory -Path $runtimeRoot -Force | Out-Null
if ([string]::IsNullOrWhiteSpace($StateFile)) { $StateFile = Join-Path $runtimeRoot 'demo-state.json' }
$StateFile = [IO.Path]::GetFullPath($StateFile)

function Stop-OwnedProcess([string]$name, [string]$kind, [string]$expectedTarget) {
    $metaPath = Join-Path $runtimeRoot "$name.process.json"
    if (-not (Test-Path -LiteralPath $metaPath)) { return }
    $meta = Get-Content -Raw -LiteralPath $metaPath | ConvertFrom-Json
    $process = Get-Process -Id ([int]$meta.pid) -ErrorAction SilentlyContinue
    if ($null -eq $process) {
        Remove-Item -LiteralPath $metaPath -Force -ErrorAction SilentlyContinue
        return
    }
    $recordedStart = if ($null -eq $meta.startTimeUtc) {
        $null
    } elseif ($meta.startTimeUtc -is [DateTime]) {
        ([DateTime]$meta.startTimeUtc).ToUniversalTime()
    } else {
        [DateTimeOffset]::Parse([string]$meta.startTimeUtc).UtcDateTime
    }
    $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $($process.Id)" -ErrorAction Stop
    $actualExecutableValue = if (-not [string]::IsNullOrWhiteSpace([string]$process.Path)) { [string]$process.Path } else { [string]$processInfo.ExecutablePath }
    $actualExecutable = [IO.Path]::GetFullPath($actualExecutableValue)
    $recordedExecutable = if ($null -ne $meta.executablePath) { [IO.Path]::GetFullPath([string]$meta.executablePath) } else { $actualExecutable }
    $metadataIdentity = ($null -eq $meta.gameId -or [int]$meta.gameId -eq 1407) -and
        ($null -eq $meta.directoryName -or [string]$meta.directoryName -eq '1407-Coin-Master-GO') -and
        ($null -eq $meta.kind -or [string]$meta.kind -eq $kind)
    $targetIdentity = if ($kind -eq 'edge') {
        [string]$processInfo.CommandLine -match '--remote-debugging-port=9237'
    } else {
        [string]$processInfo.CommandLine -like "*$expectedTarget*"
    }
    $sameOwnedProcess = $null -ne $recordedStart -and
        [Math]::Abs(($process.StartTime.ToUniversalTime() - $recordedStart).TotalSeconds) -lt 2 -and
        $actualExecutable -eq $recordedExecutable -and $metadataIdentity -and $targetIdentity
    if (-not $sameOwnedProcess) {
        throw "Refusing to stop PID $($process.Id): ownership identity does not match game 1407 $kind target $expectedTarget"
    }
    Stop-Process -Id $process.Id -Force -ErrorAction Stop
    $process.WaitForExit()
    Remove-Item -LiteralPath $metaPath -Force -ErrorAction SilentlyContinue
}

function Save-OwnedProcess([string]$name, $process, [string]$kind, [string]$targetPath, [string]$executablePath) {
    $meta = [ordered]@{
        schemaVersion = 2
        gameId = 1407
        directoryName = '1407-Coin-Master-GO'
        kind = $kind
        pid = $process.Id
        startTimeUtc = $process.StartTime.ToUniversalTime().ToString('o')
        executablePath = [IO.Path]::GetFullPath($executablePath)
        targetPath = $targetPath
    }
    $meta | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $runtimeRoot "$name.process.json") -Encoding utf8
}

function Invoke-DemoPost([string]$path, [hashtable]$fields) {
    $body = ($fields.GetEnumerator() | ForEach-Object { '{0}={1}' -f [Uri]::EscapeDataString([string]$_.Key), [Uri]::EscapeDataString([string]$_.Value) }) -join '&'
    $response = Invoke-WebRequest -UseBasicParsing -Method Post -ContentType 'application/x-www-form-urlencoded;charset=UTF-8' -Body $body -Uri "http://127.0.0.1:9500$path" -TimeoutSec 10
    $json = $response.Content | ConvertFrom-Json
    if ($response.StatusCode -ne 200 -or [int]$json.code -ne 200) { throw "Demo smoke request failed: path=$path http=$($response.StatusCode) code=$($json.code)" }
    return $json
}

Stop-OwnedProcess 'api' 'java-demo' $apiJar
Stop-OwnedProcess 'static' 'static-server' $staticScript
Stop-OwnedProcess 'edge' 'edge' '--remote-debugging-port=9237'
if ($ResetState -and (Test-Path -LiteralPath $StateFile)) { Remove-Item -LiteralPath $StateFile -Force }

$staticOut = Join-Path $runtimeRoot 'static.out.log'
$staticErr = Join-Path $runtimeRoot 'static.err.log'
$staticProcess = Start-Process -FilePath $nodeExe -ArgumentList @($staticScript, $publishRoot, '8147', '0.0.0.0') -WorkingDirectory $reportRoot -WindowStyle Hidden -RedirectStandardOutput $staticOut -RedirectStandardError $staticErr -PassThru
Save-OwnedProcess 'static' $staticProcess 'static-server' $staticScript $nodeExe

$apiOut = Join-Path $runtimeRoot 'api.out.log'
$apiErr = Join-Path $runtimeRoot 'api.err.log'
$apiArgs = @('-jar', $apiJar, "--coin-master.state-file=$StateFile")
if ($null -ne $Seed) { $apiArgs += "--coin-master.demo-seed=$Seed" }
$apiProcess = Start-Process -FilePath $javaExe -ArgumentList $apiArgs -WorkingDirectory $apiRoot -WindowStyle Hidden -RedirectStandardOutput $apiOut -RedirectStandardError $apiErr -PassThru
Save-OwnedProcess 'api' $apiProcess 'java-demo' $apiJar $javaExe

$browserAlreadyReady = $false
try {
    $browserAlreadyReady = (Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:9237/json/version' -TimeoutSec 2).StatusCode -eq 200
} catch { $browserAlreadyReady = $false }

if (-not $browserAlreadyReady) {
    $edgeProfile = Join-Path ([IO.Path]::GetTempPath()) 'cpgame-1407-coin-master-go-edge-profile'
    if (Test-Path -LiteralPath $edgeProfile) { Remove-Item -LiteralPath $edgeProfile -Recurse -Force }
    $edgeArgs = @('--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check', '--remote-debugging-port=9237', "--user-data-dir=$edgeProfile", 'about:blank')
    $edgeProcess = Start-Process -FilePath $edge -ArgumentList $edgeArgs -WindowStyle Hidden -PassThru
    Save-OwnedProcess 'edge' $edgeProcess 'edge' '--remote-debugging-port=9237' $edge
}

$deadline = [DateTime]::UtcNow.AddSeconds(45)
do {
    try {
        $staticReady = (Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:8147/index.html' -TimeoutSec 2).StatusCode -eq 200
    } catch { $staticReady = $false }
    try {
        $body = 't=restart-health&gid=55&ai=demo&btt=1'
        $apiReady = (Invoke-WebRequest -UseBasicParsing -Method Post -ContentType 'application/x-www-form-urlencoded' -Body $body -Uri 'http://127.0.0.1:9500/cp/api/v1/auth/verify' -TimeoutSec 2).StatusCode -eq 200
    } catch { $apiReady = $false }
    try {
        $browserReady = (Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:9237/json/version' -TimeoutSec 2).StatusCode -eq 200
    } catch { $browserReady = $false }
    if (-not ($staticReady -and $apiReady -and $browserReady)) { Start-Sleep -Milliseconds 250 }
} while (-not ($staticReady -and $apiReady -and $browserReady) -and [DateTime]::UtcNow -lt $deadline)

if (-not ($staticReady -and $apiReady -and $browserReady)) { throw "Demo readiness failed: static=$staticReady api=$apiReady browser=$browserReady" }

$smokeLaunchToken = 'restart-demo-' + [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$init = Invoke-DemoPost '/cp/api/v1/auth/verify' @{ t = $smokeLaunchToken; gid = '55'; ai = 'restart-demo'; btt = '1' }
$sessionToken = [string]$init.data.token
if ([string]::IsNullOrWhiteSpace($sessionToken)) { throw 'Init smoke response did not contain data.token' }
$config = Invoke-DemoPost '/cp/api/v1/go-master/config' @{ t = $sessionToken; gid = '55' }
$spin = Invoke-DemoPost '/cp/api/v1/go-master/spin' @{ t = $sessionToken; gid = '55'; bl = '1'; bs = '0.02' }
if ([double]$spin.data.ba -ne 0.4) {
    throw "Minimum Spin smoke mismatch: request bl=1 bs=0.02 must produce response ba=0.4, actual ba=$($spin.data.ba)"
}
if (-not (Test-Path -LiteralPath $demoUrlPath)) { throw "Demo URL file is missing: $demoUrlPath" }
$demoUrl = (Get-Content -LiteralPath $demoUrlPath -Raw).Trim()
if ($demoUrl -notmatch '^http://127\.0\.0\.1:8147/') { throw "Demo URL is not the local publish page: $demoUrl" }

Write-Output "READY static=http://127.0.0.1:8147 api=http://127.0.0.1:9500 browser-cdp=http://127.0.0.1:9237 state=$StateFile seed=$Seed"
Write-Output "INIT=PASS tokenSource=auth.verify"
Write-Output "CONFIG=PASS gid=55"
Write-Output "MINIMUM_SPIN=PASS bl=1 bs=0.02 ba=0.4"
Write-Output "PAGE=$demoUrl"

$apiMeta = Get-Content -LiteralPath (Join-Path $runtimeRoot 'api.process.json') -Raw | ConvertFrom-Json
$lastRun = [ordered]@{
    schemaVersion = 1
    gameId = 1407
    directoryName = '1407-Coin-Master-GO'
    generatedAt = [DateTime]::UtcNow.ToString('o')
    apiPid = [int]$apiMeta.pid
    stateFile = $StateFile
    readiness = [ordered]@{ static = $staticReady; api = $apiReady; browser = $browserReady }
    init = [ordered]@{ status = 'PASS'; endpoint = '/cp/api/v1/auth/verify'; tokenPresent = $true }
    config = [ordered]@{ status = 'PASS'; endpoint = '/cp/api/v1/go-master/config'; gid = 55 }
    minimumSpin = [ordered]@{ status = 'PASS'; endpoint = '/cp/api/v1/go-master/spin'; requestBl = 1; requestBs = 0.02; responseBa = [double]$spin.data.ba }
    demoUrlSource = $demoUrlPath
    demoUrl = $demoUrl
    result = 'PASS'
}
$lastRunJson = ($lastRun | ConvertTo-Json -Depth 8) + "`n"
[IO.File]::WriteAllText($lastRunPath, $lastRunJson, [Text.UTF8Encoding]::new($false))
