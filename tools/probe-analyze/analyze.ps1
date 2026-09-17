# Analyze a gaze-probe CSV produced by the GazeScroll v5.39/v5.40 test feature.
#
# Prints per-phase statistics so we can see which geometric quantity separates
# "eyes on the screen" phases from "eyes off the screen" phases.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\analyze.ps1 -Csv ..\..\..\apk\probe-xxxx.csv
#   powershell ... -File .\analyze.ps1 -Csv <file> -FacesOnly $false     # include cover rows too
#   powershell ... -File .\analyze.ps1 -Csv <file> -Feature eY           # one feature, phase by phase
#
# NOTE: keep this file ASCII-only (Windows PowerShell 5.1 reads BOM-less .ps1 as ANSI).

param(
    [Parameter(Mandatory = $true)][string]$Csv,
    [string[]]$Features = @(
        'face', 'luma', 'tex', 'prox',
        'faceRatio', 'boxCx', 'boxCy', 'boxW',
        'eX', 'eY', 'eZ',
        'eyeMidBoxX', 'eyeMidBoxY', 'noseBoxX', 'noseBoxY',
        'eyeNoseDx', 'eyeNoseDy', 'eyeSpan', 'eyeNoseGap',
        'openL', 'openR'
    ),
    [bool]$FacesOnly = $true,
    [string]$Feature = ''
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path $Csv)) { throw "csv not found: $Csv" }

# ---------------------------------------------------------------- parse ------

$lines = Get-Content $Csv -Encoding UTF8
$header = $null
$rows = New-Object System.Collections.Generic.List[object]
$meta = New-Object System.Collections.Generic.List[string]

foreach ($line in $lines) {
    if ($line.StartsWith('#')) { $meta.Add($line); continue }
    if (-not $header) { $header = $line.Split(','); continue }
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    $parts = $line.Split(',')
    if ($parts.Count -ne $header.Count) { continue }
    $row = [ordered]@{}
    for ($i = 0; $i -lt $header.Count; $i++) { $row[$header[$i]] = $parts[$i] }
    $rows.Add([pscustomobject]$row)
}

if ($rows.Count -eq 0) { throw 'no data rows' }

Write-Output "file   : $Csv"
foreach ($m in $meta) { Write-Output "meta   : $m" }
Write-Output "rows   : $($rows.Count)"

# --------------------------------------------------- derived quantities ------

function To-Float([string]$s) {
    $v = 0.0
    if ([double]::TryParse($s, [ref]$v)) { return $v }
    return [double]::NaN
}

$derived = foreach ($r in $rows) {
    $boxL = To-Float $r.boxL; $boxT = To-Float $r.boxT
    $boxR = To-Float $r.boxR; $boxB = To-Float $r.boxB
    $boxW = $boxR - $boxL
    $boxH = $boxB - $boxT
    $noseX = To-Float $r.noseX; $noseY = To-Float $r.noseY
    $eyeLx = To-Float $r.eyeLx; $eyeLy = To-Float $r.eyeLy
    $eyeRx = To-Float $r.eyeRx; $eyeRy = To-Float $r.eyeRy

    $eyeMidX = if ($eyeLx -ge 0 -and $eyeRx -ge 0) { ($eyeLx + $eyeRx) / 2 } else { [double]::NaN }
    $eyeMidY = if ($eyeLy -ge 0 -and $eyeRy -ge 0) { ($eyeLy + $eyeRy) / 2 } else { [double]::NaN }

    # Landmark positions *inside the face box* (0 = box left/top, 1 = box right/bottom).
    $eyeMidBoxX = if ($boxW -gt 0.01 -and $eyeMidX -ne [double]::NaN) { ($eyeMidX - $boxL) / $boxW } else { [double]::NaN }
    $eyeMidBoxY = if ($boxH -gt 0.01 -and $eyeMidY -ne [double]::NaN) { ($eyeMidY - $boxT) / $boxH } else { [double]::NaN }
    $noseBoxX = if ($boxW -gt 0.01 -and $noseX -ge 0) { ($noseX - $boxL) / $boxW } else { [double]::NaN }
    $noseBoxY = if ($boxH -gt 0.01 -and $noseY -ge 0) { ($noseY - $boxT) / $boxH } else { [double]::NaN }

    # "nose relative to the eye line", in face-width units: the classic head-pose proxy
    # that does not care about the phone moving around.
    $eyeNoseDx = if ($eyeMidBoxX -ne [double]::NaN -and $noseBoxX -ne [double]::NaN) { $noseBoxX - $eyeMidBoxX } else { [double]::NaN }
    $eyeNoseDy = if ($eyeMidBoxY -ne [double]::NaN -and $noseBoxY -ne [double]::NaN) { $noseBoxY - $eyeMidBoxY } else { [double]::NaN }
    $eyeSpan = if ($boxW -gt 0.01 -and $eyeLx -ge 0 -and $eyeRx -ge 0) { [math]::Abs($eyeRx - $eyeLx) / $boxW } else { [double]::NaN }
    $eyeNoseGap = if ($eyeMidY -ne [double]::NaN -and $noseY -ge 0) { ($noseY - $eyeMidY) / [math]::Max($boxH, 0.01) } else { [double]::NaN }

    [pscustomobject]@{
        idx = [int]$r.idx
        wall = $r.wall
        phase = [int]$r.phase
        face = To-Float $r.face
        cover = To-Float $r.cover
        luma = To-Float $r.luma
        tex = To-Float $r.tex
        prox = To-Float $r.prox
        faceRatio = To-Float $r.faceRatio
        boxCx = if ($boxW -gt 0) { ($boxL + $boxR) / 2 } else { [double]::NaN }
        boxCy = if ($boxH -gt 0) { ($boxT + $boxB) / 2 } else { [double]::NaN }
        boxW = $boxW
        eX = To-Float $r.eX
        eY = To-Float $r.eY
        eZ = To-Float $r.eZ
        eyeMidBoxX = $eyeMidBoxX
        eyeMidBoxY = $eyeMidBoxY
        noseBoxX = $noseBoxX
        noseBoxY = $noseBoxY
        eyeNoseDx = $eyeNoseDx
        eyeNoseDy = $eyeNoseDy
        eyeSpan = $eyeSpan
        eyeNoseGap = $eyeNoseGap
        openL = To-Float $r.openL
        openR = To-Float $r.openR
    }
}

$derived = @($derived)
if ($FacesOnly) { $derived = @($derived | Where-Object { $_.face -ge 1 }) }
if ($derived.Count -eq 0) { throw 'no rows left after the face filter' }

$phases = $derived | Group-Object phase | Sort-Object { [int]$_.Name }

Write-Output ("rows after filter (FacesOnly=$FacesOnly): {0}" -f $derived.Count)
Write-Output ''
Write-Output 'phase sizes:'
foreach ($g in $phases) {
    $facePct = 100.0 * ($g.Group | Where-Object { $_.face -ge 1 }).Count / $g.Count
    Write-Output ("  phase {0,2}  n={1,5}  face={2,5:N1}%" -f $g.Name, $g.Count, $facePct)
}
Write-Output ''

function Get-Stats($group, [string]$name) {
    $vals = @($group | ForEach-Object { $_.$name } | Where-Object { $_ -ne [double]::NaN -and -not [double]::IsNaN($_) })
    if ($vals.Count -eq 0) { return $null }
    $m = ($vals | Measure-Object -Average -Minimum -Maximum)
    $mean = $m.Average
    $var = 0.0
    foreach ($v in $vals) { $var += ($v - $mean) * ($v - $mean) }
    $sd = if ($vals.Count -gt 1) { [math]::Sqrt($var / ($vals.Count - 1)) } else { 0.0 }
    [pscustomobject]@{ n = $vals.Count; mean = $mean; sd = $sd; min = $m.Minimum; max = $m.Maximum }
}

if ($Feature) {
    # One feature: phase-by-phase mean +- sd + range. Handy for eyeballing a separator.
    Write-Output ("feature: {0}" -f $Feature)
    Write-Output ("{0,-4} {1,>6} {2,>10} {3,>9} {4,>9} {5,>9}" -f 'ph', 'n', 'mean', 'sd', 'min', 'max')
    foreach ($g in $phases) {
        $s = Get-Stats $g.Group $Feature
        if (-not $s) { Write-Output ("{0,-4} {1,>6}  (no data)" -f $g.Name, 0); continue }
        Write-Output ("{0,-4} {1,>6} {2,>10:N3} {3,>9:N3} {4,>9:N3} {5,>9:N3}" -f $g.Name, $s.n, $s.mean, $s.sd, $s.min, $s.max)
    }
    return
}

# Matrix: one row per feature, one column pair per phase (mean / sd).
$head = "{0,-12}" -f 'feature'
foreach ($g in $phases) { $head += ("{0,18}" -f ("ph" + $g.Name)) }
Write-Output $head
Write-Output ('-' * $head.Length)

foreach ($f in $Features) {
    $line = "{0,-12}" -f $f
    foreach ($g in $phases) {
        $s = Get-Stats $g.Group $f
        $cell = if ($s) { "{0:N3}+-{1:N3}" -f $s.mean, $s.sd } else { '-' }
        $line += ("{0,18}" -f $cell)
    }
    Write-Output $line
}

Write-Output ''
Write-Output 'tip: -Feature eY  (or faceRatio / noseBoxX / eyeNoseDx ...) prints mean/sd/min/max per phase.'
