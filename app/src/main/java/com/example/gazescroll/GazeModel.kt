package com.example.gazescroll

/**
 * States of the two-zone scheme (the "Huawei Reading" style state switch, not a
 * continuous saccade tracker).
 *
 * IDLE      nothing armed; watching for the eye to settle in the bottom zone
 * READY     armed by a >= dwellMs stay in the bottom zone, waiting for the top zone
 * COOLDOWN  a swipe just fired; every gaze signal is ignored until it elapses
 */
enum class GazeState(val label: String) {
    IDLE("IDLE"),
    READY("READY"),
    COOLDOWN("COOLDOWN"),
}

/**
 * One camera frame worth of eye data.
 *
 * All values are normalised to 0f..1f where 0f is the top of the upright camera
 * frame and 1f is the bottom. Already smoothed and already Y-inverted when the
 * user asked for that.
 */
data class GazeSample(
    val leftEyeY: Float?,
    val rightEyeY: Float?,
    val rawMeanY: Float?,
) {
    val hasFace: Boolean
        get() = leftEyeY != null || rightEyeY != null

    val meanY: Float?
        get() {
            val l = leftEyeY
            val r = rightEyeY
            return when {
                l != null && r != null -> (l + r) / 2f
                l != null -> l
                r != null -> r
                else -> null
            }
        }

    companion object {
        val EMPTY = GazeSample(null, null, null)
    }
}

/**
 * Every tunable of the state machine. Defaults are exactly the numbers in the
 * product spec, and the debug UI lets a tester move them at runtime.
 */
data class GazeConfig(
    /** Bottom trigger zone height as a fraction of the screen (spec: 0.25). */
    val bottomZoneRatio: Float = 0.25f,
    /** Top release zone height as a fraction of the screen (spec: 0.20). */
    val topZoneRatio: Float = 0.20f,
    /** How long the eye must stay in the bottom zone before arming (spec: 400 ms). */
    val dwellMs: Long = 400L,
    /** Grace period while READY but outside both zones (spec: 2000 ms). */
    val readyTimeoutMs: Long = 2000L,
    /** Lockout after a swipe (spec: 1500 ms). */
    val cooldownMs: Long = 1500L,
    /** EMA smoothing factor for eye Y; 1.0 disables smoothing. */
    val smoothingAlpha: Float = 0.35f,
    /** Flip the Y axis when head-down makes the landmark Y go the "wrong" way. */
    val invertY: Boolean = false,
    /** Duration of the injected swipe gesture (spec: 100 ms). */
    val swipeDurationMs: Long = 100L,

    // ---------------------------------------------------------- trigger mode --

    /** Primary trigger: blink. On by default. */
    val blinkTriggerEnabled: Boolean = true,
    /**
     * How many consecutive blinks are required: 1, 2 (default) or 3.
     * Each gap must fall inside [BlinkDetector.blinkGapMinMs]..[blinkGapMaxMs].
     */
    val blinkTriggerCount: Int = 2,
    /** Lockout after a blink-triggered swipe. */
    val blinkCooldownMs: Long = 1500L,

    // ---- blink sensitivity (tuned for glasses) ----

    /**
     * Below this, an eye counts as closed. Raised from 0.4 to 0.55: ML Kit reads
     * noticeably lower on people wearing glasses, and 0.4 made blinks hard to hit.
     */
    val blinkClosedBelow: Float = 0.55f,
    /** Above this an eye counts as open again — the gap is the hysteresis band. */
    val blinkOpenAbove: Float = 0.70f,
    /** Consecutive closed frames required before the closure is believed. */
    val blinkClosedFrames: Int = 2,

    // ---- head pose (nod down / tilt up) ----

    /** Off by default so a new user cannot trigger it by accident. */
    val headPoseEnabled: Boolean = false,
    /** Degrees away from the sliding baseline that counts as a nod / tilt. */
    val headPoseAngleThreshold: Float = 8f,
    /** The baseline→peak rise must happen inside this window to count as a nod. */
    val headPoseMotionWindowMs: Long = 500L,
    /** …and the peak must then be held this long before it fires. */
    val headPoseHoldMs: Long = 200L,
    /** Lockout after a head-pose-triggered swipe (spec: 2000 ms). */
    val headPoseCooldownMs: Long = 2000L,
    /** Flip the pitch sign if nodding and tilting come out swapped on a device. */
    val headPoseInvertPitch: Boolean = false,

    /**
     * Legacy "look at the bottom, then at the top" two-zone state machine.
     * Kept for debugging only — OFF by default, blink is the real trigger.
     */
    val gazeModeEnabled: Boolean = false,
) {
    /** Y at or below this value counts as "inside the top release zone". */
    val topZoneEndY: Float get() = topZoneRatio

    /** Y at or above this value counts as "inside the bottom trigger zone". */
    val bottomZoneStartY: Float get() = 1f - bottomZoneRatio

    /** Clamp everything into a range the state machine can actually run with. */
    fun sanitized(): GazeConfig {
        val b = bottomZoneRatio.coerceIn(0.05f, 0.60f)
        val t = topZoneRatio.coerceIn(0.05f, 0.60f)
        val sum = b + t
        // The zones must never overlap, otherwise READY could release instantly.
        val (nb, nt) = if (sum <= 0.90f) b to t else (b * (0.90f / sum)) to (t * (0.90f / sum))
        return copy(
            bottomZoneRatio = nb,
            topZoneRatio = nt,
            dwellMs = dwellMs.coerceIn(100L, 3000L),
            readyTimeoutMs = readyTimeoutMs.coerceIn(300L, 10_000L),
            cooldownMs = cooldownMs.coerceIn(0L, 10_000L),
            smoothingAlpha = smoothingAlpha.coerceIn(0.05f, 1f),
            swipeDurationMs = swipeDurationMs.coerceIn(60L, 1000L),
            blinkTriggerCount = blinkTriggerCount.coerceIn(1, 3),
            blinkCooldownMs = blinkCooldownMs.coerceIn(300L, 10_000L),
            blinkClosedBelow = blinkClosedBelow.coerceIn(0.10f, 0.90f),
            blinkOpenAbove = blinkOpenAbove.coerceIn(0.20f, 0.99f),
            blinkClosedFrames = blinkClosedFrames.coerceIn(1, 6),
            headPoseAngleThreshold = headPoseAngleThreshold.coerceIn(3f, 45f),
            headPoseMotionWindowMs = headPoseMotionWindowMs.coerceIn(150L, 2000L),
            headPoseHoldMs = headPoseHoldMs.coerceIn(60L, 1500L),
            headPoseCooldownMs = headPoseCooldownMs.coerceIn(300L, 10_000L),
        )
    }
}
