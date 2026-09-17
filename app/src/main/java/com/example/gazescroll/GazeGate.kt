package com.example.gazescroll

import java.util.Locale

/**
 * 注视门的三种模式（v5.43）。
 *
 * [OFF] 不算、不拦（逐帧开销为零）。
 * [OBSERVE] 每帧照算并把结论打进日志/设置页，**但不拦任何触发**——用来量"这道门会拦掉多少
 *   真实动作"。v5.37 的教训是加严过头，所以先量再拦。
 * [ENFORCE] 真的拦：判据不成立时那次触发被丢弃（日志与界面都会写明原因）。
 */
enum class GateMode(val label: String) {
    OFF("off"),
    OBSERVE("observe"),
    ENFORCE("enforce"),
}

/** 触发通道——不同通道要豁免不同的判据（扭头本身就是偏航、歪头本身就是滚转）。 */
enum class GateChannel {
    BLINK,
    NOD,
    TURN,
    MOUTH,
    TILT,
    OTHER,
}

/** 一次注视门的结论。[detail] 里带当时的具体读数，方便事后核对。 */
data class GateDecision(val allowed: Boolean, val reason: String?, val detail: String)

/**
 * v5.43「注视门」：**用户的眼睛得盯着屏幕，才允许触发翻页**（用户点名的新功能）。
 *
 * ## 这道门到底能看见什么（2026-09-17 四轮实机采集的结论，不是推理）
 *
 * ML Kit 的人脸检测**没有虹膜**，所以"眼球转到哪"读不出来。实测（`probe-analyze` 的分位数）：
 *
 * | 判据 | 盯着屏幕 | 不盯（头转开 / 眼睛离开） | 可用性 |
 * | --- | --- | --- | --- |
 * | 脸在不在画面里 | 在 | 不在 | ✅ 最硬 |
 * | 脸够不够大（`faceRatio`） | 0.34~0.57 | 手机放桌上/人走开 → 很小 | ✅ |
 * | **偏航** `eY − 基准` | p95 ≤ 2.6° | 头转开 **±11~13°** | ✅ 但扭头手势本身要豁免 |
 * | **滚转** `eZ − 基准`（躺下/侧脸） | 0~5° | 侧躺可以到 30°+ | ✅ 但歪头手势要豁免 |
 * | **睁眼概率**（两只眼平均） | 50cm：中位数 **1.00** | 眼睛往下看别处：中位数 **0.01** | ⚠️ **只在 ≥0.45 距离可用**；30cm 下两边都是 0.03~0.21、分布重叠 |
 *
 * 关键发现：**"眼睛看别处"不是完全测不出来** —— 头不动时偏航/滚转确实一点都看不出来
 * （实测段1 vs 段2 的 `eY` 中位数 -0.1 vs -2.2，完全重叠），但**眼皮遮住眼球会让
 * ML Kit 的睁眼概率从中位数 1.00 掉到 0.01**，这个差在 50cm 下干净得刺眼。
 * 而 30cm 近距离下 ML Kit 本来就把它读得很低（看屏幕时中位数也只有 0.21），所以
 * **近距离档必须跳过这一条**，否则会把"老老实实盯着屏幕的近距离使用"整段拦掉。
 *
 * ## 为什么用"睁眼占比"而不是瞬时值
 *
 * 眨眼本身就要经过"闭眼"这一帧（[BlinkDetector] 的触发点就在闭眼上）。
 * 如果用瞬时值当门，眨眼通道会被自己拦死。所以取**最近 [EYE_WINDOW_MS] 里
 * "两只眼平均睁着"的帧占比**：一次眨眼只占 2~6 帧（约 0.2 秒），占比仍有 ~0.8，
 * 而"眼睛真的离开屏幕"是持续几秒的 → 占比掉到 0.1 以下。这是拿时间域换掉瞬时判定，
 * 与 v5.34 学到的教训一致（软分类输出不能当物理量用）。
 *
 * ## 判定失败一律**放开**（fail-open）
 *
 * 任一读数缺失（没校准、没有脸、没有睁眼概率）都不拦 —— 这道门是防误触的，不是防触发的。
 */
class GazeGate {

    companion object {
        /** 脸小到这个比例以下 = 手机不在手里（放桌上了 / 人走开了）。 */
        const val MIN_FACE_RATIO = 0.20f

        /** 相对本人偏航基准线，超过这个角度就算"头转开了"。 */
        const val MAX_YAW_DEG = 10f

        /** 相对本人滚转基准线，超过这个角度就算"躺下 / 侧脸"。 */
        const val MAX_ROLL_DEG = 20f

        /** 睁眼占比的统计窗口。 */
        const val EYE_WINDOW_MS = 1500L

        /** 单帧算"睁着"的门槛（两只眼平均）。 */
        const val EYE_OPEN_MIN = 0.5f

        /** 窗口内"睁着"的帧占比低于这个值 = 眼睛确实离开屏幕了。 */
        const val EYE_DUTY_MIN = 0.30f

        /**
         * 脸占比 ≥ 这个值就算近距离档 —— **近距离档跳过"睁眼占比"这一条**。
         *
         * 依据：30cm 实测"盯着屏幕"的睁眼中位数只有 0.21（ML Kit 近距离本来就读得低），
         * 而"眼睛看别处"是 0.03，两者分布大面积重叠（p5~p95 都是 0.01~0.95）。
         * 拿它当门会把近距离正常使用整段拦掉。
         */
        const val EYE_NEAR_SKIP_RATIO = 0.45f
    }

    /** (时刻, 那一帧两只眼平均是否算"睁着")。 */
    private val openSamples = ArrayDeque<Pair<Long, Boolean>>()

    /** 每帧喂一次：更新"睁眼占比"的滑窗。 */
    fun onFrame(eyeOpenLeft: Float?, eyeOpenRight: Float?, nowMs: Long) {
        val left = eyeOpenLeft
        val right = eyeOpenRight
        if (left == null && right == null) {
            // 没有读数（没脸）就不算"睁着"，也不保留旧样本 —— 否则丢脸回来会带着过期数据。
            openSamples.clear()
            return
        }
        val mean = when {
            left != null && right != null -> (left + right) / 2f
            left != null -> left
            else -> right ?: 0f
        }
        openSamples.addLast(nowMs to (mean >= EYE_OPEN_MIN))
        while (openSamples.isNotEmpty() &&
            nowMs - openSamples.first().first > EYE_WINDOW_MS
        ) {
            openSamples.removeFirst()
        }
    }

    /** 最近一个窗口里"睁着"的帧占比；没有样本时返回 null（= 不拦）。 */
    fun eyeOpenDuty(): Float? {
        if (openSamples.isEmpty()) return null
        var open = 0
        for (sample in openSamples) if (sample.second) open++
        return open.toFloat() / openSamples.size
    }

    /** 清空窗口（换应用 / 重绑 / 丢脸很久之后调用）。 */
    fun reset() {
        openSamples.clear()
    }

    /**
     * 判一次。任一读数缺失都不拦（fail-open）。
     *
     * @param yawDeltaDeg 当前偏航 − 本人偏航基准线（度）；null = 没有基准线
     * @param rollDeltaDeg 当前滚转 − 本人滚转基准线（度）；null = 没有基准线
     */
    fun decide(
        channel: GateChannel,
        faceDetected: Boolean,
        faceRatio: Float?,
        yawDeltaDeg: Float?,
        rollDeltaDeg: Float?,
    ): GateDecision {
        if (!faceDetected) return blocked("no-face", "")

        val ratio = faceRatio
        if (ratio != null && ratio < MIN_FACE_RATIO) {
            return blocked("too-far", "faceRatio=${fmt(ratio)}")
        }

        // 扭头手势本身就是偏航 → 那条通道不看偏航；歪头手势同理。
        if (channel != GateChannel.TURN && yawDeltaDeg != null &&
            kotlin.math.abs(yawDeltaDeg) > MAX_YAW_DEG
        ) {
            return blocked("head-turned", "yaw=${fmtSigned(yawDeltaDeg)}°")
        }
        if (channel != GateChannel.TILT && rollDeltaDeg != null &&
            kotlin.math.abs(rollDeltaDeg) > MAX_ROLL_DEG
        ) {
            return blocked("head-tilted", "roll=${fmtSigned(rollDeltaDeg)}°")
        }

        // 近距离档跳过"睁眼占比"（实机证明那一段它分不开）。
        if (ratio != null && ratio < EYE_NEAR_SKIP_RATIO) {
            val duty = eyeOpenDuty()
            if (duty != null && duty < EYE_DUTY_MIN) {
                return blocked("eyes-away", "duty=${fmt(duty)}")
            }
        }
        return GateDecision(true, null, "")
    }

    /** 诊断行 / 设置页用的一小段文字。 */
    fun describe(): String {
        val duty = eyeOpenDuty()
        return "eyeDuty=${duty?.let { fmt(it) } ?: "-"}"
    }

    private fun blocked(reason: String, detail: String) = GateDecision(false, reason, detail)

    private fun fmt(v: Float): String = String.format(Locale.US, "%.2f", v)

    private fun fmtSigned(v: Float): String = String.format(Locale.US, "%+.1f", v)
}
