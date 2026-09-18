package com.example.gazescroll

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log

/**
 * Keeps [GazeAccessibilityService] enabled by writing the secure settings directly.
 *
 * Why this exists: Android 13+ (and HyperOS on top of it) marks sideloaded apps as
 * having "restricted settings", which greys out the accessibility toggle in the
 * Settings UI — the user sees "已拒绝此应用获取敏感权限" and there is no way to
 * flip it without hunting down the MIUI-optimisation switch.
 *
 * That restriction lives in the Settings *app*, not in the framework.
 * AccessibilityManagerService still honours a direct write to
 * `enabled_accessibility_services`, and WRITE_SECURE_SETTINGS is a development
 * permission that `adb shell pm grant` can hand over with no user interaction.
 *
 * The second job here is *repair*: when the app process is force-stopped (or
 * killed by the battery manager), Android marks the accessibility service as
 * crashed and never rebinds it. [repairIfNeeded] detects "enabled in settings
 * but not actually connected" and toggles the entry to force a rebind.
 */
object AccessibilityBootstrap {

    private const val TAG = "A11yBootstrap"

    private const val KEY_ACCESSIBILITY_ENABLED = "accessibility_enabled"
    private const val KEY_ENABLED_SERVICES = "enabled_accessibility_services"

    /** The system needs a moment between the off and on writes to notice. */
    private const val REBIND_DELAY_MS = 600L

    private val handler = Handler(Looper.getMainLooper())

    fun canWriteSecureSettings(ctx: Context): Boolean =
        runCatching {
            ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    /**
     * 这个状态下 App **自己修不了**，只能请用户手动来一下（v5.62）。
     *
     * 判据是「设置里开着 + 实例不在 + 没有 WRITE_SECURE_SETTINGS」：
     *
     *  - 设置里开着、实例也在 → 一切正常，不用管；
     *  - 设置里开着、实例不在 → 上面两条自愈路径都走不了（[forceRebind] /
     *    [repairIfNeeded] 的第一句就是 `canWriteSecureSettings` 检查，没有就直接 return）。
     *    过去这种状态只剩一行日志，服务「看着在跑、手势却到不了目标 App」，
     *    用户体感就是「突然坏了」。现在 [GazeCameraService] 用它来决定要不要弹提醒。
     *  - 设置里没开 → 那是从没配好，属于首次引导的事（见 MainActivity 的引导卡片），
     *    不算"掉线"，这里返回 false。
     *
     * 有 WRITE_SECURE_SETTINGS 时返回 false：那种情况下自愈会真的发生，
     * 弹提醒只会变成噪音。
     */
    fun needsManualRepair(ctx: Context): Boolean =
        canWriteSecureSettings(ctx).not() &&
            isServiceEnabled(ctx) &&
            !GazeAccessibilityService.isConnected()

    /** Reads the system setting, so it is correct even before the service binds. */
    fun isServiceEnabled(ctx: Context): Boolean {
        val expected = componentId(ctx)
        val enabled = readEnabledServices(ctx) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    /**
     * Adds our service to the enabled list, preserving every other entry.
     *
     * Returns true when the write went through. A successful write only means the
     * setting changed — whether the framework actually binds the service is a
     * separate question, answered by [GazeAccessibilityService.isConnected].
     */
    fun enableService(ctx: Context): Boolean {
        if (!canWriteSecureSettings(ctx)) {
            Log.w(TAG, "no WRITE_SECURE_SETTINGS: cannot self-enable")
            return false
        }

        val expected = componentId(ctx)
        val entries = currentEntries(ctx).toMutableList()
        if (entries.none { it.equals(expected, ignoreCase = true) }) {
            entries.add(expected)
        }

        return runCatching {
            Settings.Secure.putString(ctx.contentResolver, KEY_ENABLED_SERVICES, entries.joinToString(":"))
            Settings.Secure.putString(ctx.contentResolver, KEY_ACCESSIBILITY_ENABLED, "1")
            Log.i(TAG, "enabled accessibility service: $entries")
            true
        }.getOrElse {
            Log.w(TAG, "failed to write accessibility settings", it)
            false
        }
    }

    /**
     * 强制把无障碍服务重新绑定一次（v5.5）。
     *
     * ## 为什么需要它，以及它和 [repairIfNeeded] 的区别
     *
     * [repairIfNeeded] 第一句就是 `if (GazeAccessibilityService.isConnected()) return`，
     * 而 `isConnected()` 只是 `instance != null`。**实例存在不等于连接可用**：
     * 长时间息屏 / 系统省电回收后，无障碍连接可能已经失效，但 `instance` 仍是旧的引用。
     *
     * 那种状态下：
     *  - `repairIfNeeded()` 认为"已连接"，**什么都不做**；
     *  - `dispatchGesture` 注入失败，只记一行日志就结束了；
     *  - 于是服务在跑、通知正常、摄像头也在分析，**但手势根本到不了目标 App**。
     *
     * 这正是"息屏再开后必须下拉状态栏"的成因——下拉会产生一批窗口事件，让系统重新激活
     * 那个失效的连接。这里不等用户去下拉，直接把"关掉再打开"这件事做掉。
     */
    fun forceRebind(ctx: Context, reason: String) {
        if (ShizukuSwipeDispatcher.hasPermission()) return
        if (!canWriteSecureSettings(ctx)) {
            Log.w(TAG, "forceRebind($reason): no WRITE_SECURE_SETTINGS, cannot rebind")
            return
        }
        Log.w(TAG, "forceRebind($reason): toggling accessibility entry to force a rebind")
        runCatching {
            Settings.Secure.putString(
                ctx.contentResolver,
                KEY_ENABLED_SERVICES,
                currentEntries(ctx)
                    .filterNot { it.equals(componentId(ctx), ignoreCase = true) }
                    .joinToString(":"),
            )
        }.onFailure { Log.w(TAG, "forceRebind: disable failed", it) }

        val appContext = ctx.applicationContext
        handler.postDelayed({ enableService(appContext) }, REBIND_DELAY_MS)
    }

    /**
     * Makes sure the service is enabled AND actually bound.
     *
     * Skips straight through when Shizuku is the active backend, since the
     * accessibility service is only a fallback.
     */
    fun repairIfNeeded(ctx: Context) {
        if (ShizukuSwipeDispatcher.hasPermission()) return
        if (!canWriteSecureSettings(ctx)) return

        if (GazeAccessibilityService.isConnected()) return

        if (!isServiceEnabled(ctx)) {
            enableService(ctx)
            return
        }

        // Enabled in settings but not bound: the classic "app was force-stopped"
        // state. Toggling the entry off and back on makes the
        // AccessibilityManagerService rebind it.
        Log.i(TAG, "enabled but not bound — forcing a rebind")
        runCatching {
            Settings.Secure.putString(
                ctx.contentResolver,
                KEY_ENABLED_SERVICES,
                currentEntries(ctx)
                    .filterNot { it.equals(componentId(ctx), ignoreCase = true) }
                    .joinToString(":"),
            )
        }

        val appContext = ctx.applicationContext
        handler.postDelayed({ enableService(appContext) }, REBIND_DELAY_MS)
    }

    private fun currentEntries(ctx: Context): List<String> =
        readEnabledServices(ctx).orEmpty().split(':').filter { it.isNotBlank() }

    private fun readEnabledServices(ctx: Context): String? =
        runCatching {
            Settings.Secure.getString(ctx.contentResolver, KEY_ENABLED_SERVICES)
        }.getOrNull()

    private fun componentId(ctx: Context): String =
        ComponentName(ctx, GazeAccessibilityService::class.java).flattenToString()
}
