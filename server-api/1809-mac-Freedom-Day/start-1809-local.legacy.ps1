param(
    [int]$Port = 18090
)

$base = 'D:\work\hd\cpgame2'
$bundledPython = 'C:\Users\333\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
$python = if (Test-Path -LiteralPath $bundledPython) {
    $bundledPython
} else {
    (Get-Command python -ErrorAction Stop).Source
}
$static = Join-Path $base 'resources\1809-mac-Freedom-Day\all-languages\static.cpgame.io\fixed'
$captures = Join-Path $base 'api\1809-mac-Freedom-Day'
$server = Join-Path $base 'tools\local_mirror_server.py'

$gameUrl = "http://127.0.0.1:$Port/?ai=luck_single_10229&btt=1&gid=2260&l=pt&language=pt-br&sip=127.0.0.1%3A$Port&t=local-replay&token=local-replay"
$controlUrl = "http://127.0.0.1:$Port/local-control.html"
Write-Host "Game:    $gameUrl"
Write-Host "Control: $controlUrl"

& $python $server --root $static --captures $captures --port $Port
