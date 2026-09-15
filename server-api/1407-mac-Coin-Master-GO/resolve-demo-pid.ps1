[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$OutputFile
)

$ErrorActionPreference = 'Stop'
$apiRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$reportRoot = 'D:\work\hd\cpgame\reports\1407-Coin-Master-GO'
$metaPath = Join-Path $reportRoot 'runtime\api.process.json'
$expectedJar = [IO.Path]::GetFullPath((Join-Path $apiRoot 'target\coin-master-go-server-api-1.0.0.jar'))
$OutputFile = [IO.Path]::GetFullPath($OutputFile)
Remove-Item -LiteralPath $OutputFile -Force -ErrorAction SilentlyContinue

$meta = $null
$process = $null
$processInfo = $null
$metadataMode = $false

if (Test-Path -LiteralPath $metaPath) {
    $meta = Get-Content -Raw -LiteralPath $metaPath | ConvertFrom-Json
    $process = Get-Process -Id ([int]$meta.pid) -ErrorAction SilentlyContinue
    if ($null -eq $process) {
        Remove-Item -LiteralPath $metaPath -Force -ErrorAction SilentlyContinue
    } else {
        $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $($process.Id)" -ErrorAction Stop
        $metadataMode = $true
    }
}

if ($null -eq $process) {
    $candidates = @(Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction Stop |
        Where-Object { [string]$_.CommandLine -like "*$expectedJar*" })
    if ($candidates.Count -gt 1) {
        throw "Refusing Java Demo takeover: multiple processes target exact game 1407 JAR $expectedJar"
    }
    if ($candidates.Count -eq 1) {
        $processInfo = $candidates[0]
        $process = Get-Process -Id ([int]$processInfo.ProcessId) -ErrorAction Stop
    }
}

if ($null -eq $process) { exit 0 }
if ($null -eq $processInfo) {
    $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId = $($process.Id)" -ErrorAction Stop
}

$actualExecutableValue = if (-not [string]::IsNullOrWhiteSpace([string]$process.Path)) {
    [string]$process.Path
} else {
    [string]$processInfo.ExecutablePath
}
$actualExecutable = [IO.Path]::GetFullPath($actualExecutableValue)
$targetMatches = [string]$processInfo.Name -ieq 'java.exe' -and
    [string]$processInfo.CommandLine -like "*$expectedJar*"

if ($metadataMode) {
    $recordedStart = if ($meta.startTimeUtc -is [DateTime]) {
        ([DateTime]$meta.startTimeUtc).ToUniversalTime()
    } else {
        [DateTimeOffset]::Parse([string]$meta.startTimeUtc).UtcDateTime
    }
    $recordedExecutable = [IO.Path]::GetFullPath([string]$meta.executablePath)
    $metadataMatches = [int]$meta.gameId -eq 1407 -and
        [string]$meta.directoryName -eq '1407-Coin-Master-GO' -and
        [string]$meta.kind -eq 'java-demo'
    $startMatches = [Math]::Abs(($process.StartTime.ToUniversalTime() - $recordedStart).TotalSeconds) -lt 2
    $executableMatches = $actualExecutable -eq $recordedExecutable
} else {
    $metadataMatches = $true
    $startMatches = $true
    $executableMatches = $actualExecutable -eq [IO.Path]::GetFullPath([string]$processInfo.ExecutablePath)
}

if (-not ($metadataMatches -and $startMatches -and $executableMatches -and $targetMatches)) {
    throw "Refusing to stop PID $($process.Id): ownership identity does not match game 1407 Java Demo and exact JAR $expectedJar"
}

[IO.File]::WriteAllText($OutputFile, [string]$process.Id, [Text.Encoding]::ASCII)
