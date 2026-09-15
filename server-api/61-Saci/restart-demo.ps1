param(
    [switch]$ValidateOnly
)

$ErrorActionPreference = 'Stop'
$projectDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$distDirectory = Join-Path $projectDirectory 'dist'
$pidFile = Join-Path $distDirectory 'server.pid'
$logDirectory = Join-Path $projectDirectory 'logs'
$jarFile = Join-Path $distDirectory 'saci-server-api.jar'
$configFile = Join-Path $distDirectory 'server.properties'
$demoUrlFile = Join-Path $projectDirectory 'demo-url.txt'

foreach ($requiredFile in @($jarFile, $configFile, $demoUrlFile)) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        throw "Required Saci Demo file is missing: $requiredFile"
    }
}
$configText = Get-Content -LiteralPath $configFile -Raw
if ($configText -notmatch '(?m)^\s*bind\.address\s*=\s*0\.0\.0\.0\s*$') {
    throw 'Saci Demo must bind to all interfaces'
}
$demoUrlLines = @(Get-Content -LiteralPath $demoUrlFile | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
if ($demoUrlLines.Count -ne 1) {
    throw "$demoUrlFile must contain exactly one page address"
}
$configuredDemoUrl = $demoUrlLines[0].Trim()
$configuredDemoUri = [Uri]$configuredDemoUrl
if ($configuredDemoUri.Scheme -ne 'http' -or $configuredDemoUri.AbsolutePath -ne '/demo' -or
        $configuredDemoUri.Port -le 0 -or
        -not [string]::IsNullOrEmpty($configuredDemoUri.Query) -or
        -not [string]::IsNullOrEmpty($configuredDemoUri.Fragment)) {
    throw "Invalid canonical local HTTP page address in $demoUrlFile"
}
$baseUrl = 'http://192.168.10.3:19610'

if ($ValidateOnly) {
    if (-not (Test-Path -LiteralPath $pidFile -PathType Leaf)) {
        throw "Saci Demo PID file is missing: $pidFile"
    }
    $pidText = (Get-Content -LiteralPath $pidFile -Raw).Trim()
    $validatedPid = 0
    if (-not [int]::TryParse($pidText, [ref]$validatedPid) -or $validatedPid -le 0) {
        throw "Invalid PID file: $pidFile"
    }
    $validatedProcess = Get-Process -Id $validatedPid -ErrorAction SilentlyContinue
    if ($null -eq $validatedProcess -or $validatedProcess.ProcessName -ne 'java') {
        throw "PID $validatedPid is not a running Java process"
    }
    $validatedInfo = Get-CimInstance Win32_Process -Filter "ProcessId=$validatedPid" -ErrorAction Stop
    $validatedCommand = [string]$validatedInfo.CommandLine
    if ($validatedCommand.IndexOf($jarFile, [StringComparison]::OrdinalIgnoreCase) -lt 0 -or
            $validatedCommand.IndexOf($configFile, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
        throw "PID $validatedPid is not the current Saci Java Demo"
    }
    $validatedHealth = Invoke-RestMethod -Uri ($baseUrl + '/health') -TimeoutSec 5
    if ($validatedHealth.code -ne 200 -or $validatedHealth.data.status -ne 'UP') {
        throw 'Saci Demo health validation failed'
    }
    Write-Output "Saci Demo validation-only check passed. PID=$validatedPid Bind=0.0.0.0 Health=PASS"
    Write-Output "Open page from demo-url.txt: $configuredDemoUrl"
    return
}

if (Test-Path -LiteralPath $pidFile -PathType Leaf) {
    $pidText = (Get-Content -LiteralPath $pidFile -Raw).Trim()
    $oldPid = 0
    if (-not [int]::TryParse($pidText, [ref]$oldPid) -or $oldPid -le 0) {
        throw "Invalid PID file: $pidFile"
    }
    $oldProcess = Get-Process -Id $oldPid -ErrorAction SilentlyContinue
    if ($null -ne $oldProcess) {
        if ($oldProcess.ProcessName -ne 'java') {
            throw "PID $oldPid belongs to $($oldProcess.ProcessName), not the Saci Java Demo"
        }
        $oldInfo = Get-CimInstance Win32_Process -Filter "ProcessId=$oldPid" -ErrorAction Stop
        $oldCommand = [string]$oldInfo.CommandLine
        if ($oldCommand.IndexOf($jarFile, [StringComparison]::OrdinalIgnoreCase) -lt 0 -or
                $oldCommand.IndexOf($configFile, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
            throw "PID $oldPid is Java but its command line is not this Saci Demo"
        }
        Stop-Process -Id $oldPid -Force -ErrorAction Stop
        Wait-Process -Id $oldPid -Timeout 10 -ErrorAction SilentlyContinue
    }
}

New-Item -ItemType Directory -Path $logDirectory -Force | Out-Null
$stdout = Join-Path $logDirectory 'demo.out.log'
$stderr = Join-Path $logDirectory 'demo.err.log'
$process = Start-Process -FilePath 'java' -ArgumentList @('-jar', $jarFile, $configFile) -WorkingDirectory $distDirectory -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
Set-Content -LiteralPath $pidFile -Value $process.Id -Encoding ascii

try {
    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        if ($process.HasExited) {
            throw "Saci Demo process exited with code $($process.ExitCode); inspect $stderr"
        }
        try {
            $health = Invoke-RestMethod -Uri ($baseUrl + '/health') -TimeoutSec 2
            if ($health.code -eq 200 -and $health.data.status -eq 'UP') {
                $ready = $true
                break
            }
        } catch {
            Start-Sleep -Milliseconds 250
        }
    }
    if (-not $ready) {
        throw "Saci Demo did not become HTTP-ready; inspect $stderr"
    }

    $launchToken = 'restart-smoke-' + [guid]::NewGuid().ToString('N')
    $init = Invoke-RestMethod -Method Post -Uri ($baseUrl + '/api/init') -ContentType 'application/x-www-form-urlencoded' -Body @{ai='local_61';btt='1';gid='61';t=$launchToken} -TimeoutSec 5
    if ($init.code -ne 200 -or [string]::IsNullOrWhiteSpace([string]$init.data.token)) {
        throw 'Saci Demo Init smoke check failed'
    }
    $sessionToken = [string]$init.data.token
    $config = Invoke-RestMethod -Method Post -Uri ($baseUrl + '/api/config') -ContentType 'application/x-www-form-urlencoded' -Body @{gid='61';t=$sessionToken} -TimeoutSec 5
    if ($config.code -ne 200 -or -not ($config.data.bll -contains 1) -or -not ($config.data.bsl -contains 0.02)) {
        throw 'Saci Demo Config smoke check failed'
    }
    $spin = Invoke-RestMethod -Method Post -Uri ($baseUrl + '/api/spin') -ContentType 'application/x-www-form-urlencoded' -Body @{gid='61';t=$sessionToken;bl='1';bs='0.02';request_id=('restart-' + [guid]::NewGuid().ToString('N'))} -TimeoutSec 5
    if ($spin.code -ne 200 -or [decimal]$spin.data.ba -ne [decimal]0.4) {
        throw 'Saci Demo minimum Spin smoke check failed'
    }

    $persistedPid = (Get-Content -LiteralPath $pidFile -Raw).Trim()
    if ($persistedPid -ne [string]$process.Id -or $process.HasExited) {
        throw 'Saci Demo PID verification failed after smoke checks'
    }
    Write-Output "Saci Demo restarted and verified. PID=$($process.Id) Health=PASS Init=PASS Config=PASS MinimumSpin=PASS"
    Write-Output "Open page from demo-url.txt: $configuredDemoUrl"
} catch {
    if (-not $process.HasExited) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
        Wait-Process -Id $process.Id -Timeout 10 -ErrorAction SilentlyContinue
    }
    throw
}
