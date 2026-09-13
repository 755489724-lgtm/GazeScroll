package com.example.gazescroll

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide blackboard shared by the analyzer thread, the foreground service,
 * the overlay windows and the activity.
 *
 * Writers may be on any thread; listeners are always notified on the main
 * thread, so UI code can update views directly.
 */
object GazeRuntime {

    /** Immutable debug/HUD view of the runtime. */
    data class Snapshot(
        val serviceRunning: Boolean = false,
        /** True while the service is honouring triggers. */
        val enabled: Boolean = false,
        val faceDetected: Boolean = false,
        /** Smoothed, screen-normalised eye Y; 0 = top, 1 = bottom. Gaze debug only. */
        val eyeY: Float? = null,
        /** Un-smoothed eye Y, for comparing against the smoothed value. */
        val rawEyeY: Float? = null,
        /** Legacy gaze state machine. Always IDLE unless gazeModeEnabled. */
        val state: GazeState = GazeState.IDLE,
        /** Completed blinks seen since the service started. */
        val blinkCount: Int = 0,
        /** Swipes fired (blink or gaze). */
        val triggers: Int = 0,
        /** No face for a while: analysis is throttled to 1 fps. */
        val standby: Boolean = false,
        /** Screen is on, so the camera is actually running. */
        val analyzing: Boolean = true,

        // ---- live values, shown on the settings screen so thresholds can be
        //      chosen from real numbers instead of guesswork ----
        /** ML Kit `leftEyeOpenProbability` of the newest frame. */
        val leftEyeOpen: Float? = null,
        /** ML Kit `rightEyeOpenProbability` of the newest frame. */
        val rightEyeOpen: Float? = null,
        /** ML Kit `headEulerAngleX` of the newest frame. */
        val headAngleDeg: Float? = null,
        /** Current head-pose baseline (median of the sliding window). */
        val headBaselineDeg: Float? = null,

        val note: String = "未启动",
    )

    private val main = Handler(Looper.getMainLooper())

    /** Live config; the service and analyzer read this on every frame. */
    @Volatile
    var config: GazeConfig = GazeConfig()

    @Volatile
    var snapshot: Snapshot = Snapshot()
        private set

    private val listeners = CopyOnWriteArrayList<(Snapshot) -> Unit>()

    fun addListener(l: (Snapshot) -> Unit) {
        listeners.add(l)
        // Deliver the current value so a freshly attached view is never blank.
        main.post { l(snapshot) }
    }

    fun removeListener(l: (Snapshot) -> Unit) {
        listeners.remove(l)
    }

    /** Atomically derive the next snapshot, then notify every listener. */
    @Synchronized
    fun publish(transform: (Snapshot) -> Snapshot) {
        val next = transform(snapshot)
        snapshot = next
        main.post {
            // Copy so a listener mutating the list cannot break the loop.
            for (l in listeners.toList()) {
                runCatching { l(next) }
            }
        }
    }

    /** Convenience for the service's master on/off state. */
    fun setEnabled(enabled: Boolean) {
        publish { it.copy(enabled = enabled) }
    }
}
