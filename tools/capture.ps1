# Capture GazeScroll logs. Finds the current wireless-debugging port via mDNS,
# because that port changes every time wireless debugging is re-enabled.
# Usage: powershell -NoProfile -File tools\capture.ps1 -Out apk\v58-turn.log -Minutes 20
param(
    [string]$Out = 'D:\ruanjian\deepseek harness\apk\capture.log',
    [int]$Minutes = 15,
    [int]$RowLimit = 200000
)

$ErrorActionPreference = 'Continue'
$adb = 'D:\ruanjian\deepseek harness\.android-build\android-sdk\platform-tools\adb.exe'
# Our log lines contain the degree sign and Chinese text, so the capture file MUST be read
# back with -Encoding UTF8. Reading it as ANSI turns them into mojibake and makes the
# diagnostics unreadable, which cost a debugging round once already.
$utf8 = New-Object System.Text.UTF8Encoding($false)

$serial = $null

# 1) Use an already-connected device if there is one.
$devices = & $adb devices 2>&1
foreach ($line in $devices) {
    if ($line -match '^(\S+:\d+)\s+device') { $serial = $Matches[1]; break }
}

# 2) Otherwise ask mDNS for the current port.
if (-not $serial) {
    for ($i = 0; $i -lt 12 -and -not $serial; $i++) {
        $svc = & $adb mdns services 2>&1
        foreach ($line in $svc) {
            if ($line -notmatch '_adb-tls-connect') { continue }
            if ($line -notmatch '(\d+\.\d+\.\d+\.\d+:\d+)') { continue }
            $addr = $Matches[1]
            & $adb connect $addr 2>&1 | Out-Null
            Start-Sleep -Seconds 2
            $d2 = & $adb devices 2>&1
            foreach ($l2 in $d2) {
                if ($l2 -match '^(\S+:\d+)\s+device') { $serial = $Matches[1]; break }
            }
            if ($serial) { break }
        }
        if (-not $serial) { Start-Sleep -Seconds 3 }
    }
}

if (-not $serial) { Write-Output 'NO DEVICE'; exit 1 }
Write-Output "serial=$serial"

if (Test-Path $Out) { Remove-Item $Out -Force }
& $adb -s $serial logcat -c 2>&1 | Out-Null
Write-Output "[$(Get-Date -Format 'HH:mm:ss')] capturing $Minutes min -> $Out"

$job = Start-Job -ScriptBlock {
    param($adbPath, $ser, $outFile)
    # NOTE: do NOT pipe this through Select-Object -First. That cmdlet buffers the whole
    # stream before emitting anything, so stopping the job discards everything that was
    # buffered -- an earlier version silently produced a 30-second capture instead of the
    # 20 minutes it claimed. logcat is allowed to stream straight into the file.
    & $adbPath -s $ser logcat -v time -s GazeDiag:V HeadPose:V GazeA11y:V GazeCameraService:V GazeSelfCheck:V AppState:V A11yBootstrap:V 2>&1 |
        Out-File -FilePath $outFile -Encoding utf8
} -ArgumentList $adb, $serial, $Out

$deadline = (Get-Date).AddMinutes($Minutes)
while ((Get-Date) -lt $deadline -and $job.State -eq 'Running') {
    Start-Sleep -Seconds 5
}
Stop-Job $job -ErrorAction SilentlyContinue
Remove-Job $job -Force -ErrorAction SilentlyContinue

$size = 0
if (Test-Path $Out) { $size = (Get-Item $Out).Length }
Write-Output "[$(Get-Date -Format 'HH:mm:ss')] done: $size bytes"
