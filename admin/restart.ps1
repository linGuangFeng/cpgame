param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$ScriptArgs
)

$ErrorActionPreference = 'Stop'
$Deploy = $false
foreach ($item in @($ScriptArgs)) {
    if ($item -eq '--deploy' -or $item -eq '-Deploy') { $Deploy = $true }
}

$AdminDir = $PSScriptRoot
$RootDir = (Resolve-Path (Join-Path $AdminDir '..')).Path
$VarDir = Join-Path $AdminDir 'var'
$RunDir = Join-Path $VarDir 'run'
New-Item -ItemType Directory -Force -Path $VarDir, $RunDir | Out-Null

if (-not $env:CPGAME_ARTIFACT_ROOT) { $env:CPGAME_ARTIFACT_ROOT = $RootDir }
if (-not $env:CPGAME_ADMIN_RUNTIME) { $env:CPGAME_ADMIN_RUNTIME = $VarDir }
if (-not $env:CPGAME_ADMIN_ADDRESS) { $env:CPGAME_ADMIN_ADDRESS = '0.0.0.0' }
if (-not $env:CPGAME_ADMIN_PORT) { $env:CPGAME_ADMIN_PORT = '8000' }

$PidFile = Join-Path $VarDir 'admin.pid'
$RuntimeJar = Join-Path $VarDir 'cpgame-admin.jar'
$StagedJar = Join-Path $VarDir 'cpgame-admin.jar.next'
$TargetJar = Join-Path $AdminDir 'target\cpgame-admin-0.1.0-SNAPSHOT.jar'
$StdoutLog = Join-Path $VarDir 'admin.stdout.log'
$StderrLog = Join-Path $VarDir 'admin.stderr.log'
$Launcher = Join-Path $VarDir 'run-admin.cmd'
$Port = $env:CPGAME_ADMIN_PORT

function Test-AdminMainProcess([string]$CommandLine) {
    if ([string]::IsNullOrWhiteSpace($CommandLine)) { return $false }
    if ($CommandLine -match 'CpgameSharedDemoHostMain') { return $false }
    if ($CommandLine -notmatch '-jar') { return $false }
    return $CommandLine -match 'cpgame-admin(?:-0\.1\.0-SNAPSHOT)?(?:-[0-9]{8}-[0-9]{6})?\.jar'
}

function Stop-AdminMain {
    $stopped = @{}
    if (Test-Path -LiteralPath $PidFile) {
        $raw = (Get-Content -LiteralPath $PidFile -Raw).Trim()
        $oldId = 0
        if ([int]::TryParse($raw, [ref]$oldId) -and $oldId -gt 0) {
            $proc = Get-CimInstance Win32_Process -Filter ("ProcessId = $oldId") -ErrorAction SilentlyContinue
            if ($proc -and (Test-AdminMainProcess $proc.CommandLine)) {
                Stop-Process -Id $oldId -Force -ErrorAction SilentlyContinue
                $stopped[$oldId] = $true
            }
        }
        Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
    }
    Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue | ForEach-Object {
        if ($stopped.ContainsKey([int]$_.ProcessId)) { return }
        if (-not (Test-AdminMainProcess $_.CommandLine)) { return }
        Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue
        $stopped[[int]$_.ProcessId] = $true
    }
    foreach ($id in $stopped.Keys) {
        try { Wait-Process -Id $id -Timeout 8 -ErrorAction Stop } catch {
            Stop-Process -Id $id -Force -ErrorAction SilentlyContinue
        }
    }
}

function Get-NewestSourceJar {
    $candidates = @()
    foreach ($path in @($TargetJar, $StagedJar, $RuntimeJar)) {
        if (Test-Path -LiteralPath $path) { $candidates += Get-Item -LiteralPath $path }
    }
    Get-ChildItem -LiteralPath $RunDir -Filter 'cpgame-admin-*.jar' -File -ErrorAction SilentlyContinue |
        ForEach-Object { $candidates += $_ }
    if ($candidates.Count -eq 0) { return $null }
    return $candidates | Sort-Object LastWriteTimeUtc, FullName | Select-Object -Last 1
}

function Copy-RunJar([string]$Source) {
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $dest = Join-Path $RunDir "cpgame-admin-$stamp.jar"
    Copy-Item -LiteralPath $Source -Destination $dest -Force
    try { Copy-Item -LiteralPath $Source -Destination $RuntimeJar -Force } catch { }
    Get-ChildItem -LiteralPath $RunDir -Filter 'cpgame-admin-*.jar' -File -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTimeUtc |
        Select-Object -SkipLast 8 |
        Remove-Item -Force -ErrorAction SilentlyContinue
    return $dest
}

Stop-AdminMain

$sourceJar = $null
if ($Deploy) {
    if (Test-Path -LiteralPath $StagedJar) { $sourceJar = $StagedJar }
    elseif (Test-Path -LiteralPath $TargetJar) { $sourceJar = $TargetJar }
    if ($null -eq $sourceJar) {
        Write-Error '[ERROR] Staged jar was not found. Run package-restart.cmd first.'
        exit 1
    }
} else {
    $newest = Get-NewestSourceJar
    if ($null -eq $newest) {
        Write-Error '[ERROR] No packaged jar found. Run package-restart.cmd first.'
        exit 1
    }
    $sourceJar = $newest.FullName
}

$runJar = Copy-RunJar $sourceJar
Write-Output ("Using jar: " + $runJar + " (" + (Get-Item -LiteralPath $runJar).LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss') + ")")

$java = (Get-Command java -ErrorAction Stop).Source
@(
    '@echo off'
    'setlocal'
    '"' + $java + '" --add-exports=jdk.httpserver/sun.net.httpserver=ALL-UNNAMED -jar "' + $runJar + '" --port=' + $Port + ' --address="' + $env:CPGAME_ADMIN_ADDRESS + '" --root="' + $env:CPGAME_ARTIFACT_ROOT + '" --runtime="' + $env:CPGAME_ADMIN_RUNTIME + '" >> "' + $StdoutLog + '" 2>> "' + $StderrLog + '"'
) | Set-Content -LiteralPath $Launcher -Encoding ASCII

$created = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
    CommandLine = ('cmd.exe /c "' + $Launcher + '"')
    CurrentDirectory = $AdminDir
}
if ($null -eq $created -or [int]$created.ReturnValue -ne 0 -or [int]$created.ProcessId -le 0) {
    Write-Error "[ERROR] Failed to start CPGame admin. return=$($created.ReturnValue)"
    exit 1
}

$adminPid = $null
$deadline = (Get-Date).AddSeconds(15)
while ((Get-Date) -lt $deadline -and $null -eq $adminPid) {
    $adminPid = Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue |
        Where-Object { (Test-AdminMainProcess $_.CommandLine) -and $_.CommandLine -like ("*" + [IO.Path]::GetFileName($runJar) + "*") } |
        Select-Object -ExpandProperty ProcessId -First 1
    if ($null -eq $adminPid) { Start-Sleep -Milliseconds 200 }
}
if ($null -eq $adminPid) { $adminPid = [int]$created.ProcessId }
Set-Content -LiteralPath $PidFile -Value $adminPid -NoNewline

$url = "http://127.0.0.1:$Port/health"
$healthy = $false
for ($i = 0; $i -lt 30; $i++) {
    $alive = Get-CimInstance Win32_Process -Filter ("ProcessId = $adminPid") -ErrorAction SilentlyContinue
    if (-not $alive) { break }
    try {
        if ((Invoke-WebRequest -UseBasicParsing -Uri $url -TimeoutSec 1).Content -eq 'ok') {
            $healthy = $true
            break
        }
    } catch { }
    Start-Sleep -Milliseconds 500
}
if (-not $healthy) {
    Write-Error '[ERROR] CPGame admin failed to become healthy. See var\admin.stderr.log.'
    exit 1
}

Write-Output "CPGame admin is running at http://127.0.0.1:$Port"
exit 0
