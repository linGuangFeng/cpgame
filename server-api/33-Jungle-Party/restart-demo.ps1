param(
    [ValidateRange(0, 59999)]
    [int]$Port = 0,
    [switch]$NoPause
)
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $MyInvocation.MyCommand.Path
$jar = Join-Path $project 'dist\controller.jar'
$config = Join-Path $project 'dist\controller.properties'
$publish = Join-Path (Split-Path -Parent (Split-Path -Parent $project)) 'publish\33-Jungle-Party'
$pidFile = Join-Path $project 'demo-controller.pid'
$urlFile = Join-Path $project 'demo-url.txt'

if (-not (Test-Path -LiteralPath $jar -PathType Leaf)) { throw "Missing controller JAR: $jar" }
if (-not (Test-Path -LiteralPath $config -PathType Leaf)) { throw "Missing controller config: $config" }
if (-not (Test-Path -LiteralPath (Join-Path $publish 'index.html') -PathType Leaf)) { throw "Missing publish entry: $publish" }

# Stop only a process proven to be this controller through Win32_Process.CommandLine ownership metadata.
if (Test-Path -LiteralPath $pidFile -PathType Leaf) {
    $oldPidText = (Get-Content -LiteralPath $pidFile -Raw).Trim()
    if ($oldPidText -match '^\d+$') {
        $owned = Get-CimInstance Win32_Process -Filter "ProcessId = $oldPidText" -ErrorAction SilentlyContinue
        if ($null -ne $owned -and $owned.CommandLine -like "*$jar*") {
            Stop-Process -Id ([int]$oldPidText) -Force -ErrorAction Stop
            Wait-Process -Id ([int]$oldPidText) -Timeout 10 -ErrorAction SilentlyContinue
        }
    }
    Remove-Item -LiteralPath $pidFile -Force
}

if ($Port -eq 0) {
    foreach ($candidate in (Get-Random -InputObject (50000..59999) -Count 200)) {
        if (-not (Get-NetTCPConnection -LocalPort $candidate -State Listen -ErrorAction SilentlyContinue)) { $Port = $candidate; break }
    }
}
if ($Port -lt 50000 -or $Port -gt 59999) { throw 'No free managed port in 50000-59999' }

$arguments = @('-Dfile.encoding=UTF-8', '-jar', $jar, '--port', [string]$Port, '--config', $config, '--publish', $publish)
$process = Start-Process -FilePath 'java' -ArgumentList $arguments -WorkingDirectory $project -WindowStyle Hidden -PassThru
Set-Content -LiteralPath $pidFile -Value ([string]$process.Id) -Encoding ascii
$base = "http://127.0.0.1:$Port"
try {
    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        try { $health = Invoke-RestMethod -Uri "$base/health" -Method Get -TimeoutSec 2; if ($health.status -eq 'UP') { $ready = $true; break } } catch { Start-Sleep -Milliseconds 250 }
    }
    if (-not $ready) { throw "Controller HTTP health check timed out on $base" }
    $token = "local-demo-33-$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())"
    $form = @{ gid = '33'; t = $token; bl = '1'; bs = '0.02' }
    $initResponse = Invoke-RestMethod -Uri "$base/cp/api/v1/auth/verify" -Method Post -Body $form -TimeoutSec 5
    $configResponse = Invoke-RestMethod -Uri "$base/cp/api/v1/jungle-party/config" -Method Post -Body $form -TimeoutSec 5
    $spinHeaders = @{ 'Idempotency-Key' = "restart-smoke-$token" }
    $spinResponse = Invoke-RestMethod -Uri "$base/cp/api/v1/jungle-party/spin" -Method Post -Body $form -Headers $spinHeaders -TimeoutSec 5
    if ($initResponse.code -ne 200 -or $configResponse.code -ne 200 -or $spinResponse.code -ne 200) { throw 'init/config/spin smoke validation failed' }
    $encodedSip = [uri]::EscapeDataString("127.0.0.1:$Port")
    $demoUrl = "$base/?gid=33&l=en&language=en&ai=local_33&btt=1&t=$token&sip=$encodedSip"
    Set-Content -LiteralPath $urlFile -Value $demoUrl -Encoding utf8NoBOM
    [pscustomobject]@{ status='PASS'; pid=$process.Id; port=$Port; listen='0.0.0.0'; demoUrl=$demoUrl; init='PASS'; config='PASS'; spin='PASS' } | ConvertTo-Json -Compress
} catch {
    $owned = Get-CimInstance Win32_Process -Filter "ProcessId = $($process.Id)" -ErrorAction SilentlyContinue
    if ($null -ne $owned -and $owned.CommandLine -like "*$jar*") { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }
    Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
    throw
}
