package com.example.gazescroll

/**
 * Blink detector — the primary swipe trigger.
 *
 * Pure logic, no Android APIs, so it stays readable and testable.
 *
 * ## Tuned for people wearing glasses
 *
 * ML Kit's eye-open probabilities read noticeably lower through lenses, and the
 * original 0.4 "closed" threshold made blinks hard to land. Three changes:
 *
 *  - **Higher threshold** ([closedBelow], default 0.55) with a hysteresis band up
 *    to [openAbove] (0.70).
 *  - **Single-eye trigger** — *either* eye dropping below the threshold counts as
 *    closed, so a lens reflection holding one eye high no longer hides a blink.
 *  - **Two-frame confirmation** ([requiredClosedFrames]) — a momentary dip cannot
 *    complete a blink on its own, which is what keeps the looser threshold from
 *    turning noise into phantom pages.
 *
 * A blink is the sequence 睁眼 → 闭眼 → 睁眼, i.e. the falling edge of the
 * "eyes closed" verdict.
 *
 * The user picks how many blinks in a row are needed (1, 2 or 3) — see
 * [requiredBlinks]. Every gap between consecutive blinks must fall inside
 * [blinkGapMinMs]..[blinkGapMaxMs]; a slower gap restarts the run. The run fires
 * as soon as it reaches [requiredBlinks], then the detector locks out for
 * [cooldownMs].
 */
class BlinkDetector(
    private val onTrigger: (reason: String) -> Unit,
) {

    companion object {
        /** Supported consecutive-blink counts. */
        val SUPPORTED_COUNTS = intArrayOf(1, 2, 3)

        /**
         * Nobody keeps their eyes shut for this long; if the "closed" verdict
         * persists past it, the reading is stuck (an occluded lens, a bad
         * detection) rather than a blink — clear it so blinks keep working.
         */
        private const val MAX_CLOSURE_MS = 3000L
    }

    /** Below this, an eye counts as closed. Raised to 0.55 for glasses. */
    @Volatile
    var closedBelow: Float = 0.55f

    /** Above this, an eye counts as open again — the gap is the hysteresis band. */
    @Volatile
    var openAbove: Float = 0.70f

    /** Consecutive closed frames required before the closure is believed. */
    @Volatile
    var requiredClosedFrames: Int = 2

    /** Gap window between two blinks that still counts as "in a row". */
    @Volatile
    var blinkGapMinMs: Long = 200L

    @Volatile
    var blinkGapMaxMs: Long = 1500L

    /** How many consecutive blinks fire the swipe. User setting: 1 / 2 / 3. */
    @Volatile
    var requiredBlinks: Int = 2

    /** Lockout after a trigger (spec: 1500 ms). Refreshed from config each frame. */
    @Volatile
    var cooldownMs: Long = 1500L

    /** Completed blinks seen since the service started. */
    @Volatile
    var blinkCount: Int = 0
        private set

    /** Swipes this detector has fired. */
    @Volatile
    var triggerCount: Int = 0
        private set

    /** Blinks accumulated towards the current run; exposed for debugging. */
    @Volatile
    var pendingBlinks: Int = 0
        private set

    /** Confirmed-closed state (the hysteresis verdict). */
    private var eyesClosed = false

    /** Consecutive frames seen as closed. */
    private var closedFrames = 0

    /** When the current closure was confirmed, for the stuck-reading guard. */
    private var closedSinceMs = 0L

    private var lastBlinkAtMs = 0L
    private var cooldownUntilMs = 0L

    @Synchronized
    fun reset() {
        eyesClosed = false
        closedFrames = 0
        closedSinceMs = 0L
        lastBlinkAtMs = 0L
        pendingBlinks = 0
        cooldownUntilMs = 0L
    }

    fun inCooldown(nowMs: Long): Boolean = nowMs < cooldownUntilMs

    /**
     * Feed one frame's eye-open probabilities.
     *
     * @param left  `leftEyeOpenProbability`, or null when unavailable
     * @param right `rightEyeOpenProbability`, or null when unavailable
     */
    @Synchronized
    fun onEyeProbabilities(left: Float?, right: Float?, nowMs: Long) {
        // Need at least one eye; a frame with neither tells us nothing.
        if (left == null && right == null) {
            closedFrames = 0
            return
        }

        val closedNow = (left != null && left < closedBelow) ||
            (right != null && right < closedBelow)
        val openNow = (left != null && left > openAbove) ||
            (right != null && right > openAbove)

        if (closedNow) {
            closedFrames++
            if (closedFrames >= requiredClosedFrames && !eyesClosed) {
                eyesClosed = true
                closedSinceMs = nowMs
            }
        } else if (openNow) {
            closedFrames = 0
            val completedBlink = eyesClosed
            eyesClosed = false
            closedSinceMs = 0L
            if (completedBlink) registerBlink(nowMs)
        }
        // Between the thresholds: keep the previous verdict (hysteresis).

        // Stuck-reading guard: release a closure that has clearly lasted too long.
        if (eyesClosed && nowMs - closedSinceMs > MAX_CLOSURE_MS) {
            eyesClosed = false
            closedFrames = 0
            closedSinceMs = 0L
        }
    }

    private fun registerBlink(nowMs: Long) {
        // Swallow everything during cooldown; the state above was still updated
        // so we do not fire the moment it expires.
        if (nowMs < cooldownUntilMs) return

        blinkCount++
        val previousBlinkAt = lastBlinkAtMs
        lastBlinkAtMs = nowMs
        val gapMs = if (previousBlinkAt == 0L) Long.MAX_VALUE else nowMs - previousBlinkAt

        // Consecutive run: a gap inside the window extends it, anything else restarts it.
        pendingBlinks = if (gapMs in blinkGapMinMs..blinkGapMaxMs) pendingBlinks + 1 else 1

        val needed = requiredBlinks.coerceIn(1, 3)
        if (pendingBlinks < needed) return

        triggerCount++
        pendingBlinks = 0
        cooldownUntilMs = nowMs + cooldownMs
        onTrigger(
            if (needed == 1) {
                "眨眼 1 次"
            } else {
                "连续眨眼 $needed 次（末次间隔 ${gapMs}ms）"
            },
        )
    }
}
