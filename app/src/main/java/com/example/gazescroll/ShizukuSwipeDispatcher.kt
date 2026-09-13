package com.example.gazescroll

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku

/**
 * Injects the swipe-up gesture through Shizuku instead of an AccessibilityService.
 *
 * Why: HyperOS / MIUI refuses to let a sideloaded app hold an accessibility
 * service ("已拒绝此应用获取敏感权限"), and the toggle cannot be reached without
 * finding the MIUI-optimisation switch. Shizuku is authorised over ADB once and
 * then hands the app shell (UID 2000) privileges, which include the ability to
 * inject input events.
 *
 * How the gesture is actually performed: rather than rebuilding
 * `InputManager.injectInputEvent` (a non-SDK API that would need a hidden-API
 * bypass and a hand-built MotionEvent parcel), this runs the platform's own
 * `input swipe` command through `IShizukuService#newProcess`, i.e. as shell.
 * On this device `/system/bin/input` is a two-line `cmd input "$@"` wrapper, so
 * the whole injection costs about 50 ms on top of the gesture itself — measured
 * at ~150 ms wall clock for a 100 ms swipe.
 */
object ShizukuSwipeDispatcher {

    private const val TAG = "ShizukuSwipe"

    /** Shizuku's own package name. */
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    /** Request code for Shizuku's permission dialog. */
    const val REQUEST_CODE = 4201

    /** Swipe start / end as a fraction of screen height (spec: 0.8 -> 0.2). */
    private const val FROM_RATIO = 0.8f
    private const val TO_RATIO = 0.2f

    /** True once Shizuku has delivered its binder (i.e. the service is running). */
    fun isBinderAvailable(): Boolean =
        runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun isInstalled(ctx: Context): Boolean =
        runCatching {
            ctx.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        }.getOrDefault(false)

    fun hasPermission(): Boolean =
        runCatching {
            isBinderAvailable() &&
                !Shizuku.isPreV11() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    /** Shows Shizuku's permission dialog. Must be called from an Activity. */
    fun requestPermission() {
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }
            .onFailure { Log.w(TAG, "requestPermission failed", it) }
    }

    /** Opens the Shizuku app so the user can start the service. */
    fun launchShizukuApp(ctx: Context): Boolean =
        runCatching {
            val intent = ctx.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            if (intent == null) {
                false
            } else {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                true
            }
        }.getOrDefault(false)

    /** Human-readable status for the UI. */
    fun statusText(ctx: Context): String = when {
        !isInstalled(ctx) -> "未安装"
        !isBinderAvailable() -> "未运行（需用 ADB 启动）"
        hasPermission() -> "已授权"
        else -> "待授权（点此申请）"
    }

    /**
     * One vertical swipe through Shizuku's shell process.
     *
     * Blocking: ~150 ms. Call from a background thread.
     * Returns false when Shizuku is unavailable, unauthorised, or the command failed.
     */
    fun swipe(ctx: Context, direction: SwipeDirection, durationMs: Long): Boolean {
        if (!hasPermission()) {
            Log.w(TAG, "swipe skipped: no Shizuku permission")
            return false
        }

        val (w, h) = screenSize(ctx)
        if (w <= 0 || h <= 0) return false

        val (fromRatio, toRatio) = when (direction) {
            SwipeDirection.UP -> FROM_RATIO to TO_RATIO
            SwipeDirection.DOWN -> TO_RATIO to FROM_RATIO
        }

        val x = w / 2
        val y1 = (h * fromRatio).toInt()
        val y2 = (h * toRatio).toInt()
        val duration = durationMs.coerceIn(30L, 2000L)
        val command = "input swipe $x $y1 $x $y2 $duration"

        return try {
            val service = IShizukuService.Stub.asInterface(Shizuku.getBinder())
            val process = service.newProcess(arrayOf("sh", "-c", command), null, null)
            val exit = process.waitFor()
            if (exit == 0) {
                Log.i(TAG, "swipe $direction ok: $command")
                true
            } else {
                Log.w(TAG, "swipe command exited $exit: $command")
                false
            }
        } catch (t: Throwable) {
            Log.w(TAG, "swipe failed: $command", t)
            false
        }
    }

    /** Real display size, including the area behind the status/navigation bars. */
    private fun screenSize(ctx: Context): Pair<Int, Int> {
        val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return 0 to 0
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val point = android.graphics.Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(point)
            point.x to point.y
        }
    }
}
