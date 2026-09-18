# v5.7 自动挂测：连上手机 -> 装 v5.7 -> 抓日志 -> 自动解析
# 用法: powershell -NoProfile -File tools\watch-device.ps1 -Minutes 240
param([int]$Minutes = 240)

$ErrorActionPreference = 'Continue'
$adb  = 'D:\ruanjian\wannengfanye\GazeScroll-v5.65\toolchain\android-sdk\platform-tools\adb.exe'
$apk  = 'D:\ruanjian\wannengfanye\GazeScroll-v5.65\evidence\日志与数据\gazescroll-5.7-debug.apk'
$dir  = 'D:\ruanjian\wannengfanye\GazeScroll-v5.65\evidence\日志与数据'
$log  = Join-Path $dir 'v57-capture.log'
$ports = @(37951, 37875, 40689, 5555)
$deadline = (Get-Date).AddMinutes($Minutes)

Write-Output "[$(Get-Date -Format 'HH:mm:ss')] waiting for device (up to $Minutes min)..."

$target = $null
while ((Get-Date) -lt $deadline -and -not $target) {
    foreach ($p in $ports) {
        $serial = "192.168.3.32:$p"
        $r = & $adb connect $serial 2>&1
        if ("$r" -match 'connected to') {
            Start-Sleep -Seconds 2
            if ((& $adb -s $serial get-state 2>&1) -match 'device') { $target = $serial; break }
        }
    }
    if (-not $target) { Start-Sleep -Seconds 10 }
}

if (-not $target) { Write-Output "GAVE UP: device never became available"; exit 1 }
Write-Output "[$(Get-Date -Format 'HH:mm:ss')] connected: $target"

# ---- ensure v5.7 is installed ----
$ver = (& $adb -s $target shell dumpsys package com.example.gazescroll 2>&1 |
        Select-String 'versionName' | Select-Object -First 1) -join ''
Write-Output "installed: $ver"
if ($ver -notmatch '5\.7') {
    Write-Output "installing v5.7..."
    $out = & $adb -s $target install -r $apk 2>&1
    Write-Output ($out -join "`n")
}

# ---- fresh log capture ----
if (Test-Path $log) { Remove-Item $log -Force }
& $adb -s $target logcat -c 2>&1 | Out-Null
Write-Output "[$(Get-Date -Format 'HH:mm:ss')] capturing to $log"

$rowLimit = 60000
Write-Output "[$(Get-Date -Format 'HH:mm:ss')] capture running (up to $rowLimit lines)..."
& $adb -s $target logcat -v time -s GazeDiag:V HeadPose:V GazeA11y:V GazeCameraService:V GazeSelfCheck:V AppState:V A11yBootstrap:V 2>&1 |
    Select-Object -First $rowLimit |
    Out-File -FilePath $log -Encoding utf8
Write-Output "[$(Get-Date -Format 'HH:mm:ss')] capture done: $(if (Test-Path $log) { (Get-Item $log).Length } else { 0 }) bytes"

# ---- auto analysis ----
if (Test-Path $log) {
    $lines = Get-Content $log
    Write-Output "==== analysis: $($lines.Count) lines ===="

    Write-Output '--- tiltUp triggers (should all clear the up threshold) ---'
    $lines | Select-String 'tiltUp triggered' | Select-Object -Last 15 | ForEach-Object { $_.Line.Trim() }

    Write-Output '--- nodDown triggers (near ones should say boosted) ---'
    $lines | Select-String 'nodDown triggered' | Select-Object -Last 10 | ForEach-Object { $_.Line.Trim() }

    Write-Output '--- rejected candidates (reason= is the answer) ---'
    $lines | Select-String 'candidate rejected' | Select-Object -Last 12 | ForEach-Object { $_.Line.Trim() }

    Write-Output '--- recovery events ---'
    $lines | Select-String 'rebind reason=|forcing rebind|hard resync|contradiction|foreground change|full restart|a11y still missing' |
        Select-Object -Last 25 | ForEach-Object { $_.Line.Trim() }

    Write-Output '--- self-check (last 3) ---'
    $lines | Select-String 'GazeSelfCheck' | Select-Object -Last 3 | ForEach-Object { $_.Line.Trim() }

    Write-Output '--- errors ---'
    $err = $lines | Select-String 'FATAL|AndroidRuntime'
    if ($err) { $err | Select-Object -First 10 | ForEach-Object { $_.Line.Trim() } } else { Write-Output '(none)' }
}

Write-Output "[$(Get-Date -Format 'HH:mm:ss')] all done"
