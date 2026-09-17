# Capture GazeScroll logs with automatic reconnect + append.
#
# Why this exists: the plain capture.ps1 streams once and dies the moment the
# wireless-debugging connection drops (which happens whenever the screen goes
# off). A drop in the middle of a hand-labelled test run silently truncated the
# log once already. This version keeps retrying, re-discovers the (ever
# changing) wireless port through mDNS, and APPENDS so nothing is lost.
#
# Usage: powershell -NoProfile -File tools\capture-loop.ps1 -Out apk\v59.log -Minutes 40
param(
    [string]$Out = 'D:\ruanjian\deepseek harness\apk\capture-loop.log',
    [int]$Minutes = 30
)

$ErrorActionPreference = 'Continue'
$adb = 'D:\ruanjian\deepseek harness\.android-build\android-sdk\platform-tools\adb.exe'
$tags = 'GazeDiag:V HeadPose:V Blink:V Tilt:V PhoneMotion:V RefPoint:V GazeA11y:V GazeCameraService:V GazeSelfCheck:V AppState:V A11yBootstrap:V GazeProbe:V'

function Find-Serial {
    param([string]$AdbPath)
    foreach ($line in (& $AdbPath devices 2>&1)) {
        if ($line -match '^(\S+:\d+)\s+device\b') { return $Matches[1] }
    }
    return $null
}

function Connect-Any {
    param([string]$AdbPath)
    # mDNS gives the current port; wireless debugging reassigns it every time.
    for ($i = 0; $i -lt 8; $i++) {
        $svc = & $AdbPath mdns services 2>&1
        foreach ($line in $svc) {
            if ($line -notmatch '_adb-tls-connect') { continue }
            if ($line -notmatch '(\d+\.\d+\.\d+\.\d+:\d+)') { continue }
            $addr = $Matches[1]
            & $AdbPath connect $addr 2>&1 | Out-Null
            Start-Sleep -Seconds 2
            $s = Find-Serial -AdbPath $AdbPath
            if ($s) { return $s }
        }
        Start-Sleep -Seconds 3
    }
    return $null
}

$deadline = (Get-Date).AddMinutes($Minutes)
$round = 0

while ((Get-Date) -lt $deadline) {
    $serial = Find-Serial -AdbPath $adb
    if (-not $serial) { $serial = Connect-Any -AdbPath $adb }

    if (-not $serial) {
        Add-Content -Path $Out -Value "[$(Get-Date -Format 'HH:mm:ss')] device not reachable, retrying" -Encoding utf8
        Start-Sleep -Seconds 5
        continue
    }

    $round++
    Add-Content -Path $Out -Value "[$(Get-Date -Format 'HH:mm:ss')] streaming from $serial (round $round)" -Encoding utf8

    # No -c: keep whatever the buffer still holds, so a gap while disconnected
    # is recovered whenever the buffer has not wrapped yet.
    & $adb -s $serial logcat -v time -s $tags.Split(' ') 2>&1 |
        Out-File -FilePath $Out -Encoding utf8 -Append

    Add-Content -Path $Out -Value "[$(Get-Date -Format 'HH:mm:ss')] stream ended, reconnecting" -Encoding utf8
    Start-Sleep -Seconds 2
}

Add-Content -Path $Out -Value "[$(Get-Date -Format 'HH:mm:ss')] capture window finished" -Encoding utf8
