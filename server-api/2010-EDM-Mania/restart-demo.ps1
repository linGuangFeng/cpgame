$ErrorActionPreference = 'Stop'
$Base = Split-Path -Parent $MyInvocation.MyCommand.Path
$Jar = Join-Path $Base 'dist/game-2010-evidence-gated-server.jar'
if (-not (Test-Path $Jar)) { throw '请先运行 build-and-test.sh 生成 Java API JAR。' }
Write-Host '[gid 2010] 启动证据门禁 API；Init/Config/Spin 会安全返回 EVIDENCE_REQUIRED。'
& java --add-modules jdk.httpserver '-Dserver.address=0.0.0.0' '-Dserver.port=20110' -jar $Jar
