package com.example.gazescroll

import android.content.Context

/**
 * Direction of an injected swipe.
 *
 * The name always describes **which way the finger travels**, which is also the
 * direction the content moves on screen — so `UP` is "next item" in a vertical
 * feed, and `LEFT` is "next item" in a horizontal carousel.
 */
enum class SwipeDirection {
    /** Finger travels up the screen: next item (next Douyin video). */
    UP,

    /** Finger travels down the screen: previous item. */
    DOWN,

    /** Finger travels left across the screen: next item in a horizontal list. */
    LEFT,

    /** Finger travels right across the screen: previous item in a horizontal list. */
    RIGHT;

    /** 方向标签，进通知栏文案用。 */
    val label: String
        get() = when (this) {
            UP -> "上"
            DOWN -> "下"
            LEFT -> "左"
            RIGHT -> "右"
        }

    /** True for the left/right head-turn axis. */
    val isHorizontal: Boolean
        get() = this == LEFT || this == RIGHT
}

/**
 * Single entry point for injecting a swipe, with a preference order:
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
     * Inject one swipe in [direction] (vertical or horizontal).
     *
     * @param fallbackDurationMs 横向滑动用的时长；也是没有自适应参数时的纵向时长
     * @param verticalProfile 纵向滑动的幅度 / 时长（来自 [AdaptiveSwipe]）。
     *        横向滑动不参与自适应，传 null 即可，此时走固定的一套参数。
     *
     * Blocking (~150 ms via Shizuku, immediate via accessibility).
     * Call from a background thread.
     */
    fun swipe(
        ctx: Context,
        direction: SwipeDirection,
        fallbackDurationMs: Long,
        verticalProfile: VerticalSwipeProfile? = null,
    ): Boolean {
        if (ShizukuSwipeDispatcher.hasPermission()) {
            return ShizukuSwipeDispatcher.swipe(ctx, direction, fallbackDurationMs, verticalProfile)
        }
        val service = GazeAccessibilityService.instance ?: return false
        return service.swipe(direction, fallbackDurationMs, verticalProfile)
    }

    /** Convenience wrapper for the common "next video" case. */
    fun swipeUp(ctx: Context, durationMs: Long): Boolean =
        swipe(ctx, SwipeDirection.UP, durationMs)

    /**
     * 在屏幕中央注入一次单击（v4.6「张嘴 → 暂停/播放视频」用）。
     *
     * 优先级与 [swipe] 一致：先用 Shizuku，退回到无障碍服务。
     * Blocking. Call from a background thread.
     */
    fun tapCenter(ctx: Context, durationMs: Long = 60L): Boolean {
        if (ShizukuSwipeDispatcher.hasPermission()) {
            return ShizukuSwipeDispatcher.tapCenter(ctx, durationMs)
        }
        val service = GazeAccessibilityService.instance ?: return false
        return service.tapCenter(durationMs)
    }

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
