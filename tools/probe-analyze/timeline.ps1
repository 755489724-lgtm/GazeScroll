# Quick time-series dump of one gaze-probe CSV: one line per 2-second bucket.
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File .\timeline.ps1 -Csv <file> [-Bucket 2]
param(
    [Parameter(Mandatory = $true)][string]$Csv,
    [double]$Bucket = 2.0
)

$ErrorActionPreference = 'Stop'
$lines = Get-Content $Csv -Encoding UTF8
$hdr = $null
$idx = @{}
foreach ($l in $lines) {
    if ($l.StartsWith('idx,')) { $hdr = $l.Split(','); break }
}
if (-not $hdr) { throw 'no header row' }
for ($k = 0; $k -lt $hdr.Count; $k++) { $idx[$hdr[$k]] = $k }

function Col($parts, $name) {
    $i = $idx[$name]
    if ($null -eq $i) { return [double]::NaN }
    $v = 0.0
    if ([double]::TryParse($parts[$i], [ref]$v)) { return $v }
    return [double]::NaN
}

$buckets = @{}
foreach ($l in $lines) {
    if ($l.StartsWith('#') -or $l.StartsWith('idx,')) { continue }
    if ([string]::IsNullOrWhiteSpace($l)) { continue }
    $p = $l.Split(',')
    if ($p.Count -ne $hdr.Count) { continue }
    $t = (Col $p 'elapsed') / 1000.0
    $b = [double]([math]::Floor($t / $Bucket) * $Bucket)
    if (-not $buckets.ContainsKey($b)) {
        $buckets[$b] = [pscustomobject]@{
            n = 0; face = 0; eX = 0.0; eY = 0.0; eZ = 0.0; fr = 0.0; boxCy = 0.0
            noseDx = 0.0; prox = 0.0; lux = 0.0; tex = 0.0
        }
    }
    $g = $buckets[$b]
    $g.n++
    if ((Col $p 'face') -eq 1) {
        $g.face++
        $g.eX += (Col $p 'eX'); $g.eY += (Col $p 'eY'); $g.eZ += (Col $p 'eZ')
        $g.fr += (Col $p 'faceRatio')
        $bt = Col $p 'boxT'; $bb = Col $p 'boxB'
        if ($bt -ge 0 -and $bb -ge 0) { $g.boxCy += ($bt + $bb) / 2 }
        $bl = Col $p 'boxL'; $br = Col $p 'boxR'; $bw = $br - $bl
        $nx = Col $p 'noseX'; $el = Col $p 'eyeLx'; $er = Col $p 'eyeRx'
        if ($bw -gt 0.01 -and $nx -ge 0 -and $el -ge 0 -and $er -ge 0) {
            $g.noseDx += (($nx - ($el + $er) / 2) / $bw)
        }
        $g.lux += (Col $p 'lux'); $g.tex += (Col $p 'tex')
    }
    $g.prox += (Col $p 'prox')
}

Write-Output ("file: $Csv   bucket=${Bucket}s")
"{0,6} {1,5} {2,5} {3,7} {4,7} {5,7} {6,7} {7,7} {8,8} {9,6} {10,6}" -f `
    't(s)', 'rows', 'face', 'eY', 'eX', 'eZ', 'faceR', 'boxCy', 'noseDx', 'lux', 'tex'
foreach ($b in ($buckets.Keys | Sort-Object)) {
    $g = $buckets[$b]
    if ($g.face -eq 0) {
        "{0,6:N0} {1,5} {2,5}   (no face;  prox={3:N2})" -f $b, $g.n, 0, ($g.prox / [math]::Max($g.n, 1))
        continue
    }
    $f = [double]$g.face
    "{0,6:N0} {1,5} {2,5} {3,7:N1} {4,7:N1} {5,7:N1} {6,7:N3} {7,7:N3} {8,8:N3} {9,6:N0} {10,6:N1}" -f `
        $b, $g.n, $g.face, ($g.eY / $f), ($g.eX / $f), ($g.eZ / $f), ($g.fr / $f), ($g.boxCy / $f), ($g.noseDx / $f), ($g.lux / $f), ($g.tex / $f)
}
