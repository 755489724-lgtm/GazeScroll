# 把手机装回 v5.36（用户认可的锚点版本）并重启抓取。
#
# 用法（PowerShell）:
#   powershell -NoProfile -ExecutionPolicy Bypass -File .\install-v536.ps1
#
# 做的事:
#   1) 找到手机（先用 adb devices，找不到就用 mDNS 重新 connect）
#   2) adb install -r -d apk\gazescroll-5.36-debug.apk（-d 允许降级：当前可能是 5.37/5.38）
#   3) 核对 versionName = 5.36
#   4) 后台起 capture-loop.ps1，日志写 apk\v536-anchor-verify.log
#
# 注意: 这个文件保持 ASCII，Windows PowerShell 5.1 读无 BOM 的 .ps1 会按 ANSI 解释。

param(
    [string]$Root = 'D:\ruanjian\deepseek harness',
    [int]$CaptureMinutes = 150
)

$adb = Join-Path $Root '.android-build\android-sdk\platform-tools\adb.exe'
$apk = Join-Path $Root 'apk\gazescroll-5.36-debug.apk'
$log = Join-Path $Root 'apk\v536-anchor-verify.log'

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

Write-Output "installing v5.36 on $serial ..."
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
