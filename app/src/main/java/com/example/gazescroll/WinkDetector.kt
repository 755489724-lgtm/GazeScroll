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
 * 读到多少、闭了多久、合得多快」在日志里一眼能核对。
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
     * 从「最后一次没闭着」到「第一次深闭」用了多久（毫秒）。
     *
     * 这是本通道**唯一**的"是不是有意动作"判据：真单闭是眼皮"啪"一下合上（实测 63~432ms），
     * 而低头看屏幕时 ML Kit 把一只眼慢慢读低要用好几秒。越小越像真闭眼。
     */
    val onsetMs: Long,
)

/**
 * 单眼闭眼（wink）检测器 —— v5.30 新增，v5.31/v5.32/v5.33 按实机数据三次重定判据。
 *
 * ## 判据（v5.33）
 *
 * 「**一只眼明显比另一只眼闭、而且是"啪"一下闭上的、再保持住**」：
 *
 *  1. **两只眼的读数必须差 ≥ [SEPARATION_MIN]（0.30）** —— 一只明显闭着、另一只明显睁着。
 *     这是核心判据：眨眼 / 半闭时两只眼会一起落在 0.5~0.7（差不到 0.30），必然被挡掉。
 *  2. 另一只眼不能是闭的（读数 ≥ [closedBelow]）—— 两只眼同时闭 = 眨眼，整段作废。
 *  3. **合眼要快**：从「最后一次读到没闭着」到「第一次深闭 < [DEEP_CLOSED_BELOW]」
 *     必须 ≤ [ONSET_MAX_MS]（500ms）。真单闭实测 63~432ms；低头时被慢慢读低要好几秒。
 *  4. 保持 [holdMs]（默认 400ms，可在设置里选 0.4/0.6/0.8/1.0 秒）。
 *  5. 一次单闭只调一组档位：触发后闩锁，**必须重新睁大（> [REOPEN_ABOVE]）**才能再来一次。
 *
 * ## 为什么是这几条（三轮实机数据）
 *
 * - **v5.30**：只有「一只眼低于阈值 + 另一只眼 >0.70 + 保持 1 秒」。低头看屏幕时 ML Kit
 *   会把一只眼**慢慢读低、一读几十秒**，一场 90 秒里被判成 11 次"单闭"，音量从 50 打到 0。
 * - **v5.31**：加了「闭之前必须连续明确睁着(>0.65) ≥600ms」。这条是错的 —— 30cm 俯视时
 *   **睁着的那只眼读数也常在 0.55~0.70**，于是把用户连续 5 次真单闭全否掉（"一点动静没有"）。
 * - **v5.32**：删掉那条、把「另一只眼」的门槛降到"不是闭着"、onset 窗口放到 500ms。
 *   结果**眨眼也能调音量了**（22:25:56：闭的那只 0.54、另一只 0.65 —— 两只眼一起半闭，
 *   只差 0.11 就被判成"单闭"）。原因是**绝对阈值分不开这两类**：眨眼时两只眼一起落在
 *   0.5~0.7，真单闭时闭的那只也能停在 0.54。能分开的只有**两只眼差多少** → 于是有了
 *   判据 1（0.11 vs 0.47/0.82/0.52/0.74，0.30 卡在中间）。
 *
 * 另外记录一条被证伪的假设：**"闭上一只眼时读不到另一只眼的数据"** 在本设备上不成立 ——
 * 翻遍所有历史验证日志（v5.10 起）：**3153 帧有脸画面里，没有任何一帧只缺一只眼的读数**
 * （ML Kit 只要有脸就会同时给出两只眼的概率）。所以"读不到"不能当判据。
 *
 * 本类不触发任何 Android API（只打日志），纯逻辑。
 */
class WinkDetector(
    private val onWink: (WinkEvent) -> Unit,
) {

    companion object {
        private const val TAG = "Wink"

        /**
         * 「重新睁大」的读数（v5.32）：只有重新睁到这么开，才允许下一次单闭。
         *
         * 这是**防连发**用的（一次单闭只调一组档位），不是"起手"判据。
         */
        private const val REOPEN_ABOVE = 0.65f

        /**
         * 两只眼的读数必须差这么多，才算「一只闭着、另一只睁着」（v5.33）。
         *
         * 这是本通道的核心判据。实测（v5.32 日志，用户报"我眨眼也能调音量"）：
         *
         * ```
         * 22:25:56 误触发（眨眼/半闭）  闭的那只=0.54  另一只=0.65  → 差 0.11  ✗
         * 22:26:12 真单闭              闭的那只=0.30  另一只=0.77  → 差 0.47  ✓
         * 22:26:17 真单闭              闭的那只=0.13  另一只=0.95  → 差 0.82  ✓
         * 22:26:25 真单闭              闭的那只=0.43  另一只=0.95  → 差 0.52  ✓
         * 22:26:27 真单闭              闭的那只=0.24  另一只=0.98  → 差 0.74  ✓
         * ```
         *
         * 0.30 正好卡在 0.11 与 0.47 之间。**绝对阈值分不开这两类**（眨眼时两只眼会
         * 一起落在 0.5~0.7，真单闭时闭的那只也能停在 0.54），能分开的只有"两只眼
         * 差多少"——所以判据用它。
         */
        private const val SEPARATION_MIN = 0.30f

        /** 从「最后一次没闭着」到「第一次深闭」的最大用时（v5.32）。 */
        private const val ONSET_MAX_MS = 500L

        /** 「深闭」的读数：真正的合眼会掉到这里。 */
        private const val DEEP_CLOSED_BELOW = 0.30f

        /** 默认保持时长。v5.31 是 600ms，v5.32 按实测（有意单闭 400~620ms）收到 400ms。 */
        const val DEFAULT_HOLD_MS = 400L

        /** 保持时长的可选档位（毫秒）。 */
        val HOLD_OPTIONS = longArrayOf(400L, 600L, 800L, 1000L)

        /** 短于这个时长的单闭不打日志（滤掉单帧抖动）。 */
        private const val LOG_MIN_MS = 250L

        /** 单只眼一直闭着超过这么久 = 读数卡住或用户闭着眼休息，清掉状态。 */
        private const val STUCK_MS = 6000L
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
        /** 最后一次"没闭着"（读数 ≥ 闭眼阈值）的时刻 —— onset 的起点。 */
        var lastNotClosedAtMs = 0L

        /** 本段单闭开始时刻；0 = 当前没有单闭。 */
        var closedSinceMs = 0L

        /** 本段是否已经触发过（一次单闭只调一组）。 */
        var fired = false

        /** 本段里闭着的那只眼的最深读数。 */
        var minReading = 1f

        /** 本段是否已经读到「深闭」。 */
        var deepSeen = false

        /** 起手是否够快。 */
        var onsetOk = false

        /** 从最后一次没闭着到第一次深闭的用时（诊断，-1 = 还没读到深闭）。 */
        var onsetMs = -1L

        /** 是否已经打过「保持够了但起手不合格」的日志。 */
        var reportedBadOnset = false
    }

    private val leftTrack = Track()
    private val rightTrack = Track()

    /** 诊断行用的紧凑状态。 */
    fun stateLine(): String =
        "L=${leftHeldMs}ms R=${rightHeldMs}ms thr=${"%.2f".format(closedBelow)} " +
            "hold=${holdMs}ms fired=$triggerCount last=$lastWinkLabel"

    /**
     * 清掉进行中的单闭（遮挡 / 静止硬锁定 / 服务重绑 / 功能被关掉时调用）。
     *
     * 累计次数不重置：界面上「已调音量 N 档」是这次运行的累计值。
     */
    @Synchronized
    fun reset() {
        clearAll(leftTrack)
        clearAll(rightTrack)
        leftHeldMs = 0L
        rightHeldMs = 0L
    }

    private fun clearAll(t: Track) {
        clearEpisode(t)
        t.lastNotClosedAtMs = 0L
    }

    private fun clearEpisode(t: Track) {
        t.closedSinceMs = 0L
        t.fired = false
        t.minReading = 1f
        t.deepSeen = false
        t.onsetOk = false
        t.onsetMs = -1L
        t.reportedBadOnset = false
    }

    /**
     * 喂一帧的左右眼睁开度。
     *
     * @param left  `leftEyeOpenProbability`，null 表示本帧读不到
     * @param right `rightEyeOpenProbability`，null 表示本帧读不到
     */
    @Synchronized
    fun onEyeProbabilities(left: Float?, right: Float?, nowMs: Long) {
        // 少一只眼的读数，就无从知道"另一只眼是否闭着"，单闭判定的前提不成立。
        // （实测：所有历史日志 3153 帧有脸画面里，**没有任何一帧**只缺一只眼的读数 ——
        //   ML Kit 只要有脸就会同时给出两只眼的概率，所以"读不到另一只眼"不能当判据。）
        if (left == null || right == null) {
            reset()
            return
        }
        val below = closedBelow.coerceIn(0.10f, 0.90f)

        // v5.33：每只眼**各自**记录「最后一次没闭着」的时刻，与另一只眼的状态无关。
        // v5.32 把这一步放在 handle() 里、且在"另一只眼闭着"的提前返回之后，于是
        // 「左眼单闭 → 右眼单闭」这样交替时，右眼的时间戳一直是 0（从没更新过），
        // onset 算成 Long.MAX → 一律判"合眼太慢"。日志里就是那几行
        // `从最后一次没闭着到深闭用了 9223372036854775807ms`。
        if (left >= below) leftTrack.lastNotClosedAtMs = nowMs
        if (right >= below) rightTrack.lastNotClosedAtMs = nowMs

        handle(WinkSide.LEFT, left, right, below, nowMs)
        handle(WinkSide.RIGHT, right, left, below, nowMs)

        leftHeldMs = if (leftTrack.closedSinceMs != 0L) nowMs - leftTrack.closedSinceMs else 0L
        rightHeldMs = if (rightTrack.closedSinceMs != 0L) nowMs - rightTrack.closedSinceMs else 0L
    }

    private fun handle(
        side: WinkSide,
        prob: Float,
        other: Float,
        below: Float,
        nowMs: Long,
    ) {
        val t = if (side == WinkSide.LEFT) leftTrack else rightTrack

        // 两只眼都闭着 = 眨眼 / 眯眼，不是单闭：整段作废，等睁眼后重新开始。
        // （所以「连眨 3 次翻页」永远不可能被这条通道当成单闭。）
        if (other < below) {
            clearEpisode(t)
            return
        }

        // 本眼"没闭着"（"最后一次没闭着"的时刻已由 onEyeProbabilities 各自记好）。
        if (prob >= below) {
            // 重新睁大才算这一页翻过去（下一次单闭要重新起手）。
            if (prob > REOPEN_ABOVE && t.closedSinceMs != 0L) {
                val held = nowMs - t.closedSinceMs
                // 只有"没保持够"时才在这里留痕；保持够了却没触发的情况上面已经写过原因，
                // 再写一行会自相矛盾（"只闭了 1200ms（< 600ms）"就是这么来的）。
                if (!t.fired && held < holdMs && held >= LOG_MIN_MS) {
                    Log.i(
                        TAG,
                        "wink ${side.label} 只闭了 ${held}ms（< ${holdMs}ms）→ 不触发 " +
                            "min=${"%.2f".format(t.minReading)} other=${"%.2f".format(other)} " +
                            "thr=${"%.2f".format(below)} " +
                            "dist=${if (nearTier == true) "near" else "mid/far"}",
                    )
                }
                clearEpisode(t)
            }
            return
        }

        // ---- 闭着（< 闭眼阈值），且另一只眼没闭 ----
        if (t.closedSinceMs == 0L) {
            t.closedSinceMs = nowMs
            t.fired = false
            t.minReading = prob
            t.deepSeen = false
            t.onsetOk = false
            t.onsetMs = -1L
            t.reportedBadOnset = false
        }
        t.minReading = minOf(t.minReading, prob)

        // v5.32：第一次读到「深闭」时结算"合眼用时" —— 这是唯一的"是不是有意动作"判据。
        if (!t.deepSeen && prob < DEEP_CLOSED_BELOW) {
            t.deepSeen = true
            t.onsetMs = if (t.lastNotClosedAtMs == 0L) Long.MAX_VALUE else nowMs - t.lastNotClosedAtMs
            t.onsetOk = t.onsetMs <= ONSET_MAX_MS
        }

        val held = nowMs - t.closedSinceMs
        if (held > STUCK_MS) {
            // 读数卡住（镜片反光 / 检测退化）或用户闭着眼休息：清掉，等睁眼重来。
            clearEpisode(t)
            return
        }

        if (!t.fired && held >= holdMs) {
            val separation = other - prob
            when {
                !t.deepSeen || !t.onsetOk -> if (!t.reportedBadOnset) {
                    // 用户报「单闭不灵」时，这一行直接给出卡在哪一条：合眼太慢（或者根本没闭深）。
                    t.reportedBadOnset = true
                    val onsetText = if (t.onsetMs < 0) {
                        "还没闭到 <${DEEP_CLOSED_BELOW}"
                    } else if (t.onsetMs == Long.MAX_VALUE) {
                        "这只眼在本段之前从没被读到「没闭着」"
                    } else {
                        "${t.onsetMs}ms"
                    }
                    Log.i(
                        TAG,
                        "wink ${side.label} 保持 ${held}ms 但合眼太慢 → 不触发 " +
                            "（从最后一次没闭着到深闭用了 $onsetText，需要 ≤${ONSET_MAX_MS}ms；" +
                            "min=${"%.2f".format(t.minReading)} other=${"%.2f".format(other)} " +
                            "dist=${if (nearTier == true) "near" else "mid/far"}）",
                    )
                }

                // v5.33 核心判据：两只眼的读数必须明显不一样。
                // 眨眼 / 半闭时两只眼会一起落在 0.5~0.7，差不到 0.30，这里就会被挡掉。
                separation < SEPARATION_MIN -> if (!t.reportedBadOnset) {
                    t.reportedBadOnset = true
                    Log.i(
                        TAG,
                        "wink ${side.label} 保持 ${held}ms 但两只眼读数差不多 → 不算单闭 " +
                            "（这只眼=${"%.2f".format(prob)} 另一只=${"%.2f".format(other)} " +
                            "差=${"%.2f".format(separation)} 需要 ≥${"%.2f".format(SEPARATION_MIN)}；" +
                            "min=${"%.2f".format(t.minReading)} " +
                            "dist=${if (nearTier == true) "near" else "mid/far"}）",
                    )
                }

                else -> {
                    t.fired = true
                    triggerCount++
                    lastWinkLabel = "${side.label} ${held}ms"
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
                            onsetMs = t.onsetMs,
                        ),
                    )
                }
            }
        }
        // 落在回差带（闭眼阈值 ≤ prob ≤ [REOPEN_ABOVE]）：保持上一次状态，等一个明确的读数。
    }
}
