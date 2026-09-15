$ErrorActionPreference = 'Continue'
$adb = "D:\ruanjian\deepseek harness\.android-build\android-sdk\platform-tools\adb.exe"
$d = "192.168.3.32:37875"
$tmp = "D:\ruanjian\deepseek harness\apk\gesturetest"
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

function Shot([string]$name) {
    & $adb -s $d shell screencap -p /sdcard/gt.png 2>&1 | Out-Null
    & $adb -s $d pull /sdcard/gt.png "$tmp\$name.png" 2>&1 | Out-Null
}

function ImgHash([string]$name) {
    $b = [System.IO.File]::ReadAllBytes("$tmp\$name.png")
    $md5 = [System.Security.Cryptography.MD5]::Create().ComputeHash($b)
    $hex = ($md5 | ForEach-Object { $_.ToString('x2') }) -join ''
    return @{ len = $b.Length; md5 = $hex }
}

# Open settings screen (has a ScrollView, so a real swipe scrolls it)
& $adb -s $d shell am force-stop com.example.gazescroll 2>&1 | Out-Null
Start-Sleep -Seconds 2
& $adb -s $d shell am start -n com.example.gazescroll/.MainActivity --ez com.example.gazescroll.OPEN_SETTINGS true 2>&1 | Out-Null
Start-Sleep -Seconds 10

Shot "before"
$h1 = ImgHash "before"
Write-Output ("shot A : {0} bytes  md5={1}" -f $h1.len, $h1.md5)

Write-Output ""
Write-Output "inject one UP swipe (39% / 150ms, 6 waypoints)"
& $adb -s $d shell "am broadcast -a com.example.gazescroll.action.TEST_SWIPE -p com.example.gazescroll --ef from 0.70 --ef to 0.30 --el duration 150" 2>&1 | Out-Null
Start-Sleep -Seconds 3

Shot "after"
$h2 = ImgHash "after"
Write-Output ("shot B : {0} bytes  md5={1}" -f $h2.len, $h2.md5)

Write-Output ""
if ($h1.md5 -ne $h2.md5) {
    Write-Output "RESULT: screen CHANGED -> waypoint gesture really works (ScrollView scrolled)"
} else {
    Write-Output "RESULT: screen IDENTICAL -> gesture did NOT take effect"
}

& $adb -s $d logcat -d 2>&1 | Select-String -Pattern "manual swipe" | Select-Object -Last 2 | ForEach-Object { $_.Line }
