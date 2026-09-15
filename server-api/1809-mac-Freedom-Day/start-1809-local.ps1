param(
    [Parameter(Mandatory = $true)]
    [ValidateRange(50000, 59999)]
    [int]$Port
)

$project = Split-Path -Parent $MyInvocation.MyCommand.Path
$jar = Join-Path $project 'dist\controller.jar'
$config = Join-Path $project 'dist\controller.properties'
$publish = Join-Path (Split-Path -Parent (Split-Path -Parent $project)) 'publish\1809-mac-Freedom-Day'
if (-not (Test-Path -LiteralPath $jar)) { throw "缺少 Controller JAR: $jar" }

$hostAddress = '127.0.0.1'
$gameUrl = "http://${hostAddress}:$Port/?ai=luck_single_10229&btt=1&gid=2260&l=pt&language=pt-br&sip=${hostAddress}%3A$Port&t=local-replay&token=local-replay"
Write-Host "Freedom Day 试玩: $gameUrl"
& java -jar $jar --port $Port --config $config --publish $publish
