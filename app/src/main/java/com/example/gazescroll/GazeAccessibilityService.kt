package com.example.gazescroll

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Injects vertical swipes through an AccessibilityService.
 *
 * This is the fallback path used when Shizuku is unavailable. HyperOS refuses to
 * let the *user* enable this service through the Settings UI for a sideloaded
 * app, but the framework itself still honours the setting - so
 * [AccessibilityBootstrap] writes it directly using WRITE_SECURE_SETTINGS,
 * which adb can grant with no user interaction.
 *
 * The service declares only `canPerformGestures`; it does not request
 * `canRetrieveWindowContent`, so it cannot read what is on screen.
 */
class GazeAccessibilityService : AccessibilityService() {

    companion object {
        /** Swipe end points as a fraction of screen height (spec: 0.8 <-> 0.2). */
        private const val SWIPE_FROM_RATIO = 0.8f
        private const val SWIPE_TO_RATIO = 0.2f

        /**
         * 水平滑动的起止位置（占屏幕宽度比例）。留出边缘手势区，避免被系统的
         * 返回手势截走。
         */
        private const val H_FROM_RATIO = 0.80f
        private const val H_TO_RATIO = 0.20f

        /** Spec: the gesture lasts 100 ms. */
        const val DEFAULT_SWIPE_MS = 100L

        /** 屏幕中央点击的默认时长；足够短，平台会当成一次 tap 而不是滑动。 */
        const val TAP_DURATION_MS = 60L

        /**
         * 纵向滑动的路径点数（v5.1）。
         *
         * 一条直线 ≡ 一个采样点，很多 App 的滚动识别只看得到「一次跳变」而不认；补足中间
         * 点后系统会派发多次 MOVE 事件，与真实手指一致。6 个点在 150ms 的手势里约每 25ms
         * 一次更新，足够让 VelocityTracker 算出速度。
         */
        private const val SWIPE_WAYPOINTS = 6

        @Volatile
        var instance: GazeAccessibilityService? = null
            private set

        fun isConnected(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // Reconnecting after a kill: make sure the camera service comes back with
        // us, otherwise the gesture backend would be alive but nothing would ever
        // analyse a frame.
        GazeCameraService.ensureRunning(this)
        // …and answer "is a target app already in front?" immediately, instead of
        // waiting up to one poll interval for the first tick.
        AppStateManager.pollNow(this)
        // The system usually delivers the current window state right after
        // connect; until then AppStateManager fails open.
        AppStateManager.onForegroundPackage(this, AppStateManager.foregroundPackage)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /**
     * Foreground-app tracking.
     *
     * `TYPE_WINDOW_STATE_CHANGED` fires whenever the window in front changes, and
     * its package name is the only reliable way left to learn which app the user
     * is actually looking at (`getRunningTasks` has been closed off for years and
     * `rootInActiveWindow` would need `canRetrieveWindowContent`, which we
     * deliberately do not request).
     *
     * The package is reported to [AppStateManager], which decides whether the
     * camera pipeline should run at all.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val type = event.eventType
        // TYPE_WINDOWS_CHANGED matters as much as TYPE_WINDOW_STATE_CHANGED:
        // bringing an already-running app back to the front does not always
        // produce a state change, but the window list always changes.
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            return
        }
        val packageName = event.packageName?.toString() ?: return
        AppStateManager.onForegroundPackage(this, packageName)
    }

    override fun onInterrupt() = Unit

    /** Convenience wrapper for the common "next video" case. */
    fun swipeUp(durationMs: Long = DEFAULT_SWIPE_MS): Boolean =
        swipe(SwipeDirection.UP, durationMs)

    /**
     * Swipe through the screen in [direction] (vertical or horizontal).
     *
     * @param fallbackDurationMs 横向滑动的时长；纵向在没有 [verticalProfile] 时也用它
     * @param verticalProfile 自适应给出的纵向幅度 / 时长（见 [AdaptiveSwipe]）
     *
     * Returns false when the system refused the gesture (another gesture in
     * flight, or the service is not connected).
     */
    fun swipe(
        direction: SwipeDirection,
        fallbackDurationMs: Long = DEFAULT_SWIPE_MS,
        verticalProfile: VerticalSwipeProfile? = null,
    ): Boolean {
        val bounds = screenBounds()
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return false

        val path = Path()
        val durationMs: Long
        if (direction.isHorizontal) {
            // 横向不参与自适应，保持 v4.4 以来的固定参数。
            durationMs = fallbackDurationMs
            val y = h * 0.5f
            val fromX = w * if (direction == SwipeDirection.LEFT) H_FROM_RATIO else H_TO_RATIO
            val toX = w * if (direction == SwipeDirection.LEFT) H_TO_RATIO else H_FROM_RATIO
            path.moveTo(fromX, y)
            path.lineTo(toX, y)
        } else {
            // 纵向：幅度和时长按前台应用动态决定；比例乘屏幕高度，不写死像素。
            val profile = verticalProfile ?: VerticalSwipeProfile(
                name = "fixed",
                fromRatio = SWIPE_FROM_RATIO,
                toRatio = SWIPE_TO_RATIO,
                durationMs = fallbackDurationMs,
            )
            durationMs = profile.durationMs
            val (fromRatio, toRatio) = profile.pathFor(direction)
            val x = w * 0.5f
            val y1 = h * fromRatio
            val y2 = h * toRatio

            // 关键：**不要只画一条直线**。
            //
            // `dispatchGesture` 的一条直线相当于「一个采样点从起点直接跳到终点」，而多数
            // App 的滚动手势识别（VelocityTracker + touch slop）期望的是多次坐标更新。
            // 单段直线在自家设置页、桌面、微博都够用，但在抖音这类对输入更挑剔的全屏
            // 播放器里会被忽略——现象正是「日志显示 swipe ok=true，但页面纹丝不动」。
            //
            // 这里补上均匀分布的中间点，让系统按真实手指轨迹派发多次 MOVE 事件。
            path.moveTo(x, y1)
            for (i in 1 until SWIPE_WAYPOINTS) {
                val t = i.toFloat() / SWIPE_WAYPOINTS
                path.lineTo(x, y1 + (y2 - y1) * t)
            }
        }

        val stroke = GestureDescription.StrokeDescription(
            path,
            0L,
            durationMs.coerceIn(30L, 2000L),
        )
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val ok = runCatching { dispatchGesture(gesture, null, null) }.getOrDefault(false)
        android.util.Log.i(
            "GazeA11y",
            "swipe $direction pts=${if (direction.isHorizontal) 2 else SWIPE_WAYPOINTS} " +
                "${durationMs}ms = $ok",
        )
        return ok
    }

    /**
     * 在屏幕中央注入一次单击（v4.6 的「张嘴点击」用）。
     *
     * 用 `dispatchGesture` 实现，路径起点终点都在屏幕中心、duration 很短，等价于一次
     * 点击。抖音这类全屏播放器收到后就是暂停 / 播放。
     *
     * 返回 false 说明系统拒绝了这次手势（服务未连接、或另一个手势还在飞行中）。
     */
    fun tapCenter(durationMs: Long = TAP_DURATION_MS): Boolean {
        val bounds = screenBounds()
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return false

        val path = Path().apply {
            moveTo(w * 0.5f, h * 0.5f)
            lineTo(w * 0.5f, h * 0.5f)
        }
        val stroke = GestureDescription.StrokeDescription(
            path,
            0L,
            durationMs.coerceIn(20L, 500L),
        )
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val ok = runCatching { dispatchGesture(gesture, null, null) }.getOrDefault(false)
        android.util.Log.i("GazeA11y", "tap center (${w / 2}, ${h / 2}) = $ok")
        return ok
    }

    /** Real display size, including the area behind the status/navigation bars. */
    private fun screenBounds(): Rect {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wm.currentWindowMetrics.bounds
        } else {
            val point = Point()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealSize(point)
            Rect(0, 0, point.x, point.y)
        }
    }
}