$watch = 'D:\work\hd\cpgame\reports\_workflow\collect-20260908\watch-gap-sample.mjs'
Start-Sleep -Seconds 45
while ($true) {
  $out = & node $watch 2>&1 | Out-String
  $out = $out.Trim()
  if ($out -match '^DONE:') { Write-Output $out; exit 0 }
  if ($out -match '^ACTION_REQUIRED:') { Write-Output $out; exit 0 }
  if ($out -match '^FAILED:') { Write-Output $out; exit 1 }
  Start-Sleep -Seconds 30
}
