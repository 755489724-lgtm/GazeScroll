# Install v5.39 (the "gaze probe" test build) and restart the log capture.
#
# v5.39 = v5.36 anchor + the "gaze probe" recording feature (default OFF, records only,
# changes no trigger behaviour). The approved anchor stays v5.36 - use install-v536.ps1
# to go back to it.
#
# Usage (PowerShell):
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\install-v539.ps1
#
# Does:
#   1) find the phone (adb devices, else re-discover the wireless port through mDNS)
#   2) adb install -r -d apk\gazescroll-5.39-debug.apk  (-d allows a downgrade later)
#   3) verify versionName = 5.39
#   4) start capture-loop.ps1 in the background -> apk\v539-verify.log
#
# NOTE: keep this file ASCII-only - Windows PowerShell 5.1 reads BOM-less .ps1 as ANSI.

param(
    [string]$Root = 'D:\ruanjian\deepseek harness',
    [int]$CaptureMinutes = 150
)

$adb = Join-Path $Root '.android-build\android-sdk\platform-tools\adb.exe'
$apk = Join-Path $Root 'apk\gazescroll-5.39-debug.apk'
$log = Join-Path $Root 'apk\v539-verify.log'

if (-not (Test-Path $apk)) { throw "APK not found: $apk" }

function Find-Serial {
    foreach ($line in (& $adb devices 2>&1)) {
        if ($line -match '^(\S+:\d+)\s+device\b') { return $Matches[1] }
    }
    return $null
}

$serial = Find-Serial
if (-not $serial) {
    Write-Output 'device not connected - trying mDNS reconnect ...'
    for ($i = 0; $i -lt 10; $i++) {
        $svc = & $adb mdns services 2>&1
        foreach ($line in $svc) {
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
        Start-Sleep -Seconds 5
    }
}
if (-not $serial) { throw 'device still unreachable (keep the screen on / wireless debugging enabled)' }

Write-Output "installing v5.39 on $serial ..."
& $adb -s $serial install -r -d $apk
& $adb -s $serial shell 'dumpsys package com.example.gazescroll | grep versionName'
& $adb -s $serial shell am start -n com.example.gazescroll/.MainActivity

Write-Output "starting capture -> $log"
$loop = Join-Path $Root 'GazeScroll\tools\capture-loop.ps1'
Start-Process -FilePath 'powershell' -ArgumentList @(
    '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $loop,
    '-Out', $log, '-Minutes', "$CaptureMinutes"
) -WindowStyle Hidden

Write-Output 'done.'
