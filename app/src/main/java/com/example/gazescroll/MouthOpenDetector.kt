package com.example.gazescroll

import android.util.Log

/**
 * 「张嘴」检测器（v4.5，替代 v4.4 的连续点头双击）。
 *
 * ## 为什么用一个比例而不是绝对像素
 *
 * 不同人离手机的距离、手机倾斜角度都不一样，嘴到鼻子的像素距离波动极大，写死一个
 * 像素阈值必然一会儿灵敏一会儿失灵。这里用**相对自己脸部的比例**：
 *
 * ```
 * ratio = 嘴到鼻底的距离 / 同帧脸部框高度
 * ```
 *
 * 分子是 [FaceLandmarks.mouthNoseGapPx]（`MOUTH_BOTTOM` 到 `NOSE_BASE`），分母是
 * ML Kit 给的人脸框高度。两者同帧、同尺度，所以比例与距离、机型、分辨率都无关。
 *
 * 另外每条数据都做过指数平滑（EMA），单帧的抖动不会直接把结论翻过来。
 *
 * ## 为什么还要自适应基准线
 *
 * 每个人的唇形、胡子、是否抿嘴都不一样：有人自然放松时嘴缝就有一定开度，有人完全
 * 闭合接近 0。所以开合判定是**相对本人自然状态**的：
 *
 * - 未达到触发阈值时的读数被记为「自然闭嘴水平」（滑动窗口的中位数）；
 * - 张嘴量 = 当前比例 − 闭嘴基准；
 * - 张嘴量超过阈值才算张嘴。
 *
 * 这样同一套默认值对不同人都能用。窗口里的中位数天然忽略掉短暂张嘴（少数几帧），
 * 只会跟着「自然状态」慢慢漂移。
 *
 * ## 触发语义（按用户要求）
 *
 * 检测到一次张嘴就**发出一个事件**，具体是「暂停」还是「恢复」由服务按当前状态
 * 切换。并且**必须闭嘴之后再次张嘴才算下一次**：一次张嘴哪怕保持好几秒，也只会
 * 产生一个事件——这是防连发的第一道闸门，不依赖全局冷却。
 *
 * 纯逻辑、无 Android API，方便阅读与测试。
 */
class MouthOpenDetector(
    /** 每识别到一次有效的「张嘴」调用一次。 */
    private val onMouthOpen: () -> Unit,
) {

    companion object {
        private const val TAG = "MouthOpen"

        /** 平滑后的读数里，用于估计「自然闭嘴水平」的窗口长度（约 1.2 秒 @15fps）。 */
        private const val BASELINE_SAMPLES = 18

        /** 少于这么多样本时不信任基准，避免刚启动就误触发。 */
        private const val MIN_BASELINE_SAMPLES = 4

        /** 久没检测到脸就清空基准：换了姿势 / 换了人，旧基准不再适用。 */
        const val RESET_AFTER_NO_FACE_MS = 3000L

        /**
         * 可以当作「闭嘴基准」的合理读数区间（v5.0）。
         *
         * 实测踩到的坑：手挡脸、半张脸出画这类帧会读出一个极小的比值（实测抓到 **0.114**，
         * 而正常闭嘴是 0.215~0.23）。一旦它进了基准窗口，基准就被永久带偏 —— 之后所有正常
         * 读数都比它高出阈值，于是**检测器一直以为用户在张嘴**，表现为「功能要开关一次才能
         * 用」「触发一次之后就不灵了」。所以明显不合理的样本直接不采纳。
         */
        private const val MIN_PLAUSIBLE_BASELINE = 0.05f
        private const val MAX_PLAUSIBLE_BASELINE = 0.60f

        /**
         * 连续判定为「张嘴」超过这么久，就判定基准坏了，强制重新学习。
         *
         * 没人能张着嘴 4 秒不放，所以这只可能是状态卡住，而不是用户真的在张嘴。
         */
        private const val MAX_CONTINUOUS_OPEN_MS = 4000L
    }

    /** 张嘴阈值，单位为「占脸高的比例」。用户设置。 */
    @Volatile
    var sensitivity: Float = MouthSensitivity.MEDIUM.fraction

    /** 平滑系数：越小越稳（更迟钝），越大越跟手。0.45 在 15fps 下手感合适。 */
    @Volatile
    var smoothingAlpha: Float = 0.45f

    /** 连续多少帧判为「张大」才算一次张嘴，防止单帧噪声。 */
    @Volatile
    var requiredOpenFrames: Int = 2

    /** 低于（阈值 − 回差）并持续这么多帧，才认为嘴已经闭上、可以再次触发。 */
    @Volatile
    var requiredClosedFrames: Int = 3

    /** 迟滞回差：张嘴用阈值，闭嘴用阈值 − 回差，避免在临界点反复横跳。 */
    @Volatile
    var hysteresisFraction: Float = 0.35f

    /** 累计识别到的张嘴次数。 */
    @Volatile
    var openCount: Int = 0
        private set

    /** 当前平滑后的比例（嘴到鼻底 / 脸高），设置页显示用。 */
    @Volatile
    var smoothedRatio: Float? = null
        private set

    /** 当前估计的「自然闭嘴」基准；null 表示还没学到。 */
    @Volatile
    var baselineRatio: Float? = null
        private set

    /** 相对基准的张嘴量，设置页显示用。 */
    @Volatile
    var openness: Float = 0f
        private set

    /** 当前是否判定为张开状态（用于显示，也用于「必须闭嘴才能再触发」）。 */
    @Volatile
    var mouthOpen: Boolean = false
        private set

    @Volatile
    var calibrated: Boolean = false
        private set

    /** 最近一次原始比例（未平滑），诊断日志用。 */
    @Volatile
    var lastRawRatio: Float? = null
        private set

    private val baselineWindow = FloatArray(BASELINE_SAMPLES)
    private val scratch = FloatArray(BASELINE_SAMPLES)
    private var baselineIndex = 0
    private var baselineCount = 0

    private var openFrames = 0
    private var closedFrames = 0
    private var lastFaceAtMs = 0L

    /** 持续张嘴的起始时刻，用于 [MAX_CONTINUOUS_OPEN_MS] 的兜底重置。 */
    private var openSinceMs = 0L

    /** 累计「基准坏掉、强制重学」的次数，仅用于诊断。 */
    @Volatile
    var forcedReloads: Int = 0
        private set

    /** 累计被合理性检查丢掉的基准样本数，仅用于诊断。 */
    @Volatile
    var rejectedSamples: Int = 0
        private set

    @Synchronized
    fun reset() {
        baselineCount = 0
        baselineIndex = 0
        baselineWindow.fill(0f)
        openFrames = 0
        closedFrames = 0
        openSinceMs = 0L
        mouthOpen = false
        calibrated = false
        baselineRatio = null
        smoothedRatio = null
        openness = 0f
        lastRawRatio = null
        lastFaceAtMs = 0L
    }

    /**
     * 喂一帧。
     *
     * @param ratio 嘴到鼻底距离 / 脸高，null 表示这一帧没检测到脸
     * @param nowMs `SystemClock.elapsedRealtime()`
     */
    @Synchronized
    fun onFrame(ratio: Float?, nowMs: Long) {
        if (ratio == null) {
            if (lastFaceAtMs != 0L && nowMs - lastFaceAtMs > RESET_AFTER_NO_FACE_MS) {
                // 丢失人脸太久：基准和状态一起清掉，回来时重新学习。
                reset()
            }
            return
        }

        lastFaceAtMs = nowMs
        lastRawRatio = ratio
        smoothedRatio = smooth(smoothedRatio, ratio)

        val current = smoothedRatio ?: return

        // 还没学到基准：先用前几帧建立，期间不判定。
        if (baselineCount < MIN_BASELINE_SAMPLES) {
            if (acceptBaselineSample(current)) {
                baselineRatio = median()
                calibrated = baselineCount >= MIN_BASELINE_SAMPLES
                if (calibrated) {
                    Log.i(
                        TAG,
                        "mouth baseline learned: ${"%.3f".format(baselineRatio ?: 0f)} " +
                            "(rejected=${rejectedSamples})",
                    )
                }
            }
            return
        }

        val baseline = baselineRatio ?: current
        val delta = current - baseline
        openness = delta

        val openAbove = sensitivity
        // 回差取阈值的一个固定比例，且至少留一点余量，免得阈值很小时回差也趋近 0。
        val closeBelow = (openAbove - openAbove * hysteresisFraction)
            .coerceAtLeast(openAbove * 0.4f)

        if (!mouthOpen) {
            if (delta >= openAbove) {
                openFrames++
                closedFrames = 0
                if (openFrames >= requiredOpenFrames) {
                    mouthOpen = true
                    openFrames = 0
                    openSinceMs = nowMs
                    openCount++
                    Log.i(
                        TAG,
                        "mouth open detected: ratio=${"%.3f".format(current)} " +
                            "base=${"%.3f".format(baseline)} delta=${"%.3f".format(delta)} " +
                            "-> tap, rearm after mouth closes",
                    )
                    onMouthOpen()
                }
            } else {
                openFrames = 0
                // 只在「闭嘴」区间更新基准，张嘴期间不污染自然状态。
                if (delta < closeBelow) {
                    if (acceptBaselineSample(current)) baselineRatio = median()
                }
            }
        } else {
            // 兜底：一直「张着嘴」超过 4 秒只可能是基准坏了，强制重学。
            if (openSinceMs != 0L && nowMs - openSinceMs > MAX_CONTINUOUS_OPEN_MS) {
                forcedReloads++
                Log.w(
                    TAG,
                    "mouth stuck open for ${nowMs - openSinceMs}ms — baseline " +
                        "${"%.3f".format(baseline)} looks wrong, re-learning (forcedReloads=$forcedReloads)",
                )
                reset()
                return
            }
            if (delta < closeBelow) {
                closedFrames++
                if (closedFrames >= requiredClosedFrames) {
                    // 确认闭上了，解锁下一次触发。
                    mouthOpen = false
                    closedFrames = 0
                    if (acceptBaselineSample(current)) baselineRatio = median()
                    Log.i(
                        TAG,
                        "mouth rearmed: closed again, ready for next tap " +
                            "(base=${"%.3f".format(baselineRatio ?: 0f)})",
                    )
                }
            } else {
                // 还张着（或又张大了）：保持锁定，不产生新事件。
                closedFrames = 0
            }
        }
    }

    /**
     * 采纳一个「闭嘴基准」样本，附带合理性检查（v5.0）。
     *
     * 明显不合理的读数（半张脸出画、手挡脸时读到的极小值）进来会把基准永久带偏，进而让
     * 检测器一直以为用户在张嘴。宁可不学，也不要学错——窗口迟迟填不满时也只是暂时不判定。
     */
    private fun acceptBaselineSample(value: Float): Boolean {
        if (value < MIN_PLAUSIBLE_BASELINE || value > MAX_PLAUSIBLE_BASELINE) {
            rejectedSamples++
            return false
        }
        pushBaseline(value)
        return true
    }

    private fun smooth(previous: Float?, next: Float): Float {
        val alpha = smoothingAlpha.coerceIn(0.05f, 1f)
        return if (previous == null) next else previous + alpha * (next - previous)
    }

    private fun pushBaseline(value: Float) {
        baselineWindow[baselineIndex] = value
        baselineIndex = (baselineIndex + 1) % BASELINE_SAMPLES
        if (baselineCount < BASELINE_SAMPLES) baselineCount++
    }

    private fun median(): Float {
        if (baselineCount <= 0) return 0f
        System.arraycopy(baselineWindow, 0, scratch, 0, baselineCount)
        java.util.Arrays.sort(scratch, 0, baselineCount)
        return scratch[baselineCount / 2]
    }
}
