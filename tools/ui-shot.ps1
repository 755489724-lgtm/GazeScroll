# Capture a UI screenshot from the phone.
#
# Why this exists: the v5.47 / v5.48 builds are UI-only - no detection logic changed,
# so the usual "new log field" evidence does not apply. For a UI change the checkable
# evidence is a SCREENSHOT (see HANDOVER section 1 rule 9, adapted for UI work).
#
# Usage (PowerShell):
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\ui-shot.ps1 -Out 'D:\...\data\v547-01-home.png'
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\ui-shot.ps1 -Out ... -SwipeLeft          # open settings
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\ui-shot.ps1 -Out ... -Tap 540,300        # expand a card
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\ui-shot.ps1 -Out ... -LongPress 700,300   # start a drag
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\ui-shot.ps1 -Out ... -NoLaunch            # keep current screen
#
# NOTE: keep this file ASCII-only - Windows PowerShell 5.1 reads BOM-less .ps1 as ANSI.

param(
    [string]$Root = 'D:\ruanjian\deepseek harness',
    [Parameter(Mandatory = $true)][string]$Out,
    [switch]$NoLaunch,
    [switch]$SwipeLeft,
    [int[]]$Tap = @(),
    [int[]]$LongPress = @(),
    [int]$SwipeY = 1200,
    [int]$SwipeFromX = 900,
    [int]$SwipeToX = 200,
    [int]$SleepMs = 900
)

$adb = Join-Path $Root '.android-build\android-sdk\platform-tools\adb.exe'
$pkg = 'com.example.gazescroll'

function Find-Serial {
    foreach ($line in (& $adb devices 2>&1)) {
        if ($line -match '^(\S+:\d+)\s+device\b') { return $Matches[1] }
    }
    foreach ($line in (& $adb devices 2>&1)) {
        if ($line -match '^(\S+)\s+device\b' -and $line -notmatch 'List of devices') { return $Matches[1] }
    }
    return $null
}

$serial = Find-Serial
if (-not $serial) {
    Write-Output 'device not connected - trying mDNS reconnect ...'
    for ($i = 0; $i -lt 6; $i++) {
        foreach ($line in (& $adb mdns services 2>&1)) {
            if ($line -notmatch '_adb-tls-connect') { continue }
            if ($line -notmatch '(\d+\.\d+\.\d+\.\d+:\d+)') { continue }
            $addr = $Matches[1]
            Write-Output "  connecting $addr"
            & $adb connect $addr 2>&1 | Out-Null
            Start-Sleep -Seconds 2
            $serial = Find-Serial
            if ($serial) { break }
        }
        if ($serial) { break }
        Start-Sleep -Seconds 3
    }
}
if (-not $serial) { throw 'device unreachable - enable wireless debugging on the phone (HANDOVER section 7.9)' }

if (-not $NoLaunch) {
    & $adb -s $serial shell am start -n "$pkg/.MainActivity" | Out-Null
    Start-Sleep -Milliseconds 2500
}

if ($SwipeLeft) {
    & $adb -s $serial shell input swipe $SwipeFromX $SwipeY $SwipeToX $SwipeY 220 | Out-Null
    Start-Sleep -Milliseconds $SleepMs
}

if ($LongPress.Count -eq 2) {
    # A long press is a swipe with (nearly) identical start and end points.
    & $adb -s $serial shell input swipe $LongPress[0] $LongPress[1] $LongPress[0] $LongPress[1] 900 | Out-Null
    Start-Sleep -Milliseconds $SleepMs
}

if ($Tap.Count -eq 2) {
    & $adb -s $serial shell input tap $Tap[0] $Tap[1] | Out-Null
    Start-Sleep -Milliseconds $SleepMs
}

$remote = '/sdcard/_ui_shot.png'
& $adb -s $serial shell screencap -p $remote | Out-Null
$dir = Split-Path -Parent $Out
if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
& $adb -s $serial pull $remote $Out | Out-Null
& $adb -s $serial shell rm -f $remote | Out-Null

if (Test-Path $Out) {
    Write-Output ("saved " + $Out + "  (" + [math]::Round((Get-Item $Out).Length / 1KB, 1) + " KB)")
} else {
    throw "screenshot failed: $Out"
}
