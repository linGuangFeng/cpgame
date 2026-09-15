$ErrorActionPreference = 'Stop'

$apiDir = $PSScriptRoot
$productRoot = Split-Path (Split-Path $apiDir -Parent) -Parent
$publishDir = Join-Path $productRoot 'publish\2350-Curupira'
$apiJar = Join-Path $apiDir 'target\curupira-server-api-1.0.0.jar'
$demoUrlPath = Join-Path $apiDir 'demo-url.txt'
$logDir = Join-Path $apiDir 'target\demo-logs'
$apiPidPath = Join-Path $logDir 'api.pid'
$staticPidPath = Join-Path $logDir 'static.pid'
$apiStdout = Join-Path $logDir 'api.stdout.log'
$apiStderr = Join-Path $logDir 'api.stderr.log'
$staticStdout = Join-Path $logDir 'static.stdout.log'
$staticStderr = Join-Path $logDir 'static.stderr.log'

if (-not (Test-Path -LiteralPath $apiJar)) { throw "缺少 Java API JAR：$apiJar" }
if (-not (Test-Path -LiteralPath (Join-Path $publishDir 'index.html'))) { throw "缺少发布入口：$publishDir" }
if (-not (Test-Path -LiteralPath $demoUrlPath)) { throw "缺少试玩地址文件：$demoUrlPath" }

$urlLines = @(Get-Content -LiteralPath $demoUrlPath | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
if ($urlLines.Count -ne 1) { throw 'demo-url.txt 必须只包含一个 HTTP 页面地址。' }
$demoUrl = $urlLines[0].Trim()
$demoUri = [Uri]$demoUrl
if (-not $demoUri.IsAbsoluteUri -or $demoUri.Scheme -ne 'http' -or $demoUri.Host -notin @('127.0.0.1','localhost')) {
    throw 'demo-url.txt 必须只包含有效的本机 HTTP 页面地址。'
}

New-Item -ItemType Directory -Path $logDir -Force | Out-Null

function Get-ValidatedGameProcess {
    param([int]$ProcessId, [ValidateSet('api','static')][string]$Role)
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$ProcessId" -ErrorAction SilentlyContinue
    if ($null -eq $process) { return $null }
    $command = [string]$process.CommandLine
    $owned = if ($Role -eq 'api') { $command -like "*$apiJar*" } else { $command -like "*$publishDir*" }
    if (-not $owned) { throw "PID $ProcessId 不属于当前游戏 $Role，拒绝停止：$command" }
    return $process
}

function Stop-PersistedGameProcess {
    param([string]$PidPath, [ValidateSet('api','static')][string]$Role)
    if (-not (Test-Path -LiteralPath $PidPath)) { return }
    $text = (Get-Content -Raw -LiteralPath $PidPath).Trim()
    try { $processId = [int]$text } catch { throw "PID 文件无效，拒绝停止进程：$PidPath" }
    if ($processId -le 0) { throw "PID 文件无效，拒绝停止进程：$PidPath" }
    $process = Get-ValidatedGameProcess -ProcessId $processId -Role $Role
    if ($null -ne $process) {
        Stop-Process -Id $processId -Force -ErrorAction Stop
        Wait-Process -Id $processId -Timeout 10 -ErrorAction SilentlyContinue
    }
    Remove-Item -LiteralPath $PidPath -Force
}

Stop-PersistedGameProcess -PidPath $apiPidPath -Role 'api'
Stop-PersistedGameProcess -PidPath $staticPidPath -Role 'static'

foreach ($port in 8500, 19500) {
    $role = if ($port -eq 19500) { 'api' } else { 'static' }
    $listeners = @(Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue)
    foreach ($listener in $listeners) {
        $null = Get-ValidatedGameProcess -ProcessId $listener.OwningProcess -Role $role
        Stop-Process -Id $listener.OwningProcess -Force -ErrorAction Stop
        Wait-Process -Id $listener.OwningProcess -Timeout 10 -ErrorAction SilentlyContinue
    }
}

$java = (Get-Command java.exe -ErrorAction Stop).Source
$jwebserver = (Get-Command jwebserver.exe -ErrorAction Stop).Source
$api = $null
$static = $null
try {
    $api = Start-Process -FilePath $java -ArgumentList @('-jar', $apiJar) -WorkingDirectory $apiDir `
        -RedirectStandardOutput $apiStdout -RedirectStandardError $apiStderr -WindowStyle Hidden -PassThru
    $static = Start-Process -FilePath $jwebserver -ArgumentList @('-b','0.0.0.0','-p','8500','-d',$publishDir) `
        -WorkingDirectory $publishDir -RedirectStandardOutput $staticStdout -RedirectStandardError $staticStderr `
        -WindowStyle Hidden -PassThru
    Set-Content -LiteralPath $apiPidPath -Value $api.Id -NoNewline -Encoding ascii
    Set-Content -LiteralPath $staticPidPath -Value $static.Id -NoNewline -Encoding ascii
    $null = Get-ValidatedGameProcess -ProcessId $api.Id -Role 'api'
    $null = Get-ValidatedGameProcess -ProcessId $static.Id -Role 'static'

    $deadline = (Get-Date).AddSeconds(30)
    $health = $null
    $index = $null
    do {
        try {
            $health = Invoke-RestMethod -Uri 'http://127.0.0.1:19500/health' -TimeoutSec 2
            $index = Invoke-WebRequest -UseBasicParsing -Uri $demoUrl -TimeoutSec 2
            if ($health.status -eq 'UP' -and $index.StatusCode -eq 200) { break }
        } catch {
            Start-Sleep -Milliseconds 300
        }
    } while ((Get-Date) -lt $deadline)
    if ($null -eq $health -or $health.status -ne 'UP' -or $null -eq $index -or $index.StatusCode -ne 200) {
        throw '试玩服务未能在 30 秒内通过 HTTP 就绪检查。'
    }

    $contentType = 'application/x-www-form-urlencoded;charset=utf-8'
    $config = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:19500/cp/config/initialData' `
        -ContentType $contentType -Body @{ gid='2350'; language='en-us'; ai='local_demo'; currency='undefined' }
    if ($config.code -ne 0 -or $config.data.game_info.name -ne 'Curupira') { throw 'Config 验收失败。' }

    $token = "restart-demo-$($api.Id)-$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())"
    $init = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:19500/cp/single_game.Game/initRoom' `
        -ContentType $contentType -Body @{ token=$token; gid='2350'; language='en-us' }
    if ($init.code -ne 0 -or @($init.data.res.ps).Count -ne 15) { throw 'Init 验收失败。' }

    $spin = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:19500/cp/single_game.Game/gameResult' `
        -ContentType $contentType -Headers @{ 'Idempotency-Key'="restart-$($api.Id)" } `
        -Body @{ token=$token; gid='2350'; language='en-us'; bet='0.02'; level='1'; type='1'; game_type='1' }
    if ($spin.code -ne 0 -or @($spin.data.res.ps).Count -ne 15 -or [decimal]$spin.data.bg -ne [decimal]'0.50') {
        throw '最小 Spin 验收失败。'
    }

    $apiListener = @(Get-NetTCPConnection -State Listen -LocalPort 19500 -ErrorAction Stop)
    $staticListener = @(Get-NetTCPConnection -State Listen -LocalPort 8500 -ErrorAction Stop)
    if ($api.Id -notin $apiListener.OwningProcess -or $static.Id -notin $staticListener.OwningProcess) {
        throw '启动后的监听 PID 与当前游戏进程不一致。'
    }

    Write-Output ([ordered]@{
        status = 'PASS'
        apiPid = $api.Id
        staticPid = $static.Id
        staticBind = '0.0.0.0:8500'
        apiBind = '0.0.0.0:19500'
        config = 'PASS'
        init = 'PASS'
        minimumSpin = 'PASS'
        roundKey = [string]$spin.data.rid
        rulesHash = $health.rulesHash
        demoUrl = $demoUrl
    } | ConvertTo-Json -Compress)
} catch {
    foreach ($started in @($api, $static)) {
        if ($null -ne $started) {
            $role = if ($started.Id -eq $api.Id) { 'api' } else { 'static' }
            $owned = Get-ValidatedGameProcess -ProcessId $started.Id -Role $role
            if ($null -ne $owned) { Stop-Process -Id $started.Id -Force -ErrorAction SilentlyContinue }
        }
    }
    Remove-Item -LiteralPath $apiPidPath,$staticPidPath -Force -ErrorAction SilentlyContinue
    throw
}
