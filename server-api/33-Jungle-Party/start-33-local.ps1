param([ValidateRange(0, 59999)][int]$Port = 0)
& (Join-Path $PSScriptRoot 'restart-demo.ps1') -Port $Port -NoPause
exit $LASTEXITCODE
