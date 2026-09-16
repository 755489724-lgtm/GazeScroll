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
 * 单眼闭眼（wink）检测器 —— v5.30 新增，v5.31 加判据，v5.32 按实机数据重定判据。
 *
 * ## 判据（v5.32）
 *
 * 「**一只眼一下子闭上、另一只眼没闭、然后保持住**」：
 *
 *  1. **另一只眼不能是闭的**（读数 ≥ [closedBelow]）。两只眼同时闭 = 眨眼，整段作废 ——
 *     这条保证单闭通道与眨眼翻页互斥。
 *  2. **"突然闭"**：从「最后一次读到没闭着」到「第一次深闭 < [DEEP_CLOSED_BELOW]」
 *     必须 ≤ [ONSET_MAX_MS]（500ms）。真单闭实测 63~432ms；低头时被慢慢读低要好几秒。
 *  3. 保持 [holdMs]（默认 400ms，用户可在设置里选 0.4/0.6/0.8/1.0 秒）。
 *  4. 一次单闭只调一组档位：触发后闩锁，**必须重新睁大（> [REOPEN_ABOVE]）**才能再来一次。
 *
 * ## 为什么是这两条（两轮实机数据）
 *
 * **v5.30 的教训**：判据只有「一只眼 < 阈值 + 另一只眼 > 0.70 + 保持 1 秒」。低头看屏幕时
 * ML Kit 会把一只眼**慢慢读低、一读几十秒**，一场 90 秒里被判成 11 次"单闭"，把音量从 50
 * 一路打到 0。两者的区别是**下降快慢**，所以 v5.31 加了"起手"两道门。
 *
 * **v5.31 的教训（v5.32 修正）**：那两道门里有一条是错的 —— 「闭之前必须连续明确睁着 ≥600ms」
 * （阈值 0.65）。实测（22:17:56~22:18:03 用户连续单闭，全部没触发）这一条把**真单闭也挡掉了**：
 *
 * ```
 * 22:17:56.245 起手不合格（闭之前只明确睁了 0ms；从睁到深闭用了 294ms；min=0.03）  ← 合得很快，却被"睁得不够久"否掉
 * 22:18:01.677 起手不合格（闭之前只明确睁了 205ms；从睁到深闭用了 432ms；min=0.06）← 同上
 * 22:18:03.063 另一只眼没睁着（other=0.65，门槛却是 >0.70）                    ← 那条"睁着的眼"读到 0.65
 * ```
 *
 * 原因是 30cm 俯视时**睁开的那只眼读数也常在 0.55~0.70**（同距离下的诊断行：
 * `eyeL=0.70 eyeR=0.13`、`eyeL=0.64`、`eyeL=0.62`），0.70/0.65 这两条线卡在了真实读数中间。
 * v5.32 因此：
 *  - 删掉「闭之前明确睁着 ≥600ms」这条（**冗余**：真正的"突然"由 onset ≤500ms 保证）；
 *  - onset 的起点从"最后一次 > 0.65"改成"**最后一次不闭着（≥ 闭眼阈值）**"，这样起手读数
 *    落在 0.55~0.70 时也能算得出真正的"合眼用时"；
 *  - 「另一只眼」的要求从 > 0.70 降到「**不是闭着**（≥ 闭眼阈值）」—— 眨眼时另一只眼只有
 *    0.02~0.10，这条照样能把它分开；
 *  - 默认保持时长 0.6s → **0.4s**（用户实测有意单闭大多 400~620ms，600ms 常常差一点）。
 *
 * 回测：v5.30 那批"慢慢读低"的序列（onset 1.3~2.7 秒）全部被拒；本节日志里
 * onset=63/67/73/99/103/131/163/278/294/432ms 的那批真单闭全部通过。
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
         * 这是**防连发**用的（一次单闭只调一组档位），不是"起手"判据 —— 起手那边只看
         * "有没有闭着"和"合得多快"。
         */
        private const val REOPEN_ABOVE = 0.65f

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
        if (left == null || right == null) {
            reset()
            return
        }
        val below = closedBelow.coerceIn(0.10f, 0.90f)

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

        // 本眼"没闭着"——记下时刻，它就是"合眼用时"的起点。
        if (prob >= below) {
            t.lastNotClosedAtMs = nowMs
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
            if (!t.deepSeen || !t.onsetOk) {
                if (!t.reportedBadOnset) {
                    // 用户报「单闭不灵」时，这一行直接给出卡在哪一条：合眼太慢（或者根本没闭深）。
                    t.reportedBadOnset = true
                    val onsetText = if (t.onsetMs < 0) {
                        "还没闭到 <${DEEP_CLOSED_BELOW}"
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
            } else {
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
        // 落在回差带（闭眼阈值 ≤ prob ≤ [REOPEN_ABOVE]）：保持上一次状态，等一个明确的读数。
    }
}
