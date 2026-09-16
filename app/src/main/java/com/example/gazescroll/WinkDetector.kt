package com.example.gazescroll

import android.util.Log

/**
 * 单闭的是哪只眼（v5.30）。
 *
 * 用户说法：右眼闭 = 调高音量、左眼闭 = 调低音量（默认），方向可以在设置页各自反向。
 */
enum class WinkSide(val label: String) {
    LEFT("左眼"),
    RIGHT("右眼"),
}

/**
 * 一次被确认的「有意单闭」。
 *
 * 带上判定当时的全部读数，服务那边原样打进 `I/Wink` 日志 —— 「这只眼读到多少、另一只眼
 * 读到多少、闭了多久、最近这只眼有多常被读成闭着」在日志里一眼能核对。
 */
data class WinkEvent(
    val side: WinkSide,
    /** 本次单闭保持了多久（毫秒）。 */
    val heldMs: Long,
    /** 本帧左右眼睁开度（按眼别）。 */
    val eyeL: Float,
    val eyeR: Float,
    /** 闭着的那只眼在本段里的最深读数。 */
    val minClosed: Float,
    /** 睁着的那只眼在本帧的读数。 */
    val otherEye: Float,
    /** 判定用的闭眼阈值。 */
    val closedBelow: Float,
    /** 距离档（来自头部检测器），仅用于日志核对。 */
    val nearTier: Boolean?,
    /**
     * 本次单闭**开始时**这只眼「最近有多常被读成闭着」（0~1，约 5 秒的指数滑动平均）。
     *
     * 这是本通道判断"是不是有意动作"的时间域判据：有意单闭是偶尔闭一下（≈0.05），
     * 而低头看屏幕时 ML Kit 会把这**一只眼反复读低**（≈0.4~0.9）。
     */
    val dutyAtStart: Float,
)

/**
 * 单眼闭眼（wink）检测器 —— v5.30 新增，v5.31→v5.34 按实机数据四次重定判据。
 *
 * ## 判据（v5.34，全部要满足）
 *
 *  1. **两只眼的读数差 ≥ [SEPARATION_MIN]（0.30）** —— 一只明显闭着、另一只明显睁着。
 *  2. 另一只眼不是闭着（≥ [closedBelow]）—— 两只眼同时闭 = 眨眼，不判定。
 *  3. 保持 [holdMs]（默认 400ms），且这条闭合不超过 [MAX_CLOSURE_MS]（闭太久是休息/眯眼）。
 *  4. **这只眼"最近不常被读成闭着"**：闭合开始时 [Track.duty] ≤ [DUTY_MAX]（0.28）。
 *  5. 一次单闭只调一组档位：触发后闩锁，**必须重新睁大（> [REOPEN_ABOVE]）**才能再来一次。
 *
 * ## 为什么最终是这几条（四轮实机数据，每一步都留了证据）
 *
 * - **v5.30**「一只眼 < 阈值 + 另一只眼 >0.70 + 保持 1 秒」：低头看屏幕时 ML Kit 把一只眼
 *   **反复读低**，90 秒里被判成 11 次"单闭"，音量从 50 打到 0。
 * - **v5.31** 加「闭之前必须连续明确睁着(>0.65) ≥600ms」：错。30cm 俯视时睁着的那只眼读数
 *   也常在 0.55~0.70 → 用户连续 5 次真单闭全被否（"一点动静没有"）。
 * - **v5.32** 删掉那条、把"另一只眼"门槛降到"不是闭着"：结果**眨眼也能调音量**
 *   （22:25:56：2 只眼一起半闭 0.54/0.65，差只有 0.11 就被判成单闭）。
 * - **v5.33** 加「合眼 ≤500ms」当核心判据：**误触没了，但真单闭又被挡掉大半**。事后核对
 *   （本次日志）发现这条根本不能用 —— ML Kit 的概率是逐帧软分类，**真单闭的下降也常常很慢**：
 *
 *   ```
 *   成功：held=406ms sep=0.88 onset=88ms     被拒：held=466ms sep=0.93 onset=658ms
 *   成功：held=475ms sep=0.84 onset=100ms    被拒：held=449ms sep=0.99 onset=1216ms
 *   成功：held=410ms sep=0.50 onset=147ms    被拒：held=480ms sep=1.00 onset=1732ms
 *   ```
 *
 *   被拒的那几条 sep 高达 0.93~1.00 —— 一眼就是有意单闭（一只 0.0x、另一只 0.9x），
 *   却因为"概率下降慢"被判成误触。而 v5.30 那批误触的 onset 是 1.6~3.2 秒，**与真单闭重叠**
 *   → 结论：**onset 分不开这两类，判据废掉**。
 * - **v5.34** 换成时间域判据：不看"这一下多快"，而看"**这只眼最近有多常被读成闭着**"。
 *   有意单闭是偶尔闭一下（duty≈0.05，用户实测那些被拒的也都属于这一类）；低头眯眼是这只眼
 *   被**反复**读低（v5.30 那批实测 duty≈0.4~0.9，因为同一次低头里它一直被读低）。
 *   duty 是约 5 秒的指数滑动平均，对单帧噪声不敏感，也不会因为"某一下闭得慢"就误杀。
 *
 * ## 另一条被证伪的假设（用户提出，已留档）
 *
 * 「闭一只眼时读不到另一只眼的数据」在本设备上不成立：全部历史日志（v5.10 起 33 个文件）
 * **3153 帧有脸画面里，没有任何一帧只缺一只眼的读数** —— ML Kit 只要有脸就同时给出两只眼的
 * 概率，闭上那只给的是 0.02~0.15 的低值。所以"读不到"不能当判据。
 *
 * 本类不触发任何 Android API（只打日志），纯逻辑。
 */
class WinkDetector(
    private val onWink: (WinkEvent) -> Unit,
) {

    companion object {
        private const val TAG = "Wink"

        /** 「重新睁大」的读数：只有重新睁到这么开，才解除触发闩锁。 */
        private const val REOPEN_ABOVE = 0.65f

        /** 两只眼的读数必须差这么多，才算「一只闭着、另一只睁着」。 */
        private const val SEPARATION_MIN = 0.30f

        /** 「深闭」的读数。 */
        private const val DEEP_CLOSED_BELOW = 0.30f

        /** 默认保持时长（实测用户有意单闭 400~620ms）。 */
        const val DEFAULT_HOLD_MS = 400L

        /** 保持时长的可选档位（毫秒）。 */
        val HOLD_OPTIONS = longArrayOf(400L, 600L, 800L, 1000L)

        /** 短于这个时长的单闭不打日志（滤掉单帧抖动）。 */
        private const val LOG_MIN_MS = 250L

        /** 单次闭合超过这个时长 = 休息 / 眯眼，不算单闭。 */
        private const val MAX_CLOSURE_MS = 2500L

        /** 一只眼一直闭着超过这么久 = 读数卡住，清掉状态等睁眼。 */
        private const val STUCK_MS = 6000L

        /** duty 的时间常数（毫秒）：约 5 秒的指数滑动平均。 */
        private const val DUTY_WINDOW_MS = 5000f

        /**
         * 闭合开始时这只眼的 duty 上限。
         *
         * 实测：有意单闭 ≈0.02~0.10（偶尔闭一下）；低头时被反复读低 ≈0.4~0.9。
         * 0.28 卡在中间，并且留出"连续点几次音量"的余量（1 秒内闭 2 次也只到 ~0.2）。
         */
        private const val DUTY_MAX = 0.28f
    }

    /** 需要保持多久（毫秒）。服务每帧同步，日志与界面都显示它。 */
    @Volatile
    var holdMs: Long = DEFAULT_HOLD_MS

    /** 低于此值算「闭着眼」。服务每帧同步成用户挑的眨眼灵敏度。 */
    @Volatile
    var closedBelow: Float = 0.55f

    /** 距离档（只用于日志）。 */
    @Volatile
    var nearTier: Boolean? = null

    /** 已触发的单闭次数（每次 = 一组音量档位）。 */
    @Volatile
    var triggerCount: Int = 0
        private set

    /** 左眼当前已保持的单闭时长（毫秒）；0 = 左眼没有在单闭。 */
    @Volatile
    var leftHeldMs: Long = 0L
        private set

    /** 右眼当前已保持的单闭时长（毫秒）；0 = 右眼没有在单闭。 */
    @Volatile
    var rightHeldMs: Long = 0L
        private set

    /** 最近一次触发的文字描述（诊断行 / 界面显示用）。 */
    @Volatile
    var lastWinkLabel: String = "-"
        private set

    /** 单只眼的状态。 */
    private class Track {
        /** 上一帧时刻，用来算 duty 的时间权重。 */
        var lastFrameAtMs = 0L

        /** 「最近这只眼有多常被读成闭着」：约 5 秒的指数滑动平均（0~1）。 */
        var duty = 0f

        /** 本次闭合的起点；0 = 当前没有闭合。 */
        var closedSinceMs = 0L

        /** 本次闭合开始时的 duty（诊断 + 判据）。 */
        var dutyAtStart = 0f

        /** 触发闩锁：触发后必须重新睁大（> [REOPEN_ABOVE]）才解除。 */
        var fired = false

        /** 本段里闭着的那只眼的最深读数。 */
        var minReading = 1f

        /** 是否已经打过这一段的拒绝日志。 */
        var reportedReject = false
    }

    private val leftTrack = Track()
    private val rightTrack = Track()

    /** 诊断行用的紧凑状态。 */
    fun stateLine(): String =
        "L=${leftHeldMs}ms(duty=${"%.2f".format(leftTrack.duty)}) " +
            "R=${rightHeldMs}ms(duty=${"%.2f".format(rightTrack.duty)}) " +
            "thr=${"%.2f".format(closedBelow)} hold=${holdMs}ms fired=$triggerCount last=$lastWinkLabel"

    /**
     * 清掉进行中的单闭（遮挡 / 静止硬锁定 / 服务重绑 / 功能被关掉时调用）。
     *
     * duty 也一起清：下一次开始重新统计"最近有多常被读成闭着"，避免把遮挡前的旧状态带过来。
     */
    @Synchronized
    fun reset() {
        clearAll(leftTrack)
        clearAll(rightTrack)
        leftHeldMs = 0L
        rightHeldMs = 0L
    }

    private fun clearAll(t: Track) {
        clearClosure(t)
        t.lastFrameAtMs = 0L
        t.duty = 0f
        t.fired = false
    }

    private fun clearClosure(t: Track) {
        t.closedSinceMs = 0L
        t.dutyAtStart = 0f
        t.minReading = 1f
        t.reportedReject = false
    }

    /**
     * 喂一帧的左右眼睁开度。
     *
     * @param left  `leftEyeOpenProbability`，null 表示本帧读不到
     * @param right `rightEyeOpenProbability`，null 表示本帧读不到
     */
    @Synchronized
    fun onEyeProbabilities(left: Float?, right: Float?, nowMs: Long) {
        // 少一只眼的读数，就无从知道"另一只眼是否闭着"，判定的前提不成立。
        // （实测：所有历史日志 3153 帧有脸画面里，没有任何一帧只缺一只眼的读数。）
        if (left == null || right == null) {
            reset()
            return
        }
        val below = closedBelow.coerceIn(0.10f, 0.90f)

        // 每只眼各自记账 —— 与另一只眼的状态无关。
        // （v5.32 曾把这段放在"另一只眼闭着就作废"的提前返回之后，于是左右眼交替单闭时
        //   右眼的状态从不更新，一行行都是 9223372036854775807ms。）
        updateEye(leftTrack, left, below, nowMs)
        updateEye(rightTrack, right, below, nowMs)

        evaluate(WinkSide.LEFT, leftTrack, left, right, below, nowMs)
        evaluate(WinkSide.RIGHT, rightTrack, right, left, below, nowMs)

        leftHeldMs = if (leftTrack.closedSinceMs != 0L) nowMs - leftTrack.closedSinceMs else 0L
        rightHeldMs = if (rightTrack.closedSinceMs != 0L) nowMs - rightTrack.closedSinceMs else 0L
    }

    /** 单只眼的物理状态：duty、闭合段、闩锁。与另一只眼完全无关。 */
    private fun updateEye(t: Track, prob: Float, below: Float, nowMs: Long) {
        // duty：这只眼"最近有多常被读成闭着"（时间加权的指数滑动平均）。
        val dt = if (t.lastFrameAtMs == 0L) 0f else (nowMs - t.lastFrameAtMs).coerceIn(0L, 500L).toFloat()
        t.lastFrameAtMs = nowMs
        if (dt > 0f) {
            val closedNow = if (prob < below) 1f else 0f
            val alpha = (dt / DUTY_WINDOW_MS).coerceIn(0f, 1f)
            t.duty += (closedNow - t.duty) * alpha
        }

        if (prob >= below) {
            // 睁开：这一段闭合结束。
            if (t.closedSinceMs != 0L) {
                val held = nowMs - t.closedSinceMs
                if (!t.fired && held < holdMs && held >= LOG_MIN_MS) {
                    Log.i(
                        TAG,
                        "wink ${sideLabel(t)} 只闭了 ${held}ms（< ${holdMs}ms）→ 不触发 " +
                            "min=${"%.2f".format(t.minReading)} " +
                            "duty=${"%.2f".format(t.dutyAtStart)} " +
                            "dist=${if (nearTier == true) "near" else "mid/far"}",
                    )
                }
                clearClosure(t)
            }
            // 重新睁大才解除闩锁（防止一只眼在阈值附近反复抖动时连续触发）。
            if (prob > REOPEN_ABOVE) t.fired = false
            return
        }

        // 闭着：开启 / 延续这一段。
        if (t.closedSinceMs == 0L) {
            t.closedSinceMs = nowMs
            t.dutyAtStart = t.duty
            t.minReading = prob
            t.reportedReject = false
        }
        t.minReading = minOf(t.minReading, prob)

        val held = nowMs - t.closedSinceMs
        if (held > STUCK_MS) {
            Log.i(TAG, "wink ${sideLabel(t)} 单眼读数卡住 ${held}ms → 清掉，等睁眼")
            clearClosure(t)
        }
    }

    /** 触发评估：闭着的那只眼 + 另一只眼的读数一起看。 */
    private fun evaluate(
        side: WinkSide,
        t: Track,
        prob: Float,
        other: Float,
        below: Float,
        nowMs: Long,
    ) {
        if (t.closedSinceMs == 0L || t.fired) return
        // 另一只眼也闭着 = 眨眼 / 眯眼：这一刻不判定（等它睁开再继续看这只眼）。
        if (other < below) return

        val held = nowMs - t.closedSinceMs
        if (held < holdMs) return

        val label = if (side == WinkSide.LEFT) "左眼" else "右眼"
        val separation = other - prob
        val reason = when {
            held > MAX_CLOSURE_MS ->
                "闭了 ${held}ms 太久（>${MAX_CLOSURE_MS}ms）= 休息 / 眯眼"
            separation < SEPARATION_MIN ->
                "两只眼读数差不多（这只眼=${"%.2f".format(prob)} 另一只=${"%.2f".format(other)} " +
                    "差=${"%.2f".format(separation)} 需要 ≥${"%.2f".format(SEPARATION_MIN)}）"
            t.dutyAtStart > DUTY_MAX ->
                "最近这只眼老被读成闭着（duty=${"%.2f".format(t.dutyAtStart)} " +
                    "需要 ≤${"%.2f".format(DUTY_MAX)}）= 低头眯眼，不是单闭"
            else -> null
        }
        if (reason != null) {
            if (!t.reportedReject) {
                t.reportedReject = true
                Log.i(
                    TAG,
                    "wink $label 保持 ${held}ms 但不算单闭 → $reason " +
                        "（min=${"%.2f".format(t.minReading)} other=${"%.2f".format(other)} " +
                        "dist=${if (nearTier == true) "near" else "mid/far"}）",
                )
            }
            return
        }

        t.fired = true
        triggerCount++
        lastWinkLabel = "$label ${held}ms"
        onWink(
            WinkEvent(
                side = side,
                heldMs = held,
                // 按眼别记录，避免"闭的那只/睁的那只"在日志里看错眼。
                eyeL = if (side == WinkSide.LEFT) prob else other,
                eyeR = if (side == WinkSide.LEFT) other else prob,
                minClosed = t.minReading,
                otherEye = other,
                closedBelow = below,
                nearTier = nearTier,
                dutyAtStart = t.dutyAtStart,
            ),
        )
    }

    /** 日志里区分左右眼的辅助（Track 本身不带眼别）。 */
    private fun sideLabel(t: Track): String = if (t === leftTrack) "左眼" else "右眼"
}
