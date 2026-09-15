[CmdletBinding()]
param(
    [switch]$NoPause,
    [switch]$ValidatePidOnly,
    [int]$ExpectedPid = 0
)

$ErrorActionPreference = 'Stop'
$serverRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$distDirectory = Join-Path $serverRoot 'dist'
$serverJar = [IO.Path]::GetFullPath((Join-Path $distDirectory 'crazy777-server-api.jar'))
$generatorJar = [IO.Path]::GetFullPath((Join-Path $distDirectory 'crazy777-generator.jar'))
$serverConfig = [IO.Path]::GetFullPath((Join-Path $distDirectory 'server.properties'))
$pidFile = Join-Path $distDirectory 'demo-server.pid'
$stdoutLog = Join-Path $distDirectory 'demo.stdout.log'
$stderrLog = Join-Path $distDirectory 'demo.stderr.log'
$stdinFile = Join-Path $distDirectory 'demo.stdin'
$demoUrlFile = Join-Path $serverRoot 'demo-url.txt'
$mainClass = 'com.cpgame.crazy777.server.Crazy777ServerMain'
$loopbackBase = 'http://127.0.0.1:19557'
$expectedRulesHash = 'sha256:852d3021084c1f4117203c4a6eda137747bf9c1f689b30decb9791e46bf66f2b'

function Get-OwnedDemoProcess([int]$ProcessId) {
    if ($ProcessId -le 0) { return $null }
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$ProcessId" -ErrorAction SilentlyContinue
    if ($null -eq $process) { return $null }
    $commandLine = [string]$process.CommandLine
    $owned = $process.Name -ieq 'java.exe' -and
        $commandLine.IndexOf($serverJar, [StringComparison]::OrdinalIgnoreCase) -ge 0 -and
        $commandLine.IndexOf($generatorJar, [StringComparison]::OrdinalIgnoreCase) -ge 0 -and
        $commandLine.IndexOf($mainClass, [StringComparison]::Ordinal) -ge 0 -and
        $commandLine.IndexOf($serverConfig, [StringComparison]::OrdinalIgnoreCase) -ge 0
    return [pscustomobject]@{ Process = $process; Owned = $owned; CommandLine = $commandLine }
}

function Read-PositivePid([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return 0 }
    $text = (Get-Content -Raw -LiteralPath $Path).Trim()
    $value = 0
    if (-not [int]::TryParse($text, [ref]$value) -or $value -le 0) {
        throw "PID 文件无效：$Path"
    }
    return $value
}

function Stop-OwnedDemo([int]$ProcessId, [string]$Source) {
    $checked = Get-OwnedDemoProcess $ProcessId
    if ($null -eq $checked) {
        Write-Host "旧 PID 已失效：PID=$ProcessId，来源=$Source"
        return
    }
    if (-not $checked.Owned) {
        throw "安全拒绝停止 PID=$ProcessId：它不属于当前 Crazy 777 Java Demo。"
    }
    Stop-Process -Id $ProcessId -Force -ErrorAction Stop
    try { Wait-Process -Id $ProcessId -Timeout 10 -ErrorAction Stop } catch {
        if (Get-Process -Id $ProcessId -ErrorAction SilentlyContinue) {
            throw "等待当前游戏 Java Demo PID=$ProcessId 退出超时。"
        }
    }
    Write-Host "已停止归属核验通过的旧试玩进程：PID=$ProcessId，来源=$Source"
}

function Invoke-GamePost([string]$Path, [hashtable]$Body) {
    return Invoke-RestMethod -Method Post -Uri ($loopbackBase + $Path) `
        -ContentType 'application/x-www-form-urlencoded' -Body $Body -TimeoutSec 8
}

foreach ($requiredFile in @($serverJar, $generatorJar, $serverConfig, $demoUrlFile)) {
    if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) {
        throw "缺少试玩启动文件：$requiredFile"
    }
}

$urlLines = @(Get-Content -LiteralPath $demoUrlFile | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
if ($urlLines.Count -ne 1 -or $urlLines[0].Trim() -ne 'http://localhost:19557/launch') {
    throw 'demo-url.txt 必须只包含一行有效的本机 HTTP 页面地址：http://localhost:19557/launch'
}
$demoUrl = $urlLines[0].Trim()
$configText = Get-Content -Raw -LiteralPath $serverConfig
if ($configText -notmatch '(?m)^server\.host=0\.0\.0\.0\s*$') {
    throw 'server.properties 必须配置 server.host=0.0.0.0。'
}

if ($ValidatePidOnly) {
    if ($ExpectedPid -le 0) { throw 'ValidatePidOnly 要求 ExpectedPid 为正整数。' }
    $recordedPid = Read-PositivePid $pidFile
    if ($recordedPid -ne $ExpectedPid) { throw "PID 文件与 ExpectedPid 不一致：$recordedPid != $ExpectedPid" }
    $checked = Get-OwnedDemoProcess $recordedPid
    if ($null -eq $checked) {
        Write-Host "STALE_PID=$recordedPid OWNERSHIP=NO_RUNNING_PROCESS"
        return
    }
    if (-not $checked.Owned) { throw "PID=$recordedPid 不属于当前 Crazy 777 Java Demo。" }
    Write-Host "VALIDATED_PID=$recordedPid OWNERSHIP=EXACT_CRAZY777_JAVA_DEMO"
    return
}

$stoppedPids = [System.Collections.Generic.List[int]]::new()
$recordedPid = Read-PositivePid $pidFile
if ($recordedPid -gt 0) {
    Stop-OwnedDemo $recordedPid 'PID_FILE'
    [void]$stoppedPids.Add($recordedPid)
    Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
}

# 没有 PID 文件的旧版本进程，只能在端口监听 PID 通过同一精确归属核验后接管。
$listeners = @(Get-NetTCPConnection -LocalPort 19557 -State Listen -ErrorAction SilentlyContinue)
foreach ($listenerPid in @($listeners | Select-Object -ExpandProperty OwningProcess -Unique)) {
    if ($stoppedPids.Contains([int]$listenerPid)) { continue }
    $checked = Get-OwnedDemoProcess ([int]$listenerPid)
    if ($null -eq $checked -or -not $checked.Owned) {
        throw "端口 19557 被非当前游戏进程 PID=$listenerPid 占用，已安全拒绝停止。"
    }
    Stop-OwnedDemo ([int]$listenerPid) 'PORT_19557'
    [void]$stoppedPids.Add([int]$listenerPid)
}

Set-Content -LiteralPath $stdinFile -Value '' -Encoding ascii
$classpath = "$serverJar;$generatorJar"
$arguments = @(
    '-Dfile.encoding=UTF-8', '-Dsun.stdout.encoding=UTF-8', '-Dsun.stderr.encoding=UTF-8',
    '-cp', $classpath, $mainClass, '--config', $serverConfig
)
$process = Start-Process -FilePath 'java.exe' -ArgumentList $arguments -WorkingDirectory $distDirectory `
    -WindowStyle Hidden -RedirectStandardInput $stdinFile -RedirectStandardOutput $stdoutLog `
    -RedirectStandardError $stderrLog -PassThru
Set-Content -LiteralPath $pidFile -Value $process.Id -Encoding ascii

try {
    $health = $null
    $deadline = (Get-Date).AddSeconds(30)
    do {
        Start-Sleep -Milliseconds 250
        if ($process.HasExited) { break }
        try {
            $candidate = Invoke-RestMethod -Uri "$loopbackBase/health" -TimeoutSec 2
            $portOwner = @(Get-NetTCPConnection -LocalPort 19557 -State Listen -ErrorAction SilentlyContinue |
                Select-Object -ExpandProperty OwningProcess -Unique)
            if ($candidate.status -eq 'UP' -and $portOwner -contains $process.Id) { $health = $candidate; break }
        } catch { }
    } while ((Get-Date) -lt $deadline)
    if ($null -eq $health -or $health.status -ne 'UP') { throw '试玩服务未在 30 秒内达到 HTTP 就绪。' }
    if ($health.bind -ne '0.0.0.0:19557' -or $health.rulesHash -ne $expectedRulesHash) {
        throw "健康检查返回的监听或 rulesHash 不符：bind=$($health.bind), rulesHash=$($health.rulesHash)"
    }
    $page = Invoke-WebRequest -UseBasicParsing -Uri $demoUrl -TimeoutSec 10
    if ($page.StatusCode -ne 200) { throw 'demo-url.txt 页面未返回 HTTP 200。' }

    $requestKey = 'restart-smoke-' + [Guid]::NewGuid().ToString('N')
    $auth = Invoke-GamePost '/cp/api/v1/auth/verify' @{ gid='57'; t=$requestKey }
    if ($auth.code -ne 200 -or [string]::IsNullOrWhiteSpace([string]$auth.data.token)) { throw 'Init 前置认证失败。' }
    $runtimeToken = [string]$auth.data.token
    $init = Invoke-GamePost '/cp/api/v1/crazy-seven/init' @{ gid='57'; t=$runtimeToken }
    if ($init.code -ne 200 -or $null -eq $init.data.last) { throw 'Init 冒烟失败。' }
    $gameConfig = Invoke-GamePost '/cp/api/v1/crazy-seven/config' @{ gid='57'; t=$runtimeToken }
    if ($gameConfig.code -ne 200 -or -not (@($gameConfig.data.bll) -contains 1) -or
            -not (@($gameConfig.data.bsl) -contains 0.5)) { throw 'Config 未返回最小下注 bl=1、bs=0.5。' }
    $spin = Invoke-GamePost '/cp/api/v1/crazy-seven/spin' @{
        gid='57'; t=$runtimeToken; bl='1'; bs='0.5'; request_id=$requestKey
    }
    if ($spin.code -ne 200 -or @($spin.data.rskl).Count -ne 15) { throw '最小 Spin 未返回合法的 15 位置牌面。' }

    $workspace = Split-Path -Parent (Split-Path -Parent $serverRoot)
    $reportFile = Join-Path $workspace 'reports\57-Crazy-777\restart-demo-validation.json'
    $validation = [ordered]@{
        schemaVersion = 1
        gameId = 57
        result = 'PASS'
        validatedAt = [DateTimeOffset]::Now.ToString('o')
        pidSafety = [ordered]@{
            pidFile = 'server-api/57-Crazy-777/dist/demo-server.pid'
            stoppedValidatedPids = @($stoppedPids)
            activePid = $process.Id
            ownership = 'EXACT_SERVER_JAR_GENERATOR_JAR_MAIN_CLASS_CONFIG'
        }
        http = [ordered]@{
            ready = $true
            health = 200
            page = $page.StatusCode
            auth = 200
            init = 200
            config = 200
            minimumSpin = 200
            minimumBet = 0.5
            spinPositionCount = @($spin.data.rskl).Count
        }
        bind = [string]$health.bind
        rulesHash = [string]$health.rulesHash
        demoUrlSource = 'server-api/57-Crazy-777/demo-url.txt'
        demoUrl = $demoUrl
    }
    $validation | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $reportFile -Encoding UTF8

    Write-Host "PID=$($process.Id) OWNERSHIP=VALIDATED"
    Write-Host 'HTTP_READY=PASS AUTH=PASS INIT=PASS CONFIG=PASS MIN_SPIN=PASS'
    Write-Host "BIND=$($health.bind) RULES_HASH=$($health.rulesHash)"
    Write-Host "URL=$demoUrl"
    Write-Host "SELF_CHECK_REPORT=$reportFile"
} catch {
    $checked = Get-OwnedDemoProcess $process.Id
    if ($null -ne $checked -and $checked.Owned) {
        Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    }
    Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
    throw
}