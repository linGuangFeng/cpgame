param([int]$WaitSeconds = 30)

$ErrorActionPreference = 'Stop'
$dist = $PSScriptRoot
$jar = Join-Path $dist 'club-goddess-api.jar'
$config = Join-Path $dist 'application.properties'
$pidFile = Join-Path $dist 'demo-api.pid'
$stdout = Join-Path $dist 'demo-api.stdout.log'
$stderr = Join-Path $dist 'demo-api.stderr.log'
$temp = Join-Path $dist 'data\tmp'
$java = (Get-Command 'java.exe' -ErrorAction Stop).Source

if (-not (Test-Path -LiteralPath $jar) -or -not (Test-Path -LiteralPath $config)) {
    throw 'restart-demo.ps1 requires the demo JAR and application.properties in dist.'
}

function Test-ExpectedDemoProcess {
    param($ProcessInfo, [string]$ExpectedJarPath)
    if ($null -eq $ProcessInfo) { return $false }
    $isJava = $ProcessInfo.ProcessName -ieq 'java' -or $ProcessInfo.ProcessName -ieq 'javaw'
    if (-not $isJava -or [string]::IsNullOrWhiteSpace([string]$ProcessInfo.Path)) { return $false }
    $jcmd = Join-Path (Split-Path -Parent $ProcessInfo.Path) 'jcmd.exe'
    if (-not (Test-Path -LiteralPath $jcmd)) { return $false }
    $jcmdOutput = & $jcmd $ProcessInfo.Id 'VM.command_line' 2>$null
    if ($LASTEXITCODE -ne 0) { return $false }
    $commandLine = ($jcmdOutput -join "`n")
    $fullJar = [IO.Path]::GetFullPath($ExpectedJarPath)
    return $commandLine.IndexOf($fullJar, [StringComparison]::OrdinalIgnoreCase) -ge 0
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

$oldProcess = $null
$oldProcessId = 0
$pidFileExists = Test-Path -LiteralPath $pidFile
$listeners = @(Get-DemoListeners)
if ($pidFileExists) {
    $oldText = (Get-Content -LiteralPath $pidFile -Raw).Trim()
    if (-not [int]::TryParse($oldText, [ref]$oldProcessId)) { exit 3 }
    $oldProcess = Get-Process -Id $oldProcessId -ErrorAction SilentlyContinue
    if ($null -eq $oldProcess) { exit 11 }
    if (-not (Test-ExpectedDemoProcess -ProcessInfo $oldProcess -ExpectedJarPath $jar)) { exit 4 }
    if (-not (Test-DemoListenerOwner -ProcessId $oldProcessId -Listeners $listeners)) { exit 12 }
} elseif ($listeners.Count -gt 0) {
    exit 13
}

New-Item -ItemType Directory -Path $temp -Force | Out-Null

if ($pidFileExists) {
    if ($null -ne $oldProcess) {
        Stop-Process -InputObject $oldProcess -Force
        $null = $oldProcess.WaitForExit(10000)
    }
    Remove-Item -LiteralPath $pidFile -Force
}

$arguments = @(
    "-Djava.io.tmpdir=$temp",
    "-Dlogging.file.name=$stdout",
    '-jar', $jar,
    "--spring.config.additional-location=file:$config"
)
Remove-Item -LiteralPath $stdout, $stderr -Force -ErrorAction SilentlyContinue
$process = Start-Process -FilePath $java -ArgumentList $arguments -WorkingDirectory $dist `
    -WindowStyle Hidden -PassThru
$newPid = $process.Id
[System.IO.File]::WriteAllText($pidFile, [string]$newPid, [System.Text.UTF8Encoding]::new($false))

$deadline = [DateTime]::UtcNow.AddSeconds($WaitSeconds)
do {
    if ($process.HasExited) {
        throw "Demo API exited during startup with code $($process.ExitCode); see $stderr"
    }
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:20600/' -TimeoutSec 2
        if ($response.StatusCode -eq 200) {
            $newProcess = Get-Process -Id $newPid -ErrorAction SilentlyContinue
            $newListeners = @(Get-DemoListeners)
            if (-not (Test-ExpectedDemoProcess -ProcessInfo $newProcess -ExpectedJarPath $jar)) { exit 7 }
            if (-not (Test-DemoListenerOwner -ProcessId $newPid -Listeners $newListeners)) { exit 12 }
            Write-Output "Demo API restarted; PID=${newPid}; URL=http://localhost:20600/"
            # Release redirected stream handles after health succeeds so --no-pause returns.
            $process.Dispose()
            exit 0
        }
    } catch {
        Start-Sleep -Milliseconds 250
    }
} while ([DateTime]::UtcNow -lt $deadline)

throw "Demo API was not ready in $WaitSeconds seconds; see $stderr"
