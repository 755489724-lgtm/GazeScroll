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
    private const val K_BLINK_COOLDOWN = "blinkCooldownMs"
    private const val K_GAZE_MODE = "gazeModeEnabled"

    // Head-pose keys.
    private const val K_HEAD_ENABLED = "headPoseEnabled"
    private const val K_HEAD_THRESHOLD = "headPoseAngleThreshold"
    private const val K_HEAD_HOLD = "headPoseHoldMs"
    private const val K_HEAD_MOTION = "headPoseMotionWindowMs"
    private const val K_HEAD_COOLDOWN = "headPoseCooldownMs"
    private const val K_HEAD_INVERT = "headPoseInvertPitch"

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
            blinkCooldownMs = sp.getLong(K_BLINK_COOLDOWN, d.blinkCooldownMs),
            gazeModeEnabled = sp.getBoolean(K_GAZE_MODE, d.gazeModeEnabled),
            headPoseEnabled = sp.getBoolean(K_HEAD_ENABLED, d.headPoseEnabled),
            // 15° was the old default; anyone still sitting on it gets migrated to
            // the new, much lighter 8° (the 4.0 sensitivity change).
            headPoseAngleThreshold = sp.getFloat(K_HEAD_THRESHOLD, d.headPoseAngleThreshold)
                .let { if (it == 15f) d.headPoseAngleThreshold else it },
            // Same for the hold time: 300 ms was the old default, 4.0 uses 200 ms.
            headPoseHoldMs = sp.getLong(K_HEAD_HOLD, d.headPoseHoldMs)
                .let { if (it == 300L) d.headPoseHoldMs else it },
            headPoseMotionWindowMs = sp.getLong(K_HEAD_MOTION, d.headPoseMotionWindowMs),
            headPoseCooldownMs = sp.getLong(K_HEAD_COOLDOWN, d.headPoseCooldownMs),
            headPoseInvertPitch = sp.getBoolean(K_HEAD_INVERT, d.headPoseInvertPitch),
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
            .putLong(K_BLINK_COOLDOWN, s.blinkCooldownMs)
            .putBoolean(K_GAZE_MODE, s.gazeModeEnabled)
            .putBoolean(K_HEAD_ENABLED, s.headPoseEnabled)
            .putFloat(K_HEAD_THRESHOLD, s.headPoseAngleThreshold)
            .putLong(K_HEAD_HOLD, s.headPoseHoldMs)
            .putLong(K_HEAD_MOTION, s.headPoseMotionWindowMs)
            .putLong(K_HEAD_COOLDOWN, s.headPoseCooldownMs)
            .putBoolean(K_HEAD_INVERT, s.headPoseInvertPitch)
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
