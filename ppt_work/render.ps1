param([string]$f, [string]$out)
$pp = New-Object -ComObject PowerPoint.Application
$pres = $pp.Presentations.Open($f, $true, $false, $false)
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force $out | Out-Null
$pres.Export($out, "PNG", 1920, 1080)
$pres.Close(); $pp.Quit()
