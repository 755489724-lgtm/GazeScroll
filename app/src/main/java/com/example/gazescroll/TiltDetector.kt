package com.example.gazescroll

import android.util.Log

/**
 * 歪头（左右压耳朵 / roll）的方向（v5.35）。
 *
 * 用户说法：左歪头 = 调高音量、右歪头 = 调低音量（默认），两条都能在设置页各自反向。
 */
enum class TiltSide(val label: String) {
    LEFT("左歪头"),
    RIGHT("右歪头"),
}

/**
 * 一次被确认的「有意歪头」。
 *
 * 带判定当时的全部读数，服务那边原样打进 `I/Tilt` 日志。
 */
data class TiltEvent(
    val side: TiltSide,
    /** 保持（超过阈值）了多久。 */
    val heldMs: Long,
    /** 触发时相对本人基准线的倾斜角（度，带符号）。 */
    val tiltDeg: Float,
    /** 本段里最大的倾斜幅度（度，正数）—— 用户"歪了多少"的真值。 */
    val peakDeg: Float,
    /** 本段的基准线（本人自然头姿的滚转角中位数）。 */
    val baselineDeg: Float,
    /** 本次触发用的阈值（度）。 */
    val thresholdDeg: Float,
    /** 触发前最后的"回到中位"持续了多久（毫秒）。 */
    val neutralBeforeMs: Long,
    /** 距离档（来自头部检测器），仅用于日志核对。 */
    val nearTier: Boolean?,
)

/**
 * 歪头（roll）→ 音量 检测器 —— v5.35 新增，用来替代 v5.30~v5.34 的「单眼闭眼」通道。
 *
 * ## 为什么换掉单眼闭眼
 *
 * 四轮实机下来，ML Kit 的 `eyeOpenProbability` 在这台设备/这个姿势下**分不开**
 * "有意单闭"与"低头眯眼"：绝对阈值试过 0.70/0.65/0.55，合眼快慢（onset）真单闭
 * 55~1732ms 与误触 1.6~3.2s 完全重叠。用户最终决定放弃这条通道，改用**歪头**。
 *
 * ## 判据
 *
 *  1. **相对本人基准线的倾斜**：`tilt = roll − baseline`。基准线是最近
 *     [BASELINE_SAMPLES] 帧滚转角的中位数（与头部通道的俯仰基准线同一套做法），
 *     所以**天生歪着头看手机的人不会被误触发**，也自动跟随换姿势。
 *  2. **必须真的歪到一定角度**：`|tilt| ≥ thresholdDeg`（用户可在设置里选 12/15/18/22°，
 *     默认 18°）。用户原话：「仰头得到一定的角度，才会触发」。
 *  3. **并且保持 [holdMs]**（用户可在设置里选 0.3/0.5/0.8/1.0 秒，默认 0.5 秒）——
 *     抖一下头、甩一下头发不会触发。
 *  4. **一次歪头只调一组档位**：触发后必须**回到中位**（`|tilt| ≤ 0.4×阈值`）并保持
 *     [NEUTRAL_REARM_MS] 之后，才允许下一次。歪着头不动不会一直调。
 *  5. 距基准线太远（`|tilt| > [TILT_SANITY_DEG]`，例如整个人躺下/侧卧）时视为不可信，
 *     不做判定并重置状态。
 *
 * 本类不触发任何 Android API（只打日志），纯逻辑，可以用 tools/tilt-replay 离线回放。
 */
class TiltDetector(
    private val onTilt: (TiltEvent) -> Unit,
) {

    companion object {
        private const val TAG = "Tilt"

        /** 基准线窗口（帧）：约 45 帧 ≈ 4 秒，与头部通道的俯仰基准线一致。 */
        private const val BASELINE_SAMPLES = 45

        /** 基准线可用前至少要有这么多帧。 */
        private const val MIN_SAMPLES = 8

        /** 回归中位所需的"回到中位"持续时长。 */
        private const val NEUTRAL_REARM_MS = 250L

        /** 中位带 = 阈值 × 这个系数（回差，避免在阈值附近反复触发）。 */
        private const val NEUTRAL_FACTOR = 0.4f

        /** 触发所需时长的可选档位（毫秒）。 */
        val HOLD_OPTIONS = longArrayOf(300L, 500L, 800L, 1000L)

        /** 触发角度的可选档位（度）。 */
        val THRESHOLD_OPTIONS = floatArrayOf(12f, 15f, 18f, 22f)

        /** 超过这个倾斜幅度就认为脸/读数是歪的（躺下、侧卧），不做判定。 */
        private const val TILT_SANITY_DEG = 55f
    }

    /** 触发角度（度）。服务每帧同步成用户选的档位。 */
    @Volatile
    var thresholdDeg: Float = 18f

    /** 需要保持多久（毫秒）。 */
    @Volatile
    var holdMs: Long = 500L

    /** 距离档（只用于日志）。 */
    @Volatile
    var nearTier: Boolean? = null

    /** 已触发的歪头次数。 */
    @Volatile
    var triggerCount: Int = 0
        private set

    /** 最近一次触发的文字描述。 */
    @Volatile
    var lastTiltLabel: String = "-"
        private set

    /** 当前相对基准线的倾斜角（度，带符号）；没数据时为 null。诊断行 / 分页门控用。 */
    @Volatile
    var tiltDeg: Float? = null
        private set

    /** 已经超过阈值多久（毫秒）；0 = 当前没超过。 */
    @Volatile
    var heldMs: Long = 0L
        private set

    /** 当前基准线（度）。 */
    @Volatile
    var baselineDeg: Float? = null
        private set

    /** 本段最大倾斜幅度（度）。 */
    @Volatile
    var peakDeg: Float = 0f
        private set

    // ---- 内部状态 ----
    private val ring = FloatArray(BASELINE_SAMPLES)
    private var ringIndex = 0
    private var ringCount = 0

    /** 当前这一侧"超过阈值"的起点；0 = 当前没超过。 */
    private var beyondSinceMs = 0L
    private var beyondSide = 0            // -1 / +1 / 0

    /** 本段是否已经触发过（一次歪头只调一组）。 */
    private var firedThisEpisode = false
    private var episodePeakDeg = 0f

    /** 回到中位之后再过这么久才允许下一次。 */
    private var neutralSinceMs = 0L
    private var neutralBeforeMs = 0L
    private var lastFrameAtMs = 0L

    /** 诊断行用的紧凑状态。 */
    fun stateLine(): String =
        "tilt=${tiltDeg?.let { "%+.1f".format(it) } ?: "-"}° " +
            "base=${baselineDeg?.let { "%.1f".format(it) } ?: "-"}° " +
            "thr=${"%.0f".format(thresholdDeg)}° held=${heldMs}ms " +
            "peak=${"%.1f".format(peakDeg)}° fired=$triggerCount last=$lastTiltLabel"

    /** 诊断行 / 分页门控用：当前是否已经歪出中位带（该让翻页通道先别判）。 */
    fun isBeyondNeutral(): Boolean {
        val t = tiltDeg ?: return false
        return kotlin.math.abs(t) > thresholdDeg * NEUTRAL_FACTOR
    }

    @Synchronized
    fun reset() {
        ringIndex = 0
        ringCount = 0
        java.util.Arrays.fill(ring, 0f)
        baselineDeg = null
        tiltDeg = null
        heldMs = 0L
        peakDeg = 0f
        beyondSinceMs = 0L
        beyondSide = 0
        firedThisEpisode = false
        episodePeakDeg = 0f
        neutralSinceMs = 0L
        neutralBeforeMs = 0L
        lastFrameAtMs = 0L
    }

    /**
     * 喂一帧的滚转角（ML Kit `headEulerAngleZ`）。
     *
     * @param rollDeg null 表示这一帧没有读数（没脸），只清状态不判定。
     */
    @Synchronized
    fun onRoll(rollDeg: Float?, nowMs: Long) {
        if (rollDeg == null) {
            // 没脸：清掉进行中的动作，但**保留**基准线窗口（丢脸一两帧不该重学头姿）。
            beyondSinceMs = 0L
            beyondSide = 0
            firedThisEpisode = false
            heldMs = 0L
            tiltDeg = null
            return
        }
        lastFrameAtMs = nowMs

        // 基准线（中位数）—— 与头部通道的俯仰基准线同一套做法。
        ring[ringIndex] = rollDeg
        ringIndex = (ringIndex + 1) % BASELINE_SAMPLES
        if (ringCount < BASELINE_SAMPLES) ringCount++
        if (ringCount < MIN_SAMPLES) return
        val base = median(ring, ringCount)
        baselineDeg = base

        val tilt = rollDeg - base
        tiltDeg = tilt

        // 读数太歪（躺下 / 侧卧）：不判定，重新学基准线。
        if (kotlin.math.abs(tilt) > TILT_SANITY_DEG) {
            reset()
            return
        }

        val threshold = thresholdDeg.coerceIn(4f, 45f)
        val neutral = threshold * NEUTRAL_FACTOR

        if (kotlin.math.abs(tilt) <= neutral) {
            // 回到中位。
            if (neutralSinceMs == 0L) neutralSinceMs = nowMs
            neutralBeforeMs = nowMs - neutralSinceMs
            if (beyondSinceMs != 0L) {
                // 这一段结束（没触发够 / 已经触发过都走这里）。
                beyondSinceMs = 0L
                beyondSide = 0
                heldMs = 0L
                peakDeg = maxOf(peakDeg, episodePeakDeg)
                episodePeakDeg = 0f
            }
            if (neutralBeforeMs >= NEUTRAL_REARM_MS) firedThisEpisode = false
            return
        }
        // 离开中位：中位计时清零。
        neutralSinceMs = 0L
        neutralBeforeMs = 0L

        val side = when {
            tilt <= -threshold -> -1
            tilt >= threshold -> 1
            else -> 0
        }
        if (side == 0) {
            // 在中位带之外、阈值之内：不算"超过阈值"，但本段还在（保持峰值）。
            episodePeakDeg = maxOf(episodePeakDeg, kotlin.math.abs(tilt))
            if (beyondSinceMs != 0L) {
                beyondSinceMs = 0L
                beyondSide = 0
                heldMs = 0L
            }
            return
        }
        episodePeakDeg = maxOf(episodePeakDeg, kotlin.math.abs(tilt))

        if (beyondSinceMs == 0L || side != beyondSide) {
            beyondSinceMs = nowMs
            beyondSide = side
            heldMs = 0L
        }
        heldMs = nowMs - beyondSinceMs

        if (!firedThisEpisode && heldMs >= holdMs) {
            firedThisEpisode = true
            triggerCount++
            val label = if (side < 0) TiltSide.LEFT.label else TiltSide.RIGHT.label
            lastTiltLabel = "$label ${"%.1f".format(kotlin.math.abs(tilt))}° ${heldMs}ms"
            peakDeg = maxOf(peakDeg, episodePeakDeg)
            onTilt(
                TiltEvent(
                    side = if (side < 0) TiltSide.LEFT else TiltSide.RIGHT,
                    heldMs = heldMs,
                    tiltDeg = tilt,
                    peakDeg = episodePeakDeg,
                    baselineDeg = base,
                    thresholdDeg = threshold,
                    neutralBeforeMs = neutralBeforeMs,
                    nearTier = nearTier,
                ),
            )
        }
    }

    /** 拒绝留痕（服务侧在通道被闸门挡住时调用，方便核对"为什么没反应"）。 */
    fun logRejectedShort(side: TiltSide, peak: Float, held: Long, threshold: Float) {
        Log.i(
            TAG,
            "${side.label} 只保持 ${held}ms（峰值 ${"%.1f".format(-peak)}°，阈值 ${"%.0f".format(threshold)}°）" +
                " → 不触发",
        )
    }

    private fun median(values: FloatArray, count: Int): Float {
        val copy = FloatArray(count)
        System.arraycopy(values, 0, copy, 0, count)
        java.util.Arrays.sort(copy)
        val mid = count / 2
        return if (count % 2 == 1) copy[mid] else (copy[mid - 1] + copy[mid]) / 2f
    }
}
