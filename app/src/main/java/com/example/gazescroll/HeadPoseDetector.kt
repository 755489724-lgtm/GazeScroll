package com.example.gazescroll

import android.util.Log
import java.util.Arrays
import kotlin.math.abs

/** Which head movement was recognised. */
enum class HeadGesture(
    /** Which way the feed scrolls for this movement. */
    val swipe: SwipeDirection,
) {
    /** Chin down — "next" is up in the feed, so this scrolls to the previous video. */
    NOD_DOWN(SwipeDirection.DOWN),

    /** Chin up — scrolls to the next video. */
    TILT_UP(SwipeDirection.UP),
}

/**
 * Head-pose (pitch) detector: a light nod down / tilt up.
 *
 * Reads ML Kit's `headEulerAngleX`. Per ML Kit's reference a **positive** euler X
 * means the face is turned **upward**, so relative to the baseline:
 *
 *   delta <= -threshold  ->  NOD_DOWN
 *   delta >= +threshold  ->  TILT_UP
 *
 * Flip [invertPitch] if a device reports it the other way round.
 *
 * ## Baseline
 *
 * The resting angle depends entirely on how the phone is held, so it is never
 * hard-coded. [baselineDeg] is the **median of a sliding window** of the last
 * [WINDOW_SAMPLES] readings. A median ignores a brief nod (a few frames out of
 * ~3 s of samples), so the baseline stays put while you nod, yet still follows
 * the slow drift of holding the phone differently. Losing the face for a while
 * clears the window so it re-learns on return.
 *
 * ## Recognising a deliberate nod rather than a slow lean
 *
 * Threshold alone is not enough — leaning back in a chair would cross it. So a
 * nod must also be *quick*: the rise from half-threshold to full threshold has to
 * happen inside [motionWindowMs], and the peak must then be **held** for
 * [holdMs]. Falling back under the onset level at any point cancels it.
 */
class HeadPoseDetector(
    private val onTrigger: (gesture: HeadGesture, reason: String) -> Unit,
) {

    companion object {
        private const val TAG = "HeadPose"

        /** Samples kept for the median baseline (~3 s at 15 fps). */
        private const val WINDOW_SAMPLES = 45

        /** Samples required before a cold baseline is trusted (~0.3 s). */
        private const val MIN_SAMPLES = 5

        /** No face for this long clears the baseline. */
        const val RECALIBRATE_AFTER_NO_FACE_MS = 3000L

        /** The excursion is considered to start at this fraction of the threshold. */
        private const val ONSET_FRACTION = 0.5f

        /** Log excursions past this many degrees, to make the sign checkable. */
        private const val DIAGNOSTIC_LOG_DEG = 4f
    }

    /** Degrees from the baseline that count as a nod / tilt. User setting. */
    @Volatile
    var thresholdDeg: Float = 8f

    /** The baseline→peak rise must finish inside this window. */
    @Volatile
    var motionWindowMs: Long = 500L

    /** The peak must then be held this long. */
    @Volatile
    var holdMs: Long = 200L

    @Volatile
    var cooldownMs: Long = 2000L

    @Volatile
    var invertPitch: Boolean = false

    @Volatile
    var calibrated: Boolean = false
        private set

    @Volatile
    var baselineDeg: Float = 0f
        private set

    /** Latest raw pitch, for the settings readout / logs. */
    @Volatile
    var lastAngleDeg: Float? = null
        private set

    @Volatile
    var triggerCount: Int = 0
        private set

    private val window = FloatArray(WINDOW_SAMPLES)
    private val scratch = FloatArray(WINDOW_SAMPLES)
    private var windowIndex = 0
    private var windowCount = 0

    /** When the reading first passed the onset level. */
    private var onsetAtMs = 0L

    /** When it first passed the full threshold. */
    private var reachedAtMs = 0L

    /** True when the rise was quick enough to look like a deliberate nod. */
    private var armed = false

    private var cooldownUntilMs = 0L
    private var lastFaceAtMs = 0L
    private var lastLoggedDeg = 0f

    @Synchronized
    fun reset() {
        windowCount = 0
        windowIndex = 0
        calibrated = false
        baselineDeg = 0f
        clearExcursion()
        cooldownUntilMs = 0L
        lastFaceAtMs = 0L
        lastAngleDeg = null
    }

    /** Throw away the learned baseline; the next samples establish a new one. */
    @Synchronized
    fun recalibrate() {
        windowCount = 0
        windowIndex = 0
        calibrated = false
        clearExcursion()
        Log.i(TAG, "baseline cleared, re-learning")
    }

    /**
     * Start from a known baseline instead of re-learning one.
     *
     * The service seeds this from the previous session when you re-enter a target
     * app, so nodding works immediately rather than after the window fills. The
     * ring buffer is pre-filled with the seed, so the median keeps tracking it
     * until real samples replace it.
     */
    @Synchronized
    fun seedBaseline(degrees: Float) {
        window.fill(degrees)
        windowCount = WINDOW_SAMPLES
        windowIndex = 0
        baselineDeg = degrees
        calibrated = true
        clearExcursion()
        Log.i(TAG, "baseline seeded with ${"%.1f".format(degrees)}°")
    }

    private fun clearExcursion() {
        onsetAtMs = 0L
        reachedAtMs = 0L
        armed = false
        lastLoggedDeg = 0f
    }

    /**
     * Feed one frame.
     *
     * @param angleDeg `headEulerAngleX`, or null when no face was detected
     */
    @Synchronized
    fun onHeadAngle(angleDeg: Float?, nowMs: Long) {
        if (angleDeg == null) {
            if (lastFaceAtMs != 0L && nowMs - lastFaceAtMs > RECALIBRATE_AFTER_NO_FACE_MS) {
                lastFaceAtMs = 0L
                recalibrate()
            }
            return
        }

        lastFaceAtMs = nowMs
        lastAngleDeg = angleDeg
        pushSample(angleDeg)
        if (windowCount < MIN_SAMPLES) return

        baselineDeg = median()
        calibrated = true

        val delta = angleDeg - baselineDeg
        val signed = if (invertPitch) -delta else delta
        val magnitude = abs(signed)

        // Cooldown swallows everything; a pending excursion is dropped.
        if (nowMs < cooldownUntilMs) {
            clearExcursion()
            return
        }

        if (magnitude >= DIAGNOSTIC_LOG_DEG && abs(signed - lastLoggedDeg) >= 3f) {
            Log.i(TAG, "pitch $angleDeg° base $baselineDeg° delta ${"%.1f".format(signed)}°")
            lastLoggedDeg = signed
        }

        val onsetDeg = thresholdDeg * ONSET_FRACTION
        if (magnitude < onsetDeg) {
            // Back at rest: cancel anything pending and let the baseline follow.
            clearExcursion()
            return
        }

        if (onsetAtMs == 0L) onsetAtMs = nowMs
        if (magnitude < thresholdDeg) return

        if (reachedAtMs == 0L) {
            reachedAtMs = nowMs
            val riseMs = nowMs - onsetAtMs
            armed = riseMs <= motionWindowMs
            if (!armed) {
                Log.i(TAG, "ignored slow lean: rise ${riseMs}ms > ${motionWindowMs}ms")
            }
        }
        if (!armed) return
        if (nowMs - reachedAtMs < holdMs) return

        val gesture = if (signed < 0) HeadGesture.NOD_DOWN else HeadGesture.TILT_UP
        triggerCount++
        cooldownUntilMs = nowMs + cooldownMs
        clearExcursion()

        onTrigger(
            gesture,
            when (gesture) {
                HeadGesture.NOD_DOWN -> "低头 ${signed.toInt()}°（${motionWindowMs}ms 内完成）"
                HeadGesture.TILT_UP -> "仰头 ${signed.toInt()}°（${motionWindowMs}ms 内完成）"
            },
        )
    }

    private fun pushSample(value: Float) {
        window[windowIndex] = value
        windowIndex = (windowIndex + 1) % WINDOW_SAMPLES
        if (windowCount < WINDOW_SAMPLES) windowCount++
    }

    /** Median of the samples currently in the ring buffer. */
    private fun median(): Float {
        System.arraycopy(window, 0, scratch, 0, windowCount)
        Arrays.sort(scratch, 0, windowCount)
        return scratch[windowCount / 2]
    }
}
