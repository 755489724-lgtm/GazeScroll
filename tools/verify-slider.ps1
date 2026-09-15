$ErrorActionPreference = 'Continue'
$adb = "D:\ruanjian\deepseek harness\.android-build\android-sdk\platform-tools\adb.exe"
$d = "192.168.3.32:40689"
$prefs = "/data/data/com.example.gazescroll/shared_prefs/gaze_scroll_prefs.xml"
$tmp = "D:\ruanjian\deepseek harness\GazeScroll\tools\gz_prefs_slider.xml"

function Apply-Distance([string]$value, [string]$label) {
    $xml = "<?xml version=`"1.0`" encoding=`"utf-8`" standalone=`"yes`" ?>`n" +
    "<map>`n" +
    "    <boolean name=`"setupComplete`" value=`"true`" />`n" +
    "    <set name=`"targetPackages`">`n" +
    "        <string>com.ss.android.ugc.aweme</string>`n" +
    "        <string>com.sina.weibo</string>`n" +
    "    </set>`n" +
    "    <boolean name=`"headPoseEnabled`" value=`"true`" />`n" +
    "    <float name=`"headPoseAngleThreshold`" value=`"6.0`" />`n" +
    "    <boolean name=`"headPoseInvertPitch`" value=`"true`" />`n" +
    "    <boolean name=`"globalCooldownEnabled`" value=`"true`" />`n" +
    "    <long name=`"globalCooldownMs`" value=`"2000`" />`n" +
    "    <boolean name=`"horizontalSwipeEnabled`" value=`"true`" />`n" +
    "    <float name=`"horizontalSwipeAngleThreshold`" value=`"20.0`" />`n" +
    "    <boolean name=`"mouthTapEnabled`" value=`"true`" />`n" +
    "    <string name=`"mouthSensitivity`">MEDIUM</string>`n" +
    "    <boolean name=`"adaptiveSwipeEnabled`" value=`"true`" />`n" +
    "    <float name=`"listSwipeDistance`" value=`"$value`" />`n" +
    "</map>`n"
    Set-Content -Path $tmp -Value $xml -Encoding UTF8 -NoNewline

    & $adb -s $d shell am force-stop com.example.gazescroll 2>&1 | Out-Null
    Start-Sleep -Seconds 2
    & $adb -s $d push $tmp /data/local/tmp/gz_s.xml 2>&1 | Out-Null
    & $adb -s $d shell "chmod 644 /data/local/tmp/gz_s.xml" 2>&1 | Out-Null
    & $adb -s $d shell "run-as com.example.gazescroll cp /data/local/tmp/gz_s.xml $prefs" 2>&1 | Out-Null
    & $adb -s $d shell "rm -f /data/local/tmp/gz_s.xml" 2>&1 | Out-Null

    & $adb -s $d logcat -c 2>&1 | Out-Null
    & $adb -s $d shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.example.gazescroll/.MainActivity --ez com.example.gazescroll.OPEN_SETTINGS true 2>&1 | Out-Null
    Start-Sleep -Seconds 9
    $line = (& $adb -s $d logcat -d -s GazeDiag:V 2>&1 | Select-Object -Last 1) -join ''
    $m = [regex]::Match($line, 'listSwipe=(\S+).*?swipe=(\S+)')
    Write-Output ("[{0}] 写入 listSwipeDistance={1} -> 日志 listSwipe={2} swipe={3}" -f $label, $value, $m.Groups[1].Value, $m.Groups[2].Value)
}

Apply-Distance "0.50" "上限50%"
Apply-Distance "0.10" "下限10%"
Apply-Distance "0.26" "默认26%"
Remove-Item $tmp -Force -ErrorAction SilentlyContinue

# 顺便验证越界值会被夹回合法区间
Apply-Distance "0.95" "越界95%"
Apply-Distance "0.26" "恢复默认"

Write-Output "=== 最终 prefs ==="
& $adb -s $d shell "run-as com.example.gazescroll cat $prefs" 2>&1 | Select-String -Pattern "mouthTap|listSwipeDistance|adaptiveSwipe" | ForEach-Object { $_.Line.Trim() }
