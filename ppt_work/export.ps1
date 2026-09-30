param([string]$f, [string]$pdf, [string]$out)
$pp = New-Object -ComObject PowerPoint.Application
$pres = $pp.Presentations.Open($f, $true, $false, $false)
$pres.SaveAs($pdf, 32)
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force $out | Out-Null
$pres.Export($out, "PNG", 1920, 1080)
$pres.Close(); $pp.Quit()
