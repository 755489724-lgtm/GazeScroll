<#
.SYNOPSIS
    GazeScroll power measurement - how much battery does the face-detection chain actually use?

.DESCRIPTION
    Why this script exists (power evaluation after v5.66):

    The user wants to save power, and the first idea was "during the 2 s cooldown after a
    page turn, sleep the front camera for 1.8 s and wake it for 0.2 s". That scheme cannot
    work physically (reopening a camera takes 300-700 ms, so a 0.2 s window contains zero
    usable frames). But nobody has ever MEASURED how much of the total power draw the
    detection chain is - every number so far was an order-of-magnitude guess. Without that
    number any power optimisation is guesswork.

    Method: two 10 minute runs, compared against each other.

        Run A: GazeScroll running, scrolling Douyin for 10 minutes.
        Run B: GazeScroll stopped (or target app closed), same 10 minutes.

    The difference is approximately the detection chain cost. batterystats reports
    estimated power per uid and per sensor, so it also shows whether something else is
    draining the battery.

    This script is READ-ONLY: it resets stats and dumps data. It does not install
    anything, change settings, or touch app data.

.PARAMETER Serial
    adb target serial (the first column of `adb devices`). May be omitted with one device.

.PARAMETER Label
    Name of this run, used in the output file names, e.g. A-app-on / B-app-off.

.PARAMETER DurationSec
    Length of the timed window in seconds. Default 600 = 10 minutes. Use 60 for a dry run.

.PARAMETER Adb
    Path to adb.exe. Defaults to the one bundled with the project toolchain.

.PARAMETER OutDir
    Output directory. Defaults to evidence\power-measure\ next to the source tree.

.EXAMPLE
    # Run A: GazeScroll on, 10 minutes
    .\power-measure.ps1 -Serial 192.168.3.32:41234 -Label A-app-on

.EXAMPLE
    # Run B: stop GazeScroll first, then
    .\power-measure.ps1 -Serial 192.168.3.32:41234 -Label B-app-off

.NOTES
    Three things must be true or the two runs are not comparable:
      1. Screen brightness fixed, auto-brightness off.
      2. Same network, same kind of video feed in both runs.
      3. Phone NOT charging (batterystats uses a different accounting while charging).

    ENCODING - IMPORTANT:
    This file is deliberately pure ASCII. PowerShell 5.1 reads a .ps1 without a BOM as
    ANSI/GBK, so Chinese comments turn into mojibake and stray quote characters inside
    them break the whole parse ("Missing ')' in function parameter list"). Editor tools
    that rewrite this file drop the BOM, so keep the script ASCII-only. If you ever add
    non-ASCII text, re-save it as UTF-8 WITH BOM:

        $p='<path>\power-measure.ps1'
        $t=[System.IO.File]::ReadAllText($p,[System.Text.UTF8Encoding]::new($false))
        [System.IO.File]::WriteAllText($p,$t,[System.Text.UTF8Encoding]::new($true))
#>
[CmdletBinding()]
param(
    [string]$Serial = '',
    [Parameter(Mandatory = $true)]
    [string]$Label,
    [int]$DurationSec = 600,
    [string]$Adb = 'D:\ruanjian\wannengfanye\GazeScroll-v5.65\toolchain\android-sdk\platform-tools\adb.exe',
    [string]$OutDir = 'D:\ruanjian\wannengfanye\GazeScroll-v5.65\evidence\power-measure'
)

# NOTE: do NOT set $ErrorActionPreference to SilentlyContinue here. It was tried as a way
# to mute adb's stderr noise, but in PS 5.1 it also SWALLOWS `throw` inside a function -
# the device gate below fired and execution happily continued. adb failures are handled
# by inspecting the exit code explicitly instead.
$ErrorActionPreference = 'Continue'
$script:AdbLastError = ''

if (-not (Test-Path $Adb)) { throw "adb not found: $Adb" }
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }

# Every adb call carries -s explicitly; Serial may be empty with a single device.
$dev = @()
if ($Serial) { $dev = @('-s', $Serial) }

function Invoke-Adb {
    # The parameter must NOT be named $Args - that is a PowerShell automatic variable
    # and the parameter list fails to parse.
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$AdbArguments)
    $out = & $Adb @dev @AdbArguments 2>&1
    if ($LASTEXITCODE -ne 0) { $script:AdbLastError = ($out | Out-String).Trim() }
    $out
}

function Assert-Device {
    # Flat inline check, no helper call in the condition and no reliance on a muted
    # preference: PS 5.1 silently drops `throw` inside a function when
    # $ErrorActionPreference is SilentlyContinue, which let a dead device through.
    $text = (& $Adb @dev shell getprop sys.boot_completed 2>&1 | Out-String).Trim()
    $code = $LASTEXITCODE
    if ($code -eq 0 -and $text -eq '1') { return }
    $hint = "Connect the phone first (the port is under Developer options -> Wireless debugging and changes every time):`n" +
            "  & '$Adb' connect 192.168.3.32:<port>`n" +
            "To find the port:`n" +
            "  & '$Adb' mdns services        # look for the _adb-tls-connect line`n" +
            "Note: while the phone screen is off or wireless debugging is off, mDNS is empty - ping succeeding proves nothing."
    throw "Device unusable (adb exit=$code):`n`n$hint"
}

Write-Host ''
Write-Host '=== GazeScroll power measurement ===' -ForegroundColor Cyan
Write-Host ("Label        : {0}" -f $Label)
Write-Host ("Duration     : {0} s" -f $DurationSec)
Write-Host ("Output dir   : {0}" -f $OutDir)

# ------------------------------------------------------------- preconditions --
Assert-Device

$model  = (Invoke-Adb shell getprop ro.product.model | Out-String).Trim()
$charge = (Invoke-Adb shell dumpsys battery | Select-String -Pattern '^\s*(level|status|AC powered|USB powered|Wireless powered):' | Out-String).Trim()
Write-Host ''
Write-Host ("Device model : {0}" -f $model)
Write-Host 'Battery      :'
$charge -split "`r?`n" | ForEach-Object { if ($_.Trim()) { Write-Host ("  " + $_.Trim()) } }

if ($charge -match 'AC powered:\s*true' -or $charge -match 'USB powered:\s*true') {
    Write-Warning 'Phone is charging - the numbers will be distorted. Unplug it and re-run.'
}

# The screen must be on. With the screen off the camera is already released, so the
# measurement would look great and mean nothing.
$wake = (Invoke-Adb shell dumpsys power | Select-String -Pattern 'mWakefulness=' | Select-Object -First 1 | Out-String).Trim()
Write-Host ("Screen       : {0}" -f $wake)
if ($wake -notmatch 'Awake') { Write-Warning 'Screen is not Awake - turn it on, unlock, and stay in the target app before measuring.' }

# -------------------------------------------------------------- reset stats --
Write-Host ''
Write-Host 'Resetting batterystats...' -ForegroundColor Yellow
Invoke-Adb shell dumpsys batterystats --reset | Out-Null

Write-Host ''
Write-Host ("Timed window starts now: {0} s. Keep the screen on and scroll Douyin normally." -f $DurationSec) -ForegroundColor Green
Write-Host 'No interaction with the phone is needed; this script just waits.'
Write-Host ''

$startLocal = Get-Date
$startUtc   = $startLocal.ToUniversalTime().ToString('yyyy-MM-dd-HHmmss')
$deadline   = $startLocal.AddSeconds($DurationSec)

# Report progress every 30 s so it is obvious the script is still alive.
while ((Get-Date) -lt $deadline) {
    $left = [int]($deadline - (Get-Date)).TotalSeconds
    Write-Host ("  {0,4} s left..." -f $left)
    Start-Sleep -Seconds ([Math]::Min(30, [Math]::Max(1, $left)))
}

Write-Host ''
Write-Host 'Window finished, collecting data...' -ForegroundColor Yellow

# ------------------------------------------------------------------ capture --
$prefix = Join-Path $OutDir ("{0}-{1}" -f (Get-Date $startLocal -Format 'yyyyMMdd-HHmmss'), $Label)

function Save-Dump {
    param([string[]]$AdbArgs, [string]$Path)
    # Must redirect through cmd: PowerShell's > writes UTF-16 and garbles the text.
    $argLine = ($AdbArgs | ForEach-Object { if ($_ -match '\s') { '"' + $_ + '"' } else { $_ } }) -join ' '
    cmd /c "`"$Adb`" $argLine > `"$Path`" 2>&1"
    $len = (Get-Item $Path -ErrorAction SilentlyContinue).Length
    if (-not $len) { Write-Warning "Empty output file (device dropped?): $Path" }
    else { Write-Host ("  saved {0} ({1} bytes)" -f (Split-Path $Path -Leaf), $len) }
}

# --charged reports only the window since the last unplug, which is exactly the reset window.
Save-Dump -AdbArgs (@('shell', 'dumpsys', 'batterystats', '--charged')) -Path "$prefix.batterystats-charged.txt"
Save-Dump -AdbArgs (@('shell', 'dumpsys', 'batterystats', '--charged', 'com.example.gazescroll')) -Path "$prefix.app-gazescroll.txt"
Save-Dump -AdbArgs (@('shell', 'dumpsys', 'batterystats', '--charged', 'com.ss.android.ugc.aweme')) -Path "$prefix.app-douyin.txt"
Save-Dump -AdbArgs (@('shell', 'dumpsys', 'battery')) -Path "$prefix.battery.txt"
Save-Dump -AdbArgs (@('shell', 'dumpsys', 'media.camera')) -Path "$prefix.camera.txt"

# Explicit time window: batterystats accepts --start / --end in UTC, format yyyy-MM-dd-HHmmss.
$endUtc = (Get-Date).ToUniversalTime().ToString('yyyy-MM-dd-HHmmss')
Save-Dump -AdbArgs (@('shell', 'dumpsys', 'batterystats', '--start', $startUtc, '--end', $endUtc)) -Path "$prefix.window-utc.txt"

# ------------------------------------------------------------------- digest --
Write-Host ''
Write-Host '=== Quick digest (details are in the txt files) ===' -ForegroundColor Cyan

$appFile = "$prefix.app-gazescroll.txt"
if (Test-Path $appFile) {
    $lines = Get-Content $appFile
    Write-Host ''
    Write-Host '[GazeScroll com.example.gazescroll]' -ForegroundColor Green
    ($lines | Select-String -Pattern 'Estimated power use|Camera|sensor|Foreground|Wake lock|wake_lock' |
        Select-Object -First 25) | ForEach-Object { Write-Host ("  " + $_.Line.Trim()) }
}

$batFile = "$prefix.battery.txt"
if (Test-Path $batFile) {
    Write-Host ''
    Write-Host '[Battery level]' -ForegroundColor Green
    (Get-Content $batFile | Select-String -Pattern '^\s*(level|status):') | ForEach-Object { Write-Host ("  " + $_.Line.Trim()) }
}

$chargedFile = "$prefix.batterystats-charged.txt"
if (Test-Path $chargedFile) {
    Write-Host ''
    Write-Host '[Whole-device estimated power / sensors]' -ForegroundColor Green
    $all = Get-Content $chargedFile
    $hit = ($all | Select-String -Pattern 'Estimated power use \(mAh\)' | Select-Object -First 1)
    if ($hit) {
        $i = $hit.LineNumber
        $all[($i - 1)..([Math]::Min($i + 22, $all.Count - 1))] | ForEach-Object { Write-Host ("  " + $_) }
    }
    ($all | Select-String -Pattern 'sensor|camera' | Select-Object -First 15) | ForEach-Object { Write-Host ("  " + $_.Line.Trim()) }
}

Write-Host ''
Write-Host ("Done. After both runs, hand me the files in {0} and I will compute the difference." -f $OutDir) -ForegroundColor Cyan
Write-Host ("Window start (local) {0}   end (UTC) {1}" -f $startLocal.ToString('HH:mm:ss'), $endUtc)
Write-Host ''
