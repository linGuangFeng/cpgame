param(
    [string]$PlatformBaseUrl = "http://127.0.0.1:8091",
    [switch]$NoPause
)

$ErrorActionPreference = "Stop"
$directoryName = "43-Lucky-Wheel"
$bindAddress = "0.0.0.0"
$scriptDirectory = Split-Path -Parent $MyInvocation.MyCommand.Path
$demoUrlFile = Join-Path $scriptDirectory "demo-url.txt"
$processDirectory = Join-Path $scriptDirectory "runtime"
$processFile = Join-Path $processDirectory "demo.process.json"

function Invoke-PlatformPost([string]$path) {
    $response = Invoke-WebRequest -UseBasicParsing -Method Post -Uri ($PlatformBaseUrl.TrimEnd('/') + $path) -TimeoutSec 45
    if ($response.StatusCode -ne 200) { throw "平台接口返回 HTTP $($response.StatusCode): $path" }
    return $response.Content | ConvertFrom-Json
}

function Get-OwnedJavaProcess([long]$validatedPid, [int]$port) {
    $process = Get-CimInstance Win32_Process -Filter "ProcessId = $validatedPid"
    if ($null -eq $process) { throw "受管 Java PID $validatedPid 不存在" }
    $commandLine = [string]$process.CommandLine
    if ($process.Name -notmatch '^java(w)?\.exe$' -or
        $commandLine -notmatch 'CpgameSharedDemoHostMain' -or
        $commandLine -notmatch ([regex]::Escape("--port=$port"))) {
        throw "PID $validatedPid 不属于当前平台受管 Java Demo"
    }
    return $process
}

function Wait-Http([string]$uri) {
    for ($attempt = 0; $attempt -lt 80; $attempt++) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri $uri -TimeoutSec 2
            if ($response.StatusCode -eq 200) { return }
        } catch { }
        Start-Sleep -Milliseconds 250
    }
    throw "等待 HTTP 就绪超时: $uri"
}

function Resolve-LanIpv4([int]$port) {
    $candidates = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object { $_.IPAddress -ne '127.0.0.1' -and $_.IPAddress -notlike '169.254.*' -and $_.PrefixOrigin -ne 'WellKnown' } |
        Sort-Object -Property InterfaceMetric,SkipAsSource | Select-Object -ExpandProperty IPAddress -Unique
    foreach ($candidate in $candidates) {
        try {
            $response = Invoke-WebRequest -UseBasicParsing -Uri ("http://" + $candidate + ":" + $port + "/health") -TimeoutSec 3
            if ($response.StatusCode -eq 200) { return $candidate }
        } catch { }
    }
    throw "未找到可经动态端口访问的非回环 IPv4"
}

function To-LanDemoUrl([string]$managedUrl, [string]$lanIp, [int]$port) {
    $builder = [System.UriBuilder]$managedUrl
    $builder.Host = $lanIp
    $builder.Port = $port
    $url = $builder.Uri.AbsoluteUri
    $encodedHost = [uri]::EscapeDataString("$lanIp`:$port")
    $url = $url -replace '(?i)(sip|apiHost)=[^&]*', ('$1=' + $encodedHost)
    $url = $url -replace '(?i)apiPort=\d+', ("apiPort=$port")
    return $url
}

try {
    New-Item -ItemType Directory -Path $processDirectory -Force | Out-Null

    # 先由平台发现或启动当前实例，验证 PID 所有权后再做安全重启。
    $current = Invoke-PlatformPost ("/games/" + $directoryName + "/start")
    if (-not $current.ok) { throw $current.message }
    $validatedPid = [long]$current.processId
    $currentPort = [int]$current.port
    $owned = Get-OwnedJavaProcess $validatedPid $currentPort
    [ordered]@{
        directoryName = $directoryName
        validatedPid = $validatedPid
        port = $currentPort
        startTime = $owned.CreationDate
        commandLine = $owned.CommandLine
        validation = "Win32_Process exact PID, Java executable, host main and --port"
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $processFile -Encoding UTF8

    $null = Invoke-PlatformPost ("/games/" + $directoryName + "/stop")
    for ($attempt = 0; $attempt -lt 40 -and (Get-Process -Id $validatedPid -ErrorAction SilentlyContinue); $attempt++) {
        Start-Sleep -Milliseconds 250
    }
    if (Get-Process -Id $validatedPid -ErrorAction SilentlyContinue) {
        $null = Get-OwnedJavaProcess $validatedPid $currentPort
        Stop-Process -Id $validatedPid -Force
    }
    if (Get-NetTCPConnection -State Listen -LocalPort $currentPort -ErrorAction SilentlyContinue) {
        throw "旧受管端口 $currentPort 在停止后仍有监听"
    }

    $started = Invoke-PlatformPost ("/games/" + $directoryName + "/start")
    if (-not $started.ok) { throw $started.message }
    $validatedPid = [long]$started.processId
    $port = [int]$started.port
    if ($port -lt 50000 -or $port -gt 59999) { throw "平台端口不在 50000-59999: $port" }
    $owned = Get-OwnedJavaProcess $validatedPid $port
    $listeners = @(Get-NetTCPConnection -State Listen -OwningProcess $validatedPid -ErrorAction Stop)
    if ($listeners.Count -ne 1 -or $listeners[0].LocalPort -ne $port -or
        ($listeners[0].LocalAddress -ne $bindAddress -and $listeners[0].LocalAddress -ne '::')) {
        throw "受管进程必须恰好监听一个由平台分配、绑定 0.0.0.0/:: 的端口"
    }

    $lanIp = Resolve-LanIpv4 $port
    $demoUrl = To-LanDemoUrl ([string]$started.demoUrl) $lanIp $port
    Set-Content -LiteralPath $demoUrlFile -Value $demoUrl -Encoding ASCII
    [ordered]@{
        directoryName = $directoryName
        validatedPid = $validatedPid
        port = $port
        bindAddress = $listeners[0].LocalAddress
        startTime = $owned.CreationDate
        commandLine = $owned.CommandLine
        demoUrl = $demoUrl
    } | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $processFile -Encoding UTF8

    $baseUri = "http://$lanIp`:$port"
    $gameBaseUri = $baseUri + "/play/" + $directoryName
    Wait-Http ($baseUri + "/health")
    $page = Invoke-WebRequest -UseBasicParsing -Uri $demoUrl -TimeoutSec 20
    $resource = Invoke-WebRequest -UseBasicParsing -Uri ($baseUri + "/play/" + $directoryName + "/favicon.ico") -TimeoutSec 20
    $init = Invoke-RestMethod -Method Post -Uri ($gameBaseUri + "/cp/api/v1/lucky-wheel/init") -ContentType "application/x-www-form-urlencoded" -Body "gid=43&t=local-demo" -TimeoutSec 20
    if ($init.code -ne 200 -or [string]::IsNullOrWhiteSpace([string]$init.data.token)) { throw "Init 未返回有效 token" }
    $token = [uri]::EscapeDataString([string]$init.data.token)
    $config = Invoke-RestMethod -Method Post -Uri ($gameBaseUri + "/cp/api/v1/lucky-wheel/config") -ContentType "application/x-www-form-urlencoded" -Body "gid=43&t=$token" -TimeoutSec 20
    $spin = Invoke-RestMethod -Method Post -Uri ($gameBaseUri + "/cp/api/v1/lucky-wheel/spin") -ContentType "application/x-www-form-urlencoded" -Headers @{"Idempotency-Key"=("restart-" + [guid]::NewGuid().ToString('N'))} -Body "gid=43&t=$token&bl=1&bs=1" -TimeoutSec 20
    $history = Invoke-RestMethod -Method Post -Uri ($gameBaseUri + "/cp/api/v1/lucky-wheel/log-list") -ContentType "application/x-www-form-urlencoded" -Body "gid=43&t=$token&page_index=1" -TimeoutSec 20
    if ($page.StatusCode -ne 200 -or $resource.StatusCode -ne 200 -or $config.code -ne 200 -or $spin.code -ne 200 -or $history.code -ne 200) {
        throw "页面、静态资源或最小 API 闭环失败"
    }
    Write-Host "[成功] 受管 Java Demo 已安全重启：PID $validatedPid，监听 $bindAddress`:$port"
    Write-Host "[成功] Health、页面、静态资源、Init、Config、最小 Spin 与 History 均通过"
    Write-Host "试玩地址：$demoUrl"
    exit 0
} catch {
    Write-Host ("[失败] " + $_.Exception.Message) -ForegroundColor Red
    exit 1
} finally {
    if (-not $NoPause -and $Host.Name -eq 'ConsoleHost') {
        Write-Host "按任意键关闭窗口。"
        $null = $Host.UI.RawUI.ReadKey('NoEcho,IncludeKeyDown')
    }
}
