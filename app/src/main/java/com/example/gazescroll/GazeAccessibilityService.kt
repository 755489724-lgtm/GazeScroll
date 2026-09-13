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

        /** Spec: the gesture lasts 100 ms. */
        const val DEFAULT_SWIPE_MS = 100L

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
     * Swipe straight down the middle of the screen in [direction].
     *
     * Returns false when the system refused the gesture (another gesture in
     * flight, or the service is not connected).
     */
    fun swipe(direction: SwipeDirection, durationMs: Long = DEFAULT_SWIPE_MS): Boolean {
        val bounds = screenBounds()
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return false

        val (fromRatio, toRatio) = when (direction) {
            SwipeDirection.UP -> SWIPE_FROM_RATIO to SWIPE_TO_RATIO
            SwipeDirection.DOWN -> SWIPE_TO_RATIO to SWIPE_FROM_RATIO
        }

        val path = Path().apply {
            moveTo(w * 0.5f, h * fromRatio)
            lineTo(w * 0.5f, h * toRatio)
        }
        val stroke = GestureDescription.StrokeDescription(
            path,
            0L,
            durationMs.coerceIn(60L, 1000L),
        )
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val ok = runCatching { dispatchGesture(gesture, null, null) }.getOrDefault(false)
        android.util.Log.i(
            "GazeA11y",
            "swipe $direction (w/2, ${h * fromRatio}) -> (w/2, ${h * toRatio}) ${durationMs}ms = $ok",
        )
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