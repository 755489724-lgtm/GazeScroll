package com.example.gazescroll

import android.content.Context
import android.content.SharedPreferences

/** SharedPreferences wrapper — a background utility does not need DataStore. */
object AppPrefs {

    private const val FILE = "gaze_scroll_prefs"

    private const val K_BOTTOM = "bottomZoneRatio"
    private const val K_TOP = "topZoneRatio"
    private const val K_DWELL = "dwellMs"
    private const val K_READY_TIMEOUT = "readyTimeoutMs"
    private const val K_COOLDOWN = "cooldownMs"
    private const val K_ALPHA = "smoothingAlpha"
    private const val K_INVERT = "invertY"
    private const val K_SWIPE_MS = "swipeDurationMs"

    // Trigger-mode keys.
    private const val K_BLINK_ENABLED = "blinkTriggerEnabled"
    private const val K_BLINK_COUNT = "blinkTriggerCount"
    private const val K_GAZE_MODE = "gazeModeEnabled"

    // Head-pose keys.
    private const val K_HEAD_ENABLED = "headPoseEnabled"
    private const val K_HEAD_THRESHOLD = "headPoseAngleThreshold"
    private const val K_HEAD_HOLD = "headPoseHoldMs"
    private const val K_HEAD_MOTION = "headPoseMotionWindowMs"
    private const val K_HEAD_INVERT = "headPoseInvertPitch"

    // 防误触全局冷却键。旧版本这里是 blinkCooldownMs / headPoseCooldownMs
    // 两个「各管各的」冷却，v4.3 起合并成一套全局冷却。
    private const val K_GLOBAL_COOLDOWN_ENABLED = "globalCooldownEnabled"
    private const val K_GLOBAL_COOLDOWN_MS = "globalCooldownMs"

    /** v4.2 遗留键，只用于升级时把用户原本的冷却时长继承过来。 */
    private const val K_LEGACY_BLINK_COOLDOWN = "blinkCooldownMs"
    private const val K_LEGACY_HEAD_COOLDOWN = "headPoseCooldownMs"

    // v4.4：左右扭头滑动。
    private const val K_H_SWIPE_ENABLED = "horizontalSwipeEnabled"
    private const val K_H_SWIPE_ANGLE = "horizontalSwipeAngleThreshold"
    private const val K_H_SWIPE_INVERT = "horizontalSwipeInvertYaw"

    // v4.6：张嘴点击屏幕中央（语义在 v4.5→v4.6 之间由「暂停 App」改为「点击中央」）。
    private const val K_MOUTH_TAP_ENABLED = "mouthTapEnabled"
    private const val K_MOUTH_SENSITIVITY = "mouthSensitivity"

    /** v4.5 的旧键，只用于升级时把用户的开关状态继承过来（缺省也是开）。 */
    private const val K_LEGACY_MOUTH_PAUSE = "mouthPauseEnabled"

    /** v4.5：按前台应用自适应上下滑动幅度。 */
    private const val K_ADAPTIVE_SWIPE = "adaptiveSwipeEnabled"

    /** v4.6：用户自定的列表类应用滑动幅度。 */
    private const val K_LIST_SWIPE_DISTANCE = "listSwipeDistance"

    // v4.7：全局使用翻页。
    private const val K_GLOBAL_PAGING = "globalPagingEnabled"

    // v4.8：静止锁定（防「一动不动也误触」）。
    private const val K_STATIC_LOCK_ENABLED = "staticLockEnabled"
    private const val K_STATIC_LOCK_FACTOR = "staticLockFactor"

    // Blink sensitivity keys.
    private const val K_BLINK_CLOSED_BELOW = "blinkClosedBelow"
    private const val K_BLINK_OPEN_ABOVE = "blinkOpenAbove"
    private const val K_BLINK_CLOSED_FRAMES = "blinkClosedFrames"

    /** Set once CAMERA + POST_NOTIFICATIONS are both granted and the service started. */
    private const val K_SETUP_COMPLETE = "setupComplete"

    /** Packages the user picked as paging targets. */
    private const val K_TARGET_PACKAGES = "targetPackages"

    /** Last learned head-pose baseline, reused so nodding works instantly on re-entry. */
    private const val K_HEAD_BASELINE = "headBaselineDeg"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * v4.2 -> v4.3 迁移。
     *
     * 老版本是两套「各管各的」冷却：眨眼一个 [K_LEGACY_BLINK_COOLDOWN]，点头一个
     * [K_LEGACY_HEAD_COOLDOWN]。升级成一套全局冷却时取两者较大的那个，这样无论用户
     * 之前习惯哪种触发方式，防误触的强度都不会被削弱。
     *
     * 只在用户**从未**设置过全局冷却时才回退到遗留值——否则会把新设置覆盖掉。
     */
    private fun legacyCooldownOrNull(sp: SharedPreferences): Long? {
        if (sp.contains(K_GLOBAL_COOLDOWN_MS)) return null
        val legacyBlink = sp.getLong(K_LEGACY_BLINK_COOLDOWN, 0L)
        val legacyHead = sp.getLong(K_LEGACY_HEAD_COOLDOWN, 0L)
        val legacy = maxOf(legacyBlink, legacyHead)
        if (legacy <= 0L) return null
        return GazeConfig.snapGlobalCooldown(legacy)
    }

    fun loadConfig(ctx: Context): GazeConfig {
        val sp = sp(ctx)
        val d = GazeConfig()
        return GazeConfig(            bottomZoneRatio = sp.getFloat(K_BOTTOM, d.bottomZoneRatio),
            topZoneRatio = sp.getFloat(K_TOP, d.topZoneRatio),
            dwellMs = sp.getLong(K_DWELL, d.dwellMs),
            readyTimeoutMs = sp.getLong(K_READY_TIMEOUT, d.readyTimeoutMs),
            cooldownMs = sp.getLong(K_COOLDOWN, d.cooldownMs),
            smoothingAlpha = sp.getFloat(K_ALPHA, d.smoothingAlpha),
            invertY = sp.getBoolean(K_INVERT, d.invertY),
            swipeDurationMs = sp.getLong(K_SWIPE_MS, d.swipeDurationMs),
            blinkTriggerEnabled = sp.getBoolean(K_BLINK_ENABLED, d.blinkTriggerEnabled),
            blinkTriggerCount = sp.getInt(K_BLINK_COUNT, d.blinkTriggerCount),
            gazeModeEnabled = sp.getBoolean(K_GAZE_MODE, d.gazeModeEnabled),
            headPoseEnabled = sp.getBoolean(K_HEAD_ENABLED, d.headPoseEnabled),
            // 15° was the old default; anyone still sitting on it gets migrated to
            // the new, much lighter 8° (the 4.0 sensitivity change).
            headPoseAngleThreshold = sp.getFloat(K_HEAD_THRESHOLD, d.headPoseAngleThreshold)
                .let { if (it == 15f) d.headPoseAngleThreshold else it },
            // Same for the hold time: 300 ms was the old default, 4.0 uses 200 ms,
            // and v4.8 lowers it to 150 ms so the peak no longer has to be held longer
            // than the action itself takes. Anyone still on either old value is moved
            // to the new, snappier default.
            headPoseHoldMs = sp.getLong(K_HEAD_HOLD, d.headPoseHoldMs)
                .let { if (it == 300L || it == 200L) d.headPoseHoldMs else it },
            headPoseMotionWindowMs = sp.getLong(K_HEAD_MOTION, d.headPoseMotionWindowMs),
            headPoseInvertPitch = sp.getBoolean(K_HEAD_INVERT, d.headPoseInvertPitch),
            // v4.4 的两组新功能：默认关闭，老用户升级后行为与 4.3 完全一致。
            horizontalSwipeEnabled = sp.getBoolean(K_H_SWIPE_ENABLED, d.horizontalSwipeEnabled),
            horizontalSwipeAngleThreshold = sp.getFloat(K_H_SWIPE_ANGLE, d.horizontalSwipeAngleThreshold),
            horizontalSwipeInvertYaw = sp.getBoolean(K_H_SWIPE_INVERT, d.horizontalSwipeInvertYaw),
            mouthTapEnabled = sp.getBoolean(
                K_MOUTH_TAP_ENABLED,
                sp.getBoolean(K_LEGACY_MOUTH_PAUSE, d.mouthTapEnabled),
            ),
            mouthSensitivity = sp.getString(K_MOUTH_SENSITIVITY, null)
                ?.let { name -> MouthSensitivity.entries.firstOrNull { it.name == name } }
                ?: d.mouthSensitivity,
            adaptiveSwipeEnabled = sp.getBoolean(K_ADAPTIVE_SWIPE, d.adaptiveSwipeEnabled),
            listSwipeDistance = sp.getFloat(K_LIST_SWIPE_DISTANCE, d.listSwipeDistance),
            globalPagingEnabled = sp.getBoolean(K_GLOBAL_PAGING, d.globalPagingEnabled),
            staticLockEnabled = sp.getBoolean(K_STATIC_LOCK_ENABLED, d.staticLockEnabled),
            staticLockFactor = sp.getFloat(K_STATIC_LOCK_FACTOR, d.staticLockFactor),
            // 全局冷却：默认开启、默认 1.5 秒，App 重启后保留用户设置。
            globalCooldownEnabled = sp.getBoolean(K_GLOBAL_COOLDOWN_ENABLED, d.globalCooldownEnabled),
            globalCooldownMs = sp.getLong(K_GLOBAL_COOLDOWN_MS, legacyCooldownOrNull(sp) ?: d.globalCooldownMs),
            blinkClosedBelow = sp.getFloat(K_BLINK_CLOSED_BELOW, d.blinkClosedBelow),
            blinkOpenAbove = sp.getFloat(K_BLINK_OPEN_ABOVE, d.blinkOpenAbove),
            blinkClosedFrames = sp.getInt(K_BLINK_CLOSED_FRAMES, d.blinkClosedFrames),
        ).sanitized()
    }

    fun saveConfig(ctx: Context, c: GazeConfig) {
        val s = c.sanitized()
        sp(ctx).edit()
            .putFloat(K_BOTTOM, s.bottomZoneRatio)
            .putFloat(K_TOP, s.topZoneRatio)
            .putLong(K_DWELL, s.dwellMs)
            .putLong(K_READY_TIMEOUT, s.readyTimeoutMs)
            .putLong(K_COOLDOWN, s.cooldownMs)
            .putFloat(K_ALPHA, s.smoothingAlpha)
            .putBoolean(K_INVERT, s.invertY)
            .putLong(K_SWIPE_MS, s.swipeDurationMs)
            .putBoolean(K_BLINK_ENABLED, s.blinkTriggerEnabled)
            .putInt(K_BLINK_COUNT, s.blinkTriggerCount)
            .putBoolean(K_GAZE_MODE, s.gazeModeEnabled)
            .putBoolean(K_HEAD_ENABLED, s.headPoseEnabled)
            .putFloat(K_HEAD_THRESHOLD, s.headPoseAngleThreshold)
            .putLong(K_HEAD_HOLD, s.headPoseHoldMs)
            .putLong(K_HEAD_MOTION, s.headPoseMotionWindowMs)
            .putBoolean(K_HEAD_INVERT, s.headPoseInvertPitch)
            .putBoolean(K_H_SWIPE_ENABLED, s.horizontalSwipeEnabled)
            .putFloat(K_H_SWIPE_ANGLE, s.horizontalSwipeAngleThreshold)
            .putBoolean(K_H_SWIPE_INVERT, s.horizontalSwipeInvertYaw)
            .putBoolean(K_MOUTH_TAP_ENABLED, s.mouthTapEnabled)
            .putString(K_MOUTH_SENSITIVITY, s.mouthSensitivity.name)
            .putBoolean(K_ADAPTIVE_SWIPE, s.adaptiveSwipeEnabled)
            .putFloat(K_LIST_SWIPE_DISTANCE, s.listSwipeDistance)
            .putBoolean(K_GLOBAL_PAGING, s.globalPagingEnabled)
            .putBoolean(K_STATIC_LOCK_ENABLED, s.staticLockEnabled)
            .putFloat(K_STATIC_LOCK_FACTOR, s.staticLockFactor)
            .putBoolean(K_GLOBAL_COOLDOWN_ENABLED, s.globalCooldownEnabled)
            .putLong(K_GLOBAL_COOLDOWN_MS, s.globalCooldownMs)
            .putFloat(K_BLINK_CLOSED_BELOW, s.blinkClosedBelow)
            .putFloat(K_BLINK_OPEN_ABOVE, s.blinkOpenAbove)
            .putInt(K_BLINK_CLOSED_FRAMES, s.blinkClosedFrames)
            .apply()
    }

    /**
     * True once the user has granted CAMERA + POST_NOTIFICATIONS and the service
     * has been started at least once. While this is true MainActivity never shows
     * a permission prompt or onboarding again — it just goes to the background.
     */
    fun isSetupComplete(ctx: Context): Boolean = sp(ctx).getBoolean(K_SETUP_COMPLETE, false)

    fun setSetupComplete(ctx: Context, complete: Boolean) {
        sp(ctx).edit().putBoolean(K_SETUP_COMPLETE, complete).apply()
    }

    /** Number of consecutive blinks the user wants, clamped to 1..3. */
    fun blinkTriggerCount(ctx: Context): Int =
        sp(ctx).getInt(K_BLINK_COUNT, GazeConfig().blinkTriggerCount).coerceIn(1, 3)

    fun setBlinkTriggerCount(ctx: Context, count: Int) {
        sp(ctx).edit().putInt(K_BLINK_COUNT, count.coerceIn(1, 3)).apply()
    }

    /**
     * 全局使用翻页（v4.7）。
     *
     * 单独提供一个轻量读取方法，是因为 [AppStateManager] 每次轮询都要问一次这个开关
     * （800ms 一次），不值得为此走一遍完整的 [loadConfig]。
     */
    fun isGlobalPagingEnabled(ctx: Context): Boolean =
        sp(ctx).getBoolean(K_GLOBAL_PAGING, GazeConfig().globalPagingEnabled)

    fun setGlobalPagingEnabled(ctx: Context, enabled: Boolean) {
        sp(ctx).edit().putBoolean(K_GLOBAL_PAGING, enabled).apply()
    }

    // ------------------------------------------------------- 全局冷却设置 --

    /**
     * 防误触冷却是否开启。默认开启：这是解决「一次点头触发多次翻页」的关键开关，
     * 关掉会立刻回到 v4.2 的逐帧连发行为。
     */
    fun isGlobalCooldownEnabled(ctx: Context): Boolean =
        sp(ctx).getBoolean(K_GLOBAL_COOLDOWN_ENABLED, GazeConfig().globalCooldownEnabled)

    fun setGlobalCooldownEnabled(ctx: Context, enabled: Boolean) {
        sp(ctx).edit().putBoolean(K_GLOBAL_COOLDOWN_ENABLED, enabled).apply()
    }

    /** 冷却时长（毫秒），已吸附到滑块步长并夹在 500~5000 ms。 */
    fun globalCooldownMs(ctx: Context): Long = GazeConfig.snapGlobalCooldown(
        sp(ctx).getLong(K_GLOBAL_COOLDOWN_MS, GazeConfig().globalCooldownMs),
    )

    fun setGlobalCooldownMs(ctx: Context, ms: Long) {
        sp(ctx).edit().putLong(K_GLOBAL_COOLDOWN_MS, GazeConfig.snapGlobalCooldown(ms)).apply()
    }

    // ---------------------------------------------------------- target apps --

    /**
     * Selected target packages.
     *
     * Uses `contains()` to tell "never chosen" (-> default: Douyin only) apart
     * from "the user deliberately unticked everything" (-> empty set, meaning the
     * camera never runs).
     */
    fun targetPackages(ctx: Context): Set<String> {
        val sp = sp(ctx)
        if (!sp.contains(K_TARGET_PACKAGES)) return TargetApps.defaultSelection()
        // getStringSet returns a live reference; copy before handing it out.
        return sp.getStringSet(K_TARGET_PACKAGES, emptySet())?.toSet() ?: emptySet()
    }

    fun setTargetPackages(ctx: Context, packages: Set<String>) {
        // SharedPreferences keeps the reference, so store a defensive copy.
        sp(ctx).edit().putStringSet(K_TARGET_PACKAGES, HashSet(packages)).apply()
    }

    // ------------------------------------------------------- head baseline --

    /**
     * Last known head-pose baseline.
     *
     * Reusing it means a nod works the instant you re-enter a target app, instead
     * of waiting for the detector to learn a fresh baseline.
     */
    fun headBaseline(ctx: Context): Float? {
        val prefs = sp(ctx)
        return if (prefs.contains(K_HEAD_BASELINE)) prefs.getFloat(K_HEAD_BASELINE, 0f) else null
    }

    fun setHeadBaseline(ctx: Context, degrees: Float) {
        sp(ctx).edit().putFloat(K_HEAD_BASELINE, degrees).apply()
    }
}
