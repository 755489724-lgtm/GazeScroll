# Install v5.40 (gaze probe + the fixed "covered" detection) and restart the log capture.
#
# v5.40 = v5.36 anchor + the "gaze probe" recording feature (default OFF, records only,
# changes no trigger behaviour). It fixes the v5.39 cover rule, which never fired because
# the front camera's auto-exposure brightens a hand-covered frame.
# The approved anchor stays v5.36 - use install-v536.ps1 to go back to it.
#
# Usage (PowerShell):
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\install-v540.ps1
#
# NOTE: keep this file ASCII-only - Windows PowerShell 5.1 reads BOM-less .ps1 as ANSI.

param(
    [string]$Root = 'D:\ruanjian\deepseek harness',
    [int]$CaptureMinutes = 150
)

$adb = Join-Path $Root '.android-build\android-sdk\platform-tools\adb.exe'
$apk = Join-Path $Root 'apk\gazescroll-5.40-debug.apk'
$log = Join-Path $Root 'apk\v540-verify.log'

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

Write-Output "installing v5.40 on $serial ..."
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
