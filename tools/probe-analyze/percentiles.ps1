# Percentile dump for the "gaze gate" study: for each labeled phase of each CSV, print the
# 5th / 50th / 95th percentile of the quantities a gate could use.
#
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File .\percentiles.ps1 -Csv a.csv,b.csv
param([Parameter(Mandatory = $true)][string]$Csv, [string]$Features = 'eY,eX,eZ,faceRatio,boxCy,openL,openR,eyeNoseDx')

$ErrorActionPreference = 'Stop'

function Pct($sorted, [double]$p) {
    if ($sorted.Count -eq 0) { return [double]::NaN }
    $i = [int][math]::Round($p * ($sorted.Count - 1))
    return $sorted[$i]
}

foreach ($file in $Csv.Split(',')) {
    $file = $file.Trim()
    if (-not (Test-Path $file)) { Write-Output "missing: $file"; continue }
    $lines = Get-Content $file -Encoding UTF8
    $hdr = $null
    foreach ($l in $lines) { if ($l.StartsWith('idx,')) { $hdr = $l.Split(','); break } }
    if (-not $hdr) { continue }
    $idx = @{}
    for ($k = 0; $k -lt $hdr.Count; $k++) { $idx[$hdr[$k]] = $k }

    $rows = @()
    foreach ($l in $lines) {
        if ($l.StartsWith('#') -or $l.StartsWith('idx,') -or [string]::IsNullOrWhiteSpace($l)) { continue }
        $p = $l.Split(',')
        if ($p.Count -ne $hdr.Count) { continue }
        if ($p[$idx['face']] -ne '1') { continue }
        $rows += , $p
    }

    Write-Output ''
    Write-Output ("=== {0}  ({1} face rows) ===" -f (Split-Path -Leaf $file), $rows.Count)
    $head = "{0,-11}" -f 'feature'
    $phases = ($rows | ForEach-Object { $_[$idx['phase']] } | Sort-Object -Unique)
    foreach ($ph in $phases) { $head += ("{0,26}" -f ("phase $ph (p5/p50/p95)")) }
    Write-Output $head

    foreach ($f in $Features.Split(',')) {
        $f = $f.Trim()
        $line = "{0,-11}" -f $f
        foreach ($ph in $phases) {
            $vals = @($rows | Where-Object { $_[$idx['phase']] -eq $ph } |
                ForEach-Object { $v = 0.0; if ([double]::TryParse($_[$idx[$f]], [ref]$v)) { $v } } |
                Sort-Object)
            $cell = "{0:N2} / {1:N2} / {2:N2}" -f (Pct $vals 0.05), (Pct $vals 0.5), (Pct $vals 0.95)
            $line += ("{0,26}" -f $cell)
        }
        Write-Output $line
    }
}
