<#
.SYNOPSIS
    Compare two power-measure.ps1 runs and report what the detection chain costs.

.DESCRIPTION
    power-measure.ps1 dumps the raw batterystats; this turns two runs (A = app on,
    B = app off) into the single number the user actually wants:

        "how much battery does GazeScroll's face detection cost while scrolling?"

    Two independent estimates are computed on purpose, because batterystats reports
    ESTIMATED power from the device's power profile, not measured current:

      1. battery-level drop  -> the ground truth (level % * learned capacity)
      2. charge counter      -> the fuel-gauge reading, finer than %

    The "camera: NNN mAh" line in batterystats is a MODEL number. On this device it
    came out around 10 W for a 480x360 front-camera stream, which is not physically
    plausible for the sensor alone, so it must never be quoted on its own.

.PARAMETER Dir
    Directory containing the power-measure.ps1 output files.

.PARAMETER RunA
    Timestamp-prefixed name fragment of the "app on" run, e.g. A-app-on.

.PARAMETER RunB
    Timestamp-prefixed name fragment of the "app off" run, e.g. B-app-off.

.EXAMPLE
    .\power-compare.ps1
#>
[CmdletBinding()]
param(
    [string]$Dir = 'D:\ruanjian\wannengfanye\GazeScroll-v5.65\evidence\power-measure',
    [string]$RunA = 'A-app-on',
    [string]$RunB = 'B-app-off'
)

$ErrorActionPreference = 'Continue'

function Get-RunFiles {
    param([string]$Fragment)
    $bat = Get-ChildItem $Dir -Filter "*$Fragment.battery.txt" | Sort-Object LastWriteTime | Select-Object -Last 1
    $chg = Get-ChildItem $Dir -Filter "*$Fragment.batterystats-charged.txt" | Sort-Object LastWriteTime | Select-Object -Last 1
    if (-not $bat) { throw "no battery.txt for '$Fragment' in $Dir" }
    [pscustomobject]@{ Battery = $bat.FullName; Charged = $chg.FullName }
}

# batterystats prints "level: 71" plus "Charge counter: 2950" (uAh-like integer).
function Read-State {
    param([string]$BatteryFile)
    $t = Get-Content $BatteryFile
    $level = ($t | Select-String -Pattern '^\s*level:\s*(\d+)' | Select-Object -First 1)
    $counter = ($t | Select-String -Pattern '^\s*Charge counter:\s*(\d+)' | Select-Object -First 1)
    $temp = ($t | Select-String -Pattern '^\s*temperature:\s*(\d+)' | Select-Object -First 1)
    [pscustomobject]@{
        Level   = if ($level) { [int]$level.Matches[0].Groups[1].Value } else { $null }
        Counter = if ($counter) { [int]$counter.Matches[0].Groups[1].Value } else { $null }
        TempC   = if ($temp) { [double]$temp.Matches[0].Groups[1].Value / 10.0 } else { $null }
    }
}

# Pull one line from the "Estimated power use (mAh)" block, e.g. "camera: 105 apps: 0 ...".
function Read-Estimated {
    param([string]$ChargedFile, [string]$Name)
    if (-not $ChargedFile -or -not (Test-Path $ChargedFile)) { return $null }
    $hit = Get-Content $ChargedFile | Select-String -Pattern ("^\s*" + [regex]::Escape($Name) + ":\s*([\d.]+)") | Select-Object -First 1
    if ($hit) { return [double]$hit.Matches[0].Groups[1].Value }
    return $null
}

$fa = Get-RunFiles $RunA
$fb = Get-RunFiles $RunB
$sa = Read-State $fa.Battery
$sb = Read-State $fb.Battery

# Learned capacity is printed in the charged dump; fall back to the nominal 4500.
$cap = 4500
if ($fa.Charged -and (Test-Path $fa.Charged)) {
    $c = Get-Content $fa.Charged | Select-String -Pattern 'Last learned battery capacity:\s*(\d+)' | Select-Object -First 1
    if ($c) { $cap = [int]$c.Matches[0].Groups[1].Value }
}

Write-Host ''
Write-Host '=== GazeScroll power comparison ===' -ForegroundColor Cyan
Write-Host ("Run A ({0}): level {1}%  counter {2}  temp {3}C" -f $RunA, $sa.Level, $sa.Counter, $sa.TempC)
Write-Host ("Run B ({0}): level {1}%  counter {2}  temp {3}C" -f $RunB, $sb.Level, $sb.Counter, $sb.TempC)
Write-Host ("Learned capacity: {0} mAh" -f $cap)

Write-Host ''
Write-Host '--- Whole-device estimated power (mAh over the 10 min window) ---' -ForegroundColor Green
$names = @('screen', 'cpu', 'camera', 'audio', 'video', 'mobile_radio', 'sensors', 'gnss', 'bluetooth')
$rows = foreach ($n in $names) {
    $va = Read-Estimated $fa.Charged $n
    $vb = Read-Estimated $fb.Charged $n
    [pscustomobject]@{
        Item  = $n
        RunA  = $va
        RunB  = $vb
        Delta = if ($null -ne $va -and $null -ne $vb) { [math]::Round($va - $vb, 2) } else { $null }
    }
}
$rows | Format-Table -AutoSize

Write-Host '--- Per-uid estimated power (mAh) ---' -ForegroundColor Green
foreach ($f in @($fa.Charged, $fb.Charged)) {
    if (-not $f -or -not (Test-Path $f)) { continue }
    $tag = if ($f -eq $fa.Charged) { 'Run A' } else { 'Run B' }
    Get-Content $f | Select-String -Pattern 'UID u0a(346|181):\s*([\d.]+)' | Select-Object -First 4 | ForEach-Object {
        Write-Host ("  {0}  {1}" -f $tag, $_.Line.Trim())
    }
}

Write-Host ''
Write-Host '--- Two independent estimates of the detection cost ---' -ForegroundColor Yellow
if ($null -ne $sa.Level -and $null -ne $sb.Level) {
    $dLevel = $sa.Level - $sb.Level
    Write-Host ("level drop : A {0}% vs B {1}%  ->  A drains {2} pp more over 10 min" -f $sa.Level, $sb.Level, $dLevel)
    Write-Host ("             = {0} mAh per 10 min = {1} mAh/h = {2} W" -f `
        [math]::Round($dLevel / 100.0 * $cap, 1), `
        [math]::Round($dLevel / 100.0 * $cap * 6, 0), `
        [math]::Round($dLevel / 100.0 * $cap * 6 * 4.0 / 1000, 2))
    Write-Host ("             as a share of total drain: {0}%" -f [math]::Round(100.0 * $dLevel / [math]::Max($sa.Level, 1), 0))
}
if ($null -ne $sa.Counter -and $null -ne $sb.Counter) {
    $dCounter = $sa.Counter - $sb.Counter
    Write-Host ("charge ctr : A {0} vs B {1}  ->  A used {2} mAh more" -f $sa.Counter, $sb.Counter, $dCounter)
}
Write-Host ''
Write-Host 'NOTE: if the two estimates disagree, trust the charge counter, not the model.'
Write-Host ''
