package com.example.gazescroll

import kotlin.math.abs

/**
 * 参考点位移检测器（v5.22）—— 按用户提出的算法实现。
 *
 * ## 用户的原始思路
 *
 * > 「用一个参考点，来计算我点头还是仰头。标准的仰头就是我的下巴会往上移动几厘米，
 * > 点头就是鼻子会往下移动几厘米。做一个参考点，**不能硬算**。」
 *
 * 也就是**不看俯仰角**，只看面部关键点在画面里的**位移**，并且用脸框高度归一化，
 * 消除远近距离带来的像素差异。近距离俯视时角度变化不明显，但关键点位移依然清晰 ——
 * 这正是它比"硬算角度"更稳的地方。
 *
 * ## 实现
 *
 * 1. **参考点**（[refNose] / [refChin]）：关键点在**脸框内**的归一化 Y。
 *    只在头部稳定时更新（连续 [REF_WINDOW] 帧峰峰值 < [REF_STILL_RANGE]），
 *    所以它天然是"这次动作的起点"，不会像滑动窗口中位数基线那样滞后好几度
 *    （v5.16~v5.21 反复踩的就是那个坑）。
 * 2. **位移**：`noseDy = noseNormY − refNose`、`chinDy = chinNormY − refChin`，
 *    单位是**脸高比例**（1.0 = 一个脸高），所以远近无关 —— 这就是"距离归一化"。
 * 3. **方向**：图像坐标 Y 向下为正。按用户描述，**鼻子下移 = 点头**（`dy > 0`）、
 *    **下巴上移 = 仰头**（`dy < 0`）。同一动作下两个关键点同向，取二者均值 [faceDy]
 *    更抗单点抖动；若两者反向（关键点检测抖动），退回用鼻子。
 *    [invert] 对应设置页的「反转俯仰方向」，打开时两个方向**一起**反转。
 * 4. **阈值**：归一化位移超过 [THRESHOLD]（默认 4.5% 脸高，约 3~5% 的用户预期区间）。
 *    近距离俯视时用 [THRESHOLD_NEAR_DOWN]（更灵敏）——**只作用于上下轴**，不影响左右扭头。
 * 5. **时间与速度**：从 onset（阈值的 40%）涨到阈值必须在 [MOTION_WINDOW_MS] 内完成，
 *    且要么速度 ≥ [MIN_SPEED]，要么在阈值以上保持住（[HOLD_MS]）；单帧位移超过
 *    [MAX_FRAME_STEP] 视为关键点抖动，直接丢弃。
 *
 * ## 本版（v5.22）只观测、不接管触发
 *
 * 符号（哪一侧是"鼻子下移"）与阈值必须用**实测数据**确认，不能猜 —— 前两轮
 * "全部乱了"都是猜符号造成的。所以本版把这条通道**完整跑起来并打日志**，
 * 但触发权仍在原有 pitch 通道手里；下一版按实测符号/阈值把它切成主判据。
 */
class ReferencePointDetector {

    companion object {
        /** 参考点更新的观察窗口（帧数）。 */
        private const val REF_WINDOW = 3

        /** 连续 [REF_WINDOW] 帧的峰峰值小于这个比例（占脸高），认为"头是稳的"。 */
        private const val REF_STILL_RANGE = 0.012f

        /** 默认触发阈值：归一化位移（1.0 = 一个脸高）= 4.5% 脸高。 */
        private const val THRESHOLD = 0.045f

        /** 近距离俯视时的放宽阈值 = 3.2% 脸高（用户要求：只放宽上下方向）。 */
        private const val THRESHOLD_NEAR_DOWN = 0.032f

        /** onset = 阈值的这个比例，用来计时"这次涨得够不够快"。 */
        private const val ONSET_FRACTION = 0.4f

        /** 从 onset 到阈值的最长时间；更慢说明是姿势漂移而不是动作。 */
        private const val MOTION_WINDOW_MS = 700L

        /** 最低速度门限（比例/毫秒）：0.00012 ≈ 300ms 走 3.6% 脸高。 */
        private const val MIN_SPEED = 0.00012f

        /** 达不到速度门限时，必须在阈值以上保持这么久（帧间隔 63~116ms）。 */
        private const val HOLD_MS = 90L

        /** 单帧位移超过这个比例（6% 脸高）视为关键点抖动。 */
        private const val MAX_FRAME_STEP = 0.06f
    }

    /** 与设置页「反转俯仰方向」同步（v5.22）。 */
    @Volatile
    var invert: Boolean = false

    /** 近距离 + 俯视（放宽上下方向的阈值）；由服务每帧同步。 */
    @Volatile
    var nearDownBoost: Boolean = false

    // ---- 实时值（诊断行与触发日志用） ----

    @Volatile
    var noseDy: Float = 0f
        private set

    @Volatile
    var chinDy: Float = 0f
        private set

    /** 判定用的位移：两个关键点同向时取均值。 */
    @Volatile
    var faceDy: Float = 0f
        private set

    @Volatile
    var speed: Float = 0f
        private set

    @Volatile
    var threshold: Float = THRESHOLD
        private set

    @Volatile
    var refNose: Float = 0f
        private set

    @Volatile
    var refChin: Float = 0f
        private set

    /** 累计"参考点通道本来会触发"的次数（本版不真正触发，用于对比实测）。 */
    @Volatile
    var wouldTriggerCount: Int = 0
        private set

    // ---- 内部状态 ----
    private val noseRing = FloatArray(REF_WINDOW)
    private val chinRing = FloatArray(REF_WINDOW)
    private var ringIndex = 0
    private var ringCount = 0
    private var haveRef = false

    private var prevDy = 0f
    private var prevDyAtMs = 0L
    private var onsetAtMs = 0L
    private var reachedAtMs = 0L
    private var lastDir = 0

    /** 最近一次被丢弃的原因（诊断用）。 */
    @Volatile
    var lastReject: String = "-"
        private set

    @Synchronized
    fun reset() {
        ringCount = 0
        ringIndex = 0
        haveRef = false
        prevDy = 0f
        prevDyAtMs = 0L
        onsetAtMs = 0L
        reachedAtMs = 0L
        lastDir = 0
        faceDy = 0f
        noseDy = 0f
        chinDy = 0f
        speed = 0f
        lastReject = "-"
    }

    /**
     * 喂一帧。
     *
     * @return 0 = 无动作；-1 = 点头方向越阈值；+1 = 仰头方向越阈值。
     *         本版（v5.22）调用方**只记录**，不据此触发。
     */
    @Synchronized
    fun onFrame(noseNormY: Float?, chinNormY: Float?, nowMs: Long): Int {
        if (noseNormY == null && chinNormY == null) return 0

        updateReference(noseNormY, chinNormY)
        if (!haveRef) return 0

        // ---- 位移（单位：脸高比例）----
        val nose = noseNormY?.minus(refNose)
        val chin = chinNormY?.minus(refChin)
        noseDy = nose ?: 0f
        chinDy = chin ?: 0f
        val dy = when {
            nose != null && chin != null ->
                // 同向取均值（更抗单点抖动）；反向说明关键点抖动，只信鼻子。
                if (nose * chin > 0f) (nose + chin) / 2f else nose
            nose != null -> nose
            else -> chin ?: 0f
        }
        val step = abs(dy - prevDy)
        faceDy = dy

        // ---- 速度（用最近两个样本，与 pitch 通道同一套做法）----
        val dt = nowMs - prevDyAtMs
        speed = if (prevDyAtMs != 0L && dt in 1..300) abs(dy - prevDy) / dt else 0f
        prevDy = dy
        prevDyAtMs = nowMs

        // ---- 阈值 ----
        threshold = if (nearDownBoost) THRESHOLD_NEAR_DOWN else THRESHOLD
        val magnitude = abs(dy)

        // ---- 方向：图像 Y 向下为正；鼻子/下巴下移 = 点头 ----
        val rawDirection = if (dy > 0f) -1 else 1
        val direction = if (invert) -rawDirection else rawDirection

        val onset = threshold * ONSET_FRACTION
        if (magnitude < onset) {
            onsetAtMs = 0L
            reachedAtMs = 0L
            lastDir = 0
            lastReject = "below-onset"
            return 0
        }
        if (onsetAtMs == 0L || direction != lastDir) {
            onsetAtMs = nowMs
            reachedAtMs = 0L
            lastDir = direction
        }
        if (magnitude < threshold) {
            lastReject = "below-threshold"
            return 0
        }
        if (reachedAtMs == 0L) reachedAtMs = nowMs
        if (step > MAX_FRAME_STEP) {
            lastReject = "frame-step"
            return 0
        }
        if (nowMs - onsetAtMs > MOTION_WINDOW_MS) {
            lastReject = "slow"
            return 0
        }
        // 够快直接放行；否则要在阈值以上保持住（HOLD_MS 约一帧）。
        if (speed < MIN_SPEED && nowMs - reachedAtMs < HOLD_MS) {
            lastReject = "hold-not-met"
            return 0
        }
        lastReject = "ok"
        wouldTriggerCount++
        return direction
    }

    /**
     * 参考点更新：只在头部稳定时把当前关键点位置记为参考点。
     *
     * 稳定判据是"最近 [REF_WINDOW] 帧峰峰值 < [REF_STILL_RANGE]"，
     * 所以动作一开始参考点就冻结在起点上，动作结束、头停下来之后又重新对齐。
     */
    private fun updateReference(noseNormY: Float?, chinNormY: Float?) {
        noseRing[ringIndex] = noseNormY ?: Float.NaN
        chinRing[ringIndex] = chinNormY ?: Float.NaN
        ringIndex = (ringIndex + 1) % REF_WINDOW
        if (ringCount < REF_WINDOW) {
            ringCount++
            if (ringCount < REF_WINDOW) return
        }
        val noseRange = range(noseRing)
        val chinRange = range(chinRing)
        // 两个关键点都稳（或者只有一个可用且它稳）时才更新。
        val noseStable = noseRange != null && noseRange < REF_STILL_RANGE
        val chinStable = chinRange != null && chinRange < REF_STILL_RANGE
        if (!noseStable && !chinStable) return
        if (noseStable) refNose = mid(noseRing)
        if (chinStable) refChin = mid(chinRing)
        haveRef = true
    }

    private fun range(values: FloatArray): Float? {
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (v in values) {
            if (v.isNaN()) continue
            if (v < min) min = v
            if (v > max) max = v
        }
        if (min > max) return null
        return max - min
    }

    private fun mid(values: FloatArray): Float {
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (v in values) {
            if (v.isNaN()) continue
            if (v < min) min = v
            if (v > max) max = v
        }
        if (min > max) return 0f
        return (min + max) / 2f
    }

    /**
     * 一行诊断（v5.22）：把位移链路的每个量与最近一次丢弃原因都打出来。
     *
     * 形如 `ref noseDy=+0.045 chinDy=+0.041 dy=+0.043 thr=0.032 spd=0.00031 opt=ok`。
     */
    fun stateLine(): String =
        "ref noseDy=${"%.3f".format(noseDy)} chinDy=${"%.3f".format(chinDy)} " +
            "dy=${"%.3f".format(faceDy)} thr=${"%.3f".format(threshold)} " +
            "spd=${"%.5f".format(speed)} would=${wouldTriggerCount} opt=$lastReject"
}
