package com.example.gazescroll

import android.content.Context

/** Direction of an injected vertical swipe. */
enum class SwipeDirection {
    /** Finger travels up the screen: next item (next Douyin video). */
    UP,

    /** Finger travels down the screen: previous item. */
    DOWN,
}

/**
 * Single entry point for injecting a vertical swipe, with a preference order:
 *
 *  1. **Shizuku** — runs `input swipe` as the shell user.
 *  2. **AccessibilityService** — a real gesture via `dispatchGesture`, enabled
 *     automatically by [AccessibilityBootstrap] through WRITE_SECURE_SETTINGS.
 *
 * Whichever is ready first wins, so the app keeps working even if only one of
 * the two can be set up on a given device.
 */
object SwipeInjector {

    const val BACKEND_SHIZUKU = "Shizuku"
    const val BACKEND_ACCESSIBILITY = "无障碍服务"
    const val BACKEND_NONE = "不可用"

    /** Which mechanism is currently usable. */
    fun activeBackend(ctx: Context): String = when {
        ShizukuSwipeDispatcher.hasPermission() -> BACKEND_SHIZUKU
        GazeAccessibilityService.isConnected() ||
            AccessibilityBootstrap.isServiceEnabled(ctx) -> BACKEND_ACCESSIBILITY
        else -> BACKEND_NONE
    }

    fun isReady(ctx: Context): Boolean = activeBackend(ctx) != BACKEND_NONE

    /**
     * Inject one vertical swipe.
     *
     * Blocking (~150 ms via Shizuku, immediate via accessibility).
     * Call from a background thread.
     */
    fun swipe(ctx: Context, direction: SwipeDirection, durationMs: Long): Boolean {
        if (ShizukuSwipeDispatcher.hasPermission()) {
            return ShizukuSwipeDispatcher.swipe(ctx, direction, durationMs)
        }
        val service = GazeAccessibilityService.instance ?: return false
        return service.swipe(direction, durationMs)
    }

    /** Convenience wrapper for the common "next video" case. */
    fun swipeUp(ctx: Context, durationMs: Long): Boolean =
        swipe(ctx, SwipeDirection.UP, durationMs)

    /**
     * Best-effort, fully automatic setup: if the app holds
     * WRITE_SECURE_SETTINGS (granted over adb) and Shizuku is not available,
     * turn the accessibility service on — and repair it if it is enabled but
     * has stopped being bound.
     */
    fun bootstrap(ctx: Context) {
        if (ShizukuSwipeDispatcher.hasPermission()) return
        AccessibilityBootstrap.repairIfNeeded(ctx)
    }

    /** Called when a swipe failed, so the backend gets a chance to recover. */
    fun repair(ctx: Context) {
        AccessibilityBootstrap.repairIfNeeded(ctx)
    }
}
