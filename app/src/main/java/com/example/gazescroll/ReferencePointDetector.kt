package com.example.gazescroll

import kotlin.math.abs

/**
 * 参考点位移检测器（v5.22 引入，v5.23 增加"平移无关"的第二条轨道）。
 *
 * ## 用户的原始思路
 *
 * > 「用一个参考点，来计算我点头还是仰头。标准的仰头就是我的下巴会往上移动几厘米，
 * > 点头就是鼻子会往下移动几厘米。做一个参考点，**不能硬算**。」
 *
 * 也就是**不看俯仰角**，只看面部关键点的**位移**，并用脸框高度归一化消除远近差异。
 *
 * ## 实测发现（v5.22 首轮日志，26 条对照记录）
 *
 * **符号关系成立** ✓：pitch 通道真实触发时，位移通道的读数与动作一致 ——
 *
 * ```
 * 19:01:53.566  pitchCh=-9.0°(点头)  →  noseDy=+0.086 chinDy=+0.065  脸往下移 ✓
 * 19:01:56.050  pitchCh=+13.0°(仰头) →  noseDy=-0.036 chinDy=-0.034  脸往上移 ✓
 * ```
 *
 * **但绝对位移的噪声底和真实动作一样大** ✗：4.5% 阈值下 `would-trigger` 达到
 * **54 次/分钟**，而真实仰头那一次位移只有 **0.035**（低于阈值）。
 * 原因很直接：**脸在画面里的绝对位置会被"手机本身在动"带着走** —— 抬一下手、
 * 换个姿势都会让整个脸平移，于是"位移"既包含头部转动、也包含手机平移。
 *
 * ## 所以有两条轨道，一起观测、用数据决定用哪条
 *
 *  - **轨道 A（绝对位移）**：`noseNormY` / `chinNormY` 相对参考点的位移。
 *    就是用户描述的那个量（鼻子在画面里下移）。缺点见上：平移不区分。
 *  - **轨道 B（平移无关）**：`noseRelEye` / `chinRelEye`（关键点相对**眼睛中心**的归一化距离）
 *    相对参考点的变化。手机平移时它**不变**，只有头部真的俯仰（透视缩短）才变 ——
 *    同一个思路，但把"手机在动"这个污染源去掉了。
 *
 * 两条轨道的 `dy`、`would` 计数、丢弃原因都打进日志，用同一次实机动作做对照，
 * 谁的信噪比高就用谁（v5.24 切主判据）。
 *
 * ## 仍然只观测、不接管触发
 *
 * 触发权仍在 pitch 通道手里；本类只计算与记录，这样现有可用行为零风险。
 */
class ReferencePointDetector {

    companion object {
        /** 参考点更新的观察窗口（帧数）。 */
        private const val REF_WINDOW = 3

        /** 连续 [REF_WINDOW] 帧的峰峰值小于这个比例（占脸高），认为"头是稳的"。 */
        private const val REF_STILL_RANGE = 0.012f

        /** 默认触发阈值：归一化位移（1.0 = 一个脸高）= 4.5% 脸高。 */
        private const val THRESHOLD = 0.045f

        /**
         * 轨道 B（平移无关）的阈值。
         *
         * 取 2.5%：它不受手机平移污染，所以同一动作的读数比轨道 A 小，
         * 阈值也要相应小 —— 具体值等实测数据出来后 v5.24 定稿。
         */
        private const val REL_THRESHOLD = 0.025f

        /** 近距离俯视时的放宽系数（用户要求：只放宽上下方向）。 */
        private const val NEAR_DOWN_FACTOR = 0.72f

        /** onset = 阈值的这个比例，用来计时"这次涨得够不够快"。 */
        private const val ONSET_FRACTION = 0.4f

        /** 从 onset 到阈值的最长时间；更慢说明是姿势漂移而不是动作。 */
        private const val MOTION_WINDOW_MS = 700L

        /** 最低速度门限（比例/毫秒）。 */
        private const val MIN_SPEED = 0.00012f

        /** 达不到速度门限时，必须在阈值以上保持这么久（帧间隔 63~116ms）。 */
        private const val HOLD_MS = 90L

        /** 单帧位移超过这个比例（8% 脸高）视为关键点抖动（轨道 A）。 */
        private const val MAX_FRAME_STEP = 0.08f
    }

    /**
     * 一条轨道：两个关键点的位移 → 参考点 → 归一化位移 → 判定。
     *
     * @param name 轨道名（`abs` / `rel`），进日志
     * @param threshold 该轨道的默认阈值
     */
    private inner class Track(private val name: String, private val threshold: Float) {
        private val ringA = FloatArray(REF_WINDOW)
        private val ringB = FloatArray(REF_WINDOW)
        private var ringIndex = 0
        private var ringCount = 0
        private var haveRef = false

        private var refA = 0f
        private var refB = 0f
        private var prevDy = 0f
        private var prevAtMs = 0L
        private var onsetAtMs = 0L
        private var reachedAtMs = 0L
        private var lastDir = 0

        /** 当前生效阈值（含近距离俯视放宽）。 */
        var thresholdNow = threshold
            private set

        var dyA = 0f
            private set

        var dyB = 0f
            private set

        /** 判定用的位移：两点同向取均值。 */
        var dy = 0f
            private set

        var speed = 0f
            private set

        var wouldTriggerCount = 0
            private set

        var reject = "-"
            private set

        fun reset() {
            ringCount = 0
            ringIndex = 0
            haveRef = false
            prevDy = 0f
            prevAtMs = 0L
            onsetAtMs = 0L
            reachedAtMs = 0L
            lastDir = 0
            dyA = 0f
            dyB = 0f
            dy = 0f
            speed = 0f
            reject = "-"
        }

        /** @return 0 / -1（点头方向）/ +1（仰头方向） */
        fun update(a: Float?, b: Float?, nowMs: Long): Int {
            if (a == null && b == null) return 0
            pushAndLearnReference(a, b)
            if (!haveRef) return 0

            val da = a?.minus(refA)
            val db = b?.minus(refB)
            dyA = da ?: 0f
            dyB = db ?: 0f
            val value = when {
                da != null && db != null -> if (da * db > 0f) (da + db) / 2f else da
                da != null -> da
                else -> db ?: 0f
            }
            val step = abs(value - prevDy)
            dy = value

            val dt = nowMs - prevAtMs
            speed = if (prevAtMs != 0L && dt in 1..300) abs(value - prevDy) / dt else 0f
            prevDy = value
            prevAtMs = nowMs

            thresholdNow = threshold * (if (nearDownBoost) NEAR_DOWN_FACTOR else 1f)
            val magnitude = abs(value)
            // 图像坐标 Y 向下为正：位移为正 = 脸/鼻子下移 = 点头。
            val rawDirection = if (value > 0f) -1 else 1
            val direction = if (invert) -rawDirection else rawDirection

            val onset = thresholdNow * ONSET_FRACTION
            if (magnitude < onset) {
                onsetAtMs = 0L
                reachedAtMs = 0L
                lastDir = 0
                reject = "below-onset"
                return 0
            }
            if (onsetAtMs == 0L || direction != lastDir) {
                onsetAtMs = nowMs
                reachedAtMs = 0L
                lastDir = direction
            }
            if (magnitude < thresholdNow) {
                reject = "below-threshold"
                return 0
            }
            if (reachedAtMs == 0L) reachedAtMs = nowMs
            if (step > MAX_FRAME_STEP) {
                reject = "frame-step"
                return 0
            }
            if (nowMs - onsetAtMs > MOTION_WINDOW_MS) {
                reject = "slow"
                return 0
            }
            if (speed < MIN_SPEED && nowMs - reachedAtMs < HOLD_MS) {
                reject = "hold-not-met"
                return 0
            }
            reject = "ok"
            wouldTriggerCount++
            return direction
        }

        /** 只在头部稳定时更新参考点。 */
        private fun pushAndLearnReference(a: Float?, b: Float?) {
            ringA[ringIndex] = a ?: Float.NaN
            ringB[ringIndex] = b ?: Float.NaN
            ringIndex = (ringIndex + 1) % REF_WINDOW
            if (ringCount < REF_WINDOW) {
                ringCount++
                if (ringCount < REF_WINDOW) return
            }
            val rangeA = range(ringA)
            val rangeB = range(ringB)
            val stableA = rangeA != null && rangeA < REF_STILL_RANGE
            val stableB = rangeB != null && rangeB < REF_STILL_RANGE
            if (!stableA && !stableB) return
            if (stableA) refA = mid(ringA)
            if (stableB) refB = mid(ringB)
            haveRef = true
        }

        /** 一行诊断：`abs dy=+0.075 thr=0.045 would=3 opt=ok`。 */
        fun stateLine(): String =
            "$name dy=${"%+.3f".format(dy)} thr=${"%.3f".format(thresholdNow)} " +
                "spd=${"%.5f".format(speed)} would=$wouldTriggerCount opt=$reject"
    }

    /** 与设置页「反转俯仰方向」同步。 */
    @Volatile
    var invert: Boolean = false

    /** 近距离 + 俯视（放宽上下方向的阈值）；由服务每帧同步。 */
    @Volatile
    var nearDownBoost: Boolean = false

    private val trackAbs = Track("abs", THRESHOLD)
    private val trackRel = Track("rel", REL_THRESHOLD)

    /** 轨道 A：绝对位移（用户描述的"鼻子在画面里下移"）。 */
    val absDy: Float get() = trackAbs.dy

    /** 轨道 B：平移无关（关键点相对眼睛）。 */
    val relDy: Float get() = trackRel.dy

    @Synchronized
    fun reset() {
        trackAbs.reset()
        trackRel.reset()
    }

    /**
     * 喂一帧。四条输入分别是：鼻子的绝对/相对位置、下巴的绝对/相对位置。
     *
     * @return 0 = 无动作；±1 = **轨道 B（平移无关）**越阈值；±2 = **轨道 A（绝对位移）**越阈值。
     *         正负号 = 方向（负 = 点头方向）。本版调用方**只记录**，不据此触发。
     */
    @Synchronized
    fun onFrame(
        noseNormY: Float?,
        chinNormY: Float?,
        noseRelEye: Float?,
        chinRelEye: Float?,
        nowMs: Long,
    ): Int {
        val absDir = trackAbs.update(noseNormY, chinNormY, nowMs)
        val relDir = trackRel.update(noseRelEye, chinRelEye, nowMs)
        // 优先报"平移无关"那条轨道（它更干净）；两条都照常计数与记录。
        if (relDir != 0) return if (relDir > 0) 1 else -1
        if (absDir != 0) return if (absDir > 0) 2 else -2
        return 0
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

    /** 一行诊断：两条轨道都打出来。 */
    fun stateLine(): String = trackAbs.stateLine() + " | " + trackRel.stateLine()
}
