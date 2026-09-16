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
 * ## 判据（v5.36）
 *
 *  1. **相对本人基准线的倾斜**：`tilt = roll − baseline`。基准线是最近 [BASELINE_SAMPLES] 帧
 *     滚转角的中位数，所以**天生歪着头看手机的人不会被误触发**，也自动跟随换姿势。
 *     **v5.36 起：歪着的时候不更新基准线**（只在"头基本在中间"时喂样本）——
 *     否则歪着头不动几秒，中位数会跟着歪过去，回正时就变成反方向的一大坨倾斜。
 *  2. **必须真的歪到一定角度**：`|tilt| ≥ thresholdDeg`（设置里可选 10 / **13（默认）** /
 *     16 / 20°，v5.36 整体调灵一档）。
 *  3. **并且保持 [holdMs]**（可选 0.2 / **0.3（默认）** / 0.5 / 0.8 秒）。
 *  4. **一次歪头只调一组档位**：触发后必须**回到中位带**（`|tilt| ≤ 0.4×阈值`）并保持
 *     [NEUTRAL_REARM_MS]（400ms）之后，才允许下一次 —— 顺手挡住"回正时甩到另一边"。
 *  5. **两秒内不认第二次动作（v5.36，用户要求）**：`上一次任何动作 + 2 秒` 之前**起手**
 *     的歪头整段作废（[gapStartAfterMs]）。**不能有提前量**：卡在 1.99 秒那一下不算，
 *     必须等满两秒之后**新起**的歪头才判定。
 *  6. 距基准线太远（`|tilt| > [TILT_SANITY_DEG]`，例如整个人躺下/侧卧）时视为不可信，
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

        /**
         * 只有"头基本在中间"（|tilt| ≤ 阈值）时才把这一帧喂进基准线窗口（v5.36）。
         *
         * 这是 v5.36 修掉「回正脖子时误触发」的关键：基准线是 45 帧中位数，
         * 如果**歪着头不动**几秒，中位数会跟着歪过去 —— 等你回正时，
         * "相对基准线"的倾斜就变成了反方向的一大坨，于是回正的动作被判成反方向歪头。
         * 现在歪着的时候不更新基准线，基准线只会跟着"平时的头姿"走（见 [onRoll]）。
         */
        /** 回到中位后必须保持这么久，才允许下一次歪头（v5.36：250 → 400ms）。 */
        private const val NEUTRAL_REARM_MS = 400L

        /** 中位带 = 阈值 × 这个系数（回差，避免在阈值附近反复触发）。 */
        private const val NEUTRAL_FACTOR = 0.4f

        /** 触发所需时长的可选档位（毫秒）。v5.36：整体调灵一档。 */
        val HOLD_OPTIONS = longArrayOf(200L, 300L, 500L, 800L)

        /** 触发角度的可选档位（度）。v5.36：整体调灵一档（默认 13°）。 */
        val THRESHOLD_OPTIONS = floatArrayOf(10f, 13f, 16f, 20f)

        /** 默认触发角度（度）。 */
        const val DEFAULT_THRESHOLD_DEG = 13f

        /** 默认保持时长（毫秒）。 */
        const val DEFAULT_HOLD_MS = 300L

        /** 超过这个倾斜幅度就认为脸/读数是歪的（躺下、侧卧），不做判定。 */
        private const val TILT_SANITY_DEG = 55f
    }

    /** 触发角度（度）。服务每帧同步成用户选的档位。 */
    @Volatile
    var thresholdDeg: Float = DEFAULT_THRESHOLD_DEG

    /** 需要保持多久（毫秒）。 */
    @Volatile
    var holdMs: Long = DEFAULT_HOLD_MS

    /**
     * 「这一次歪头必须在这个时刻之后**才开始**」（v5.36）。
     *
     * 服务每帧同步成 `上一次任何动作的时刻 + 2 秒`（用户要求：上一秒做过动作就必须强制等满
     * 两秒，而且**不能有提前量** —— 卡在 1.99 秒那一下不算，必须等满两秒之后**新起**的歪头
     * 才判定）。实现方式是：**超过阈值的那一帧（起手）必须晚于这个时刻**，早了就整段作废、
     * 必须回到中位再重新歪。
     */
    @Volatile
    var gapStartAfterMs: Long = 0L

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

    /** 本段是不是"起手太早"（还没等满两秒）→ 整段作废（v5.36）。 */
    private var episodeGapBlocked = false

    /** 「起手太早」是否已经打过日志（每段只打一次）。 */
    private var reportedGapBlock = false

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
        episodeGapBlocked = false
        reportedGapBlock = false
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

        val thresholdNow = thresholdDeg.coerceIn(4f, 45f)
        // v5.36：只有"头基本在中间"时才把读数喂进基准线窗口 —— 歪着头不动不会把基准线带走，
        // 于是回正时不会凭空出现"反方向的大倾斜"。（窗口没填满前一律喂，先把基准线建起来。）
        val previousTilt = tiltDeg
        // 只有"头基本在中间"时才更新基准线（窗口还没建起来时一律喂，先把基准线立起来）。
        // 注意这里**不能**再带 `ringCount < BASELINE_SAMPLES` 当例外：否则刚启动就歪着头时，
        // 窗口会被歪着的样本填满，基准线照样被带走（v5.36 离线回放第 5 项就是这么抓到的）。
        val trackBaseline = previousTilt == null || kotlin.math.abs(previousTilt) <= thresholdNow
        if (trackBaseline) {
            ring[ringIndex] = rollDeg
            ringIndex = (ringIndex + 1) % BASELINE_SAMPLES
            if (ringCount < BASELINE_SAMPLES) ringCount++
        }
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

        val threshold = thresholdNow
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
                episodeGapBlocked = false
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
                episodeGapBlocked = false
            }
            return
        }
        episodePeakDeg = maxOf(episodePeakDeg, kotlin.math.abs(tilt))

        if (beyondSinceMs == 0L || side != beyondSide) {
            beyondSinceMs = nowMs
            beyondSide = side
            heldMs = 0L
            // v5.36：**起手**必须晚于"上一次动作 + 2 秒"。卡在 1.99 秒那一下不算 ——
            // 这一段整段作废（不能有提前量），必须回到中位、重新歪一次。
            episodeGapBlocked = nowMs < gapStartAfterMs
            if (episodeGapBlocked && !reportedGapBlock) {
                reportedGapBlock = true
                val remain = gapStartAfterMs - nowMs
                Log.i(
                    TAG,
                    "${if (side < 0) TiltSide.LEFT.label else TiltSide.RIGHT.label} " +
                        "起手太早（还剩 ${remain}ms 才满两秒）→ 这一段不算，回正后重新歪",
                )
            }
        }
        heldMs = nowMs - beyondSinceMs

        if (!firedThisEpisode && !episodeGapBlocked && heldMs >= holdMs) {
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

    private fun median(values: FloatArray, count: Int): Float {
        val copy = FloatArray(count)
        System.arraycopy(values, 0, copy, 0, count)
        java.util.Arrays.sort(copy)
        val mid = count / 2
        return if (count % 2 == 1) copy[mid] else (copy[mid - 1] + copy[mid]) / 2f
    }
}
