@echo off
REM ============================================================
REM  One-click restore for "万能翻页" 4.2
REM
REM  Double-click after a phone reboot, or any time paging stops.
REM  Re-installs the current build, re-grants every permission the
REM  app needs, re-arms the gesture backend and launches the app.
REM ============================================================
setlocal

set "ADB=D:\ruanjian\wannengfanye\GazeScroll-v5.65\toolchain\android-sdk\platform-tools\adb.exe"
set "PKG=com.example.gazescroll"
set "APK=D:\ruanjian\deepseek harness\apk\gazescroll-4.2-debug.apk"

echo.
echo [1/7] Waiting for the phone (USB debugging must be on)...
"%ADB%" wait-for-device
"%ADB%" devices

echo.
echo [2/7] Re-installing the current build (keeps your settings)...
if exist "%APK%" (
  "%ADB%" install -r "%APK%" | findstr /C:"Success" /C:"Failure"
) else (
  echo   APK not found, skipping: %APK%
)

echo.
echo [3/7] Granting runtime permissions...
"%ADB%" shell pm grant %PKG% android.permission.CAMERA
"%ADB%" shell pm grant %PKG% android.permission.POST_NOTIFICATIONS
"%ADB%" shell pm grant %PKG% android.permission.WRITE_SECURE_SETTINGS

echo.
echo [4/7] Granting usage-access (foreground app detection)...
"%ADB%" shell appops set %PKG% GET_USAGE_STATS allow

echo.
echo [5/7] Re-enabling the gesture backend...
"%ADB%" shell settings put secure accessibility_enabled 1
"%ADB%" shell settings put secure enabled_accessibility_services %PKG%/%PKG%.GazeAccessibilityService

echo.
echo [6/7] Waking the screen and launching the app...
"%ADB%" shell input keyevent KEYCODE_WAKEUP
"%ADB%" shell am start -n %PKG%/.MainActivity >nul

echo.
echo [7/7] Waiting 12s, then reporting state...
timeout /t 12 /nobreak >nul
echo   - gesture backend:
"%ADB%" shell settings get secure enabled_accessibility_services
echo   - usage access:
"%ADB%" shell appops get %PKG% GET_USAGE_STATS
echo   - foreground service:
"%ADB%" shell dumpsys activity services %PKG% | findstr /C:"isForeground=true"
echo   - accessibility backend:
"%ADB%" shell dumpsys accessibility | findstr /C:"Bound services"
echo   - notification (待机 = waiting for a target app):
"%ADB%" shell dumpsys notification --noredact | findstr /C:"android.title=String"

echo.
echo ============================================================
echo  Done. From now on just tap the Douyin icon - paging starts
echo  by itself (chain launch).
echo.
echo  IMPORTANT: after this script, do NOT use "force stop" on
echo  万能翻页 in Android settings. Android clears an app's
echo  accessibility service on force-stop, and nothing of ours
echo  survives to notice Douyin. If that happens, run this
echo  script again (or just open the app once).
echo.
echo  If detection ever looks dead: open the app and press
echo  "手动重启服务", or use the 重启 action on the notification.
echo ============================================================
echo.
pause
