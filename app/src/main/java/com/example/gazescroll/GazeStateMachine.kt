package com.example.gazescroll

/**
 * The two-zone state machine.
 *
 * Deliberately free of Android APIs so the logic stays readable and testable.
 * It is driven once per analysed camera frame; every timeout is evaluated
 * against the timestamp of the sample, which is correct here because the
 * analyzer keeps delivering frames (with a null Y) even when no face is found.
 *
 * Zone semantics, straight from the spec:
 *   - bottom trigger zone : eye Y in the bottom [bottomZoneRatio] of the frame
 *   - top release zone    : eye Y in the top [topZoneRatio] of the frame
 *   - "left or right eye" : entering a zone means EITHER eye entered it
 */
class GazeStateMachine(
    private val onStateChanged: (from: GazeState, to: GazeState) -> Unit,
    private val onTrigger: () -> Unit,
) {

    @Volatile
    var config: GazeConfig = GazeConfig()

    var state: GazeState = GazeState.IDLE
        private set

    /** When the eye first settled inside the bottom zone, or 0 when not inside. */
    private var dwellStartMs = 0L

    /** When READY stopped being inside the bottom zone, or 0 while it still is. */
    private var graceStartMs = 0L

    /** Wall-clock instant the cooldown expires. */
    private var cooldownUntilMs = 0L

    @Synchronized
    fun reset() {
        dwellStartMs = 0L
        graceStartMs = 0L
        cooldownUntilMs = 0L
        transit(GazeState.IDLE)
    }

    /**
     * Feed one frame.
     *
     * @param sample smoothed eye landmarks, nulls when no face was detected
     * @param nowMs  SystemClock.elapsedRealtime()
     */
    @Synchronized
    fun update(sample: GazeSample, nowMs: Long) {
        val cfg = config

        // The cooldown gate swallows every gaze signal. This is what stops a
        // single glance from producing a burst of swipes.
        if (state == GazeState.COOLDOWN) {
            if (nowMs >= cooldownUntilMs) transit(GazeState.IDLE)
            return
        }

        val left = sample.leftEyeY
        val right = sample.rightEyeY

        // "either eye" — the spec says the left OR the right landmark counts.
        val inBottom = (left != null && left >= cfg.bottomZoneStartY) ||
            (right != null && right >= cfg.bottomZoneStartY)
        val inTop = (left != null && left <= cfg.topZoneEndY) ||
            (right != null && right <= cfg.topZoneEndY)

        when (state) {
            GazeState.IDLE -> {
                if (inBottom) {
                    if (dwellStartMs == 0L) {
                        dwellStartMs = nowMs
                    } else if (nowMs - dwellStartMs >= cfg.dwellMs) {
                        // Held long enough — arm the release zone.
                        dwellStartMs = 0L
                        graceStartMs = 0L
                        transit(GazeState.READY)
                    }
                } else {
                    // Any excursion out of the zone restarts the dwell timer.
                    dwellStartMs = 0L
                }
            }

            GazeState.READY -> when {
                inTop -> {
                    // Release. Arm the cooldown BEFORE notifying so the state
                    // reported alongside the trigger is already COOLDOWN.
                    cooldownUntilMs = nowMs + cfg.cooldownMs
                    dwellStartMs = 0L
                    graceStartMs = 0L
                    transit(GazeState.COOLDOWN)
                    onTrigger()
                }

                inBottom -> {
                    // Still parked in the trigger zone: stay armed indefinitely.
                    graceStartMs = 0L
                }

                else -> {
                    // Moved off the bottom zone but has not reached the top
                    // (e.g. the middle of the screen). Hold READY for at most
                    // readyTimeoutMs, then fall back to IDLE.
                    if (graceStartMs == 0L) {
                        graceStartMs = nowMs
                    } else if (nowMs - graceStartMs >= cfg.readyTimeoutMs) {
                        graceStartMs = 0L
                        transit(GazeState.IDLE)
                    }
                }
            }

            GazeState.COOLDOWN -> Unit // unreachable: handled by the early return
        }
    }

    private fun transit(to: GazeState) {
        if (to == state) return
        val from = state
        state = to
        onStateChanged(from, to)
    }
}
