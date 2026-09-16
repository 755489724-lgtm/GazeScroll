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
 * 带上了判定当时用到的全部读数，服务那边直接照原样打进 `I/Wink` 日志 —— 这样
 * 「这只眼到底读到多少、另一只眼读到多少、闭了多久、起手快不快」在日志里一眼能核对。
 */
data class WinkEvent(
    val side: WinkSide,
    /** 本次单闭保持了多久（毫秒）。 */
    val heldMs: Long,
    /** 本帧左右眼睁开度（按眼别，不是"闭的那只/睁的那只"）。 */
    val eyeL: Float,
    val eyeR: Float,
    /** 闭着的那只眼在本段里的最深读数。 */
    val minClosed: Float,
    /** 睁着的那只眼在本帧的读数（触发前提是它 > [WinkDetector.openAbove]）。 */
    val otherEye: Float,
    /** 判定用的闭眼阈值。 */
    val closedBelow: Float,
    /** 距离档（来自头部检测器），仅用于日志核对。 */
    val nearTier: Boolean?,
    /** 起手前那只眼「明确睁着」持续了多久（v5.31）。 */
    val openRunMs: Long,
    /** 从「最后一次明确睁着」到「第一次深闭」用了多久（v5.31），越小越像真闭眼。 */
    val onsetMs: Long,
)

/**
 * 单眼闭眼（wink）检测器 —— v5.30 新增，v5.31 加「起手」判据。
 *
 * ## 判据（v5.31）
 *
 * 「**一只眼先明确睁着、然后一下子闭上、再保持住**」，同时另一只眼始终明确睁着：
 *
 *  1. **另一只眼 > [openAbove]**（0.70）—— 两只眼同时低于阈值 = 眨眼/眯眼，整段作废。
 *  2. **起手要"睁得稳"**：闭的那只眼在闭之前必须**连续明确睁着 ≥ [SETTLED_OPEN_MS]**（600ms）。
 *  3. **起手要"闭得快"**：从「最后一次明确睁着」到「第一次深闭 < [DEEP_CLOSED_BELOW]」
 *     必须 ≤ [ONSET_MAX_MS]（350ms）—— 也就是**眼皮是"啪"一下合上的**，不是慢慢眯下去的。
 *  4. 然后保持 [holdMs]（默认 600ms，用户可在设置里选 0.4/0.6/0.8/1.0 秒）。
 *  5. 一次单闭只调一档：触发后闩锁，必须**睁眼**之后重新闭才算下一次。
 *
 * ## 为什么 v5.31 要加 2 和 3（有实机证据）
 *
 * v5.30 装到小米 13 上后，用户在**近距离俯视**刷抖音时被误调音量（22:03:48 那次把
 * 音量从 10 一路调到 0）。日志把根因摊得很清楚：**低头看屏幕时 ML Kit 会把一只眼读成
 * 半闭**，而且不是一瞬间，是**慢慢地、一只眼持续几十秒**被读低：
 *
 * ```
 * 22:02:20 eyeL=0.99 eyeR=0.02   ← 这一段"低"的是右眼
 * 22:02:54 eyeL=0.08 eyeR=0.19
 * 22:03:04 wink 左眼 held=1051ms eyeL=0.06 eyeR=0.86   ← 换成左眼低
 * 22:03:45 eyeL=0.16 eyeR=0.03   ← 两只都低（眨眼通道 2681ms 的闭眼）
 * 22:03:48 wink 左眼 held=1065ms eyeL=0.29 eyeR=0.99 min=0.11  ← 又被当成单闭，音量 10→0
 * ```
 *
 * 一次运行里 11 次「单闭」全是这么来的（左眼 6 次 / 右眼 5 次，人的眼睛不可能单闭几十秒）。
 * 特征很一致：**读数是一帧一帧慢慢滑下去的**（0.99 → 0.66 → 0.38 → 0.16 → 0.06，用了好几秒），
 * 而**有意单闭是眼皮"啪"一下合上**（一帧从 0.9 掉到 0.1 以下）。所以判据取「先睁稳 + 突然闭」——
 * 这也正是本项目在头部通道上用过的同一套思路（v5.26「只认可快速动作」、v5.28「突然性窗口」）。
 *
 * ## 阈值为什么用**原始**眨眼灵敏度
 *
 * 单闭是「完全闭上」的动作（读数会掉到 0.1 以下），不需要按距离放松；用用户自己挑的
 * 原始阈值（默认 0.55）更严，正是防误触要的方向。
 *
 * 本类不触发任何 Android API（只打日志），纯逻辑，方便读也方便测。
 */
class WinkDetector(
    private val onWink: (WinkEvent) -> Unit,
) {

    companion object {
        private const val TAG = "Wink"

        /** 起手前那只眼必须"明确睁着"多久（v5.31）。 */
        const val SETTLED_OPEN_MS = 600L

        /**
         * 「明确睁着」的读数下限（v5.31）。
         *
         * 刻意比 [openAbove]（0.70，用于"另一只眼"的要求）**低一点**：戴眼镜时睁眼读数会
         * 在 0.65~0.75 之间抖（实测日志里有 `eyeL=0.66` 这种帧），用 0.70 当"睁着"的判据
         * 会让起手段频繁被打断、真单闭反而触发不了。0.65 仍远高于闭眼阈值（0.55）。
         */
        private const val OPEN_RUN_ABOVE = 0.65f

        /** 从「最后一次明确睁着」到「第一次深闭」的最大用时（v5.31）。 */
        private const val ONSET_MAX_MS = 350L

        /** 「深闭」的读数（v5.31）：真正的合眼会掉到这里，慢慢眯下去的过程不会。 */
        private const val DEEP_CLOSED_BELOW = 0.30f

        /** 默认保持时长（v5.30 是 1000ms；v5.31 用户反馈太长，默认收到 600ms）。 */
        const val DEFAULT_HOLD_MS = 600L

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

    /** 高于此值算「明确睁着」（用于**另一只眼**的要求）。服务每帧同步。 */
    @Volatile
    var openAbove: Float = 0.70f

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
        // ---- 「明确睁着」的历史（跨段保留，用来判起手）----
        /** 当前这一段明确睁着的起点；0 = 现在没在明确睁着。 */
        var openRunStartMs = 0L

        /** 最后一次读到「明确睁着」的时刻。 */
        var lastOpenAtMs = 0L

        /** 上一段明确睁着持续了多久（段结束时记下来）。 */
        var lastOpenRunMs = 0L

        // ---- 当前单闭段 ----
        /** 本段单闭开始时刻；0 = 当前没有单闭。 */
        var closedSinceMs = 0L

        /** 本段是否已经触发过（一次单闭只调一档）。 */
        var fired = false

        /** 本段里闭着的那只眼的最深读数。 */
        var minReading = 1f

        /** 本段是否已经读到「深闭」。 */
        var deepSeen = false

        /** 起手是否合格（睁得稳 + 闭得快）。 */
        var onsetOk = false

        /** 起手前那只眼明确睁了多久（诊断）。 */
        var openRunMs = 0L

        /** 从最后一次明确睁着到第一次深闭的用时（诊断，-1 = 还没读到深闭）。 */
        var onsetMs = -1L

        /** 是否已经打过「够久但另一只眼没睁着」的日志。 */
        var reportedNotOpen = false

        /** 是否已经打过「够久但起手不合格」的日志。 */
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

    /** 清掉一整段（含"明确睁着"的历史）：换应用 / 重绑 / 功能关闭时用。 */
    private fun clearAll(t: Track) {
        clearEpisode(t)
        t.openRunStartMs = 0L
        t.lastOpenAtMs = 0L
        t.lastOpenRunMs = 0L
    }

    /** 只清掉当前这一段单闭，保留"明确睁着"的历史（睁眼后还要用它判下一次起手）。 */
    private fun clearEpisode(t: Track) {
        t.closedSinceMs = 0L
        t.fired = false
        t.minReading = 1f
        t.deepSeen = false
        t.onsetOk = false
        t.openRunMs = 0L
        t.onsetMs = -1L
        t.reportedNotOpen = false
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
        // 少一只眼的读数，就无从知道"另一只眼是否睁着"，单闭判定的前提不成立。
        if (left == null || right == null) {
            reset()
            return
        }
        val below = closedBelow.coerceIn(0.10f, 0.90f)
        val open = openAbove.coerceIn(below, 0.99f)

        handle(WinkSide.LEFT, left, right, below, open, nowMs)
        handle(WinkSide.RIGHT, right, left, below, open, nowMs)

        leftHeldMs = if (leftTrack.closedSinceMs != 0L) nowMs - leftTrack.closedSinceMs else 0L
        rightHeldMs = if (rightTrack.closedSinceMs != 0L) nowMs - rightTrack.closedSinceMs else 0L
    }

    private fun handle(
        side: WinkSide,
        prob: Float,
        other: Float,
        below: Float,
        open: Float,
        nowMs: Long,
    ) {
        val t = if (side == WinkSide.LEFT) leftTrack else rightTrack

        // 两只眼都闭着 = 眨眼 / 眯眼，不是单闭：整段作废，等睁眼后重新开始。
        // （所以「连眨 3 次翻页」永远不可能被这条通道当成单闭。）
        if (other < below) {
            clearEpisode(t)
            return
        }

        // ---- 明确睁着：维护"这一段睁了多久" ----
        if (prob > OPEN_RUN_ABOVE) {
            if (t.openRunStartMs == 0L) t.openRunStartMs = nowMs
            t.lastOpenAtMs = nowMs
            if (t.closedSinceMs != 0L) {
                // 重新睁眼 → 这一段单闭结束（触发已在下面闩锁过）。
                if (!t.fired && nowMs - t.closedSinceMs >= LOG_MIN_MS) {
                    // 留痕：用户报「单闭不灵」时能直接看到他实际闭了多久、差在哪一步。
                    Log.i(
                        TAG,
                        "wink ${side.label} 只闭了 ${nowMs - t.closedSinceMs}ms（< ${holdMs}ms）→ 不触发 " +
                            "min=${"%.2f".format(t.minReading)} other=${"%.2f".format(other)} " +
                            "thr=${"%.2f".format(below)} " +
                            "dist=${if (nearTier == true) "near" else "mid/far"}",
                    )
                }
                clearEpisode(t)
            }
            return
        }
        // 不再"明确睁着"：把这一段睁眼的时长记下来，供这次闭眼判起手用。
        if (t.openRunStartMs != 0L) {
            t.lastOpenRunMs = t.lastOpenAtMs - t.openRunStartMs
            t.openRunStartMs = 0L
        }

        // ---- 闭着（或落在回差带里）----
        if (prob < below) {
            if (t.closedSinceMs == 0L) {
                t.closedSinceMs = nowMs
                t.fired = false
                t.minReading = prob
                t.deepSeen = false
                t.onsetOk = false
                t.openRunMs = t.lastOpenRunMs
                t.onsetMs = -1L
                t.reportedNotOpen = false
                t.reportedBadOnset = false
            }
            t.minReading = minOf(t.minReading, prob)

            // v5.31：第一次读到「深闭」时结算起手质量 —— 睁得稳不稳、合得快不快。
            if (!t.deepSeen && prob < DEEP_CLOSED_BELOW) {
                t.deepSeen = true
                t.onsetMs = if (t.lastOpenAtMs == 0L) Long.MAX_VALUE else nowMs - t.lastOpenAtMs
                t.onsetOk = t.lastOpenRunMs >= SETTLED_OPEN_MS && t.onsetMs <= ONSET_MAX_MS
            }

            val held = nowMs - t.closedSinceMs
            if (held > STUCK_MS) {
                // 读数卡住（镜片反光 / 检测退化）或用户闭着眼休息：清掉，等睁眼重来。
                clearEpisode(t)
                return
            }

            if (!t.fired && held >= holdMs) {
                when {
                    other <= open -> if (!t.reportedNotOpen) {
                        // 够久了，但另一只眼没明确睁着 —— 宁可漏一次，也不把"眯眼"当成单闭。
                        t.reportedNotOpen = true
                        Log.i(
                            TAG,
                            "wink ${side.label} 保持 ${held}ms 但另一只眼没睁着" +
                                "（other=${"%.2f".format(other)} 需要 > ${"%.2f".format(open)}）→ 不触发 " +
                                "dist=${if (nearTier == true) "near" else "mid/far"}",
                        )
                    }

                    !t.onsetOk -> if (!t.reportedBadOnset) {
                        // v5.31 的核心：低头看屏幕时一只眼会被"慢慢读低"，形态和单闭很像，
                        // 区别就在起手 —— 真单闭是睁得稳稳的然后啪一下合上。这里把量到的
                        // 两个数打出来，用户报"单闭不灵"时可以直接判断卡在哪一条。
                        t.reportedBadOnset = true
                        Log.i(
                            TAG,
                            "wink ${side.label} 保持 ${held}ms 但起手不合格 → 不触发 " +
                                "（闭之前只明确睁了 ${t.openRunMs}ms，需要 ≥${SETTLED_OPEN_MS}ms；" +
                                "从睁到深闭用了 ${if (t.onsetMs < 0) "-" else "${t.onsetMs}ms"}，" +
                                "需要 ≤${ONSET_MAX_MS}ms；min=${"%.2f".format(t.minReading)} " +
                                "other=${"%.2f".format(other)} " +
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
                                openRunMs = t.openRunMs,
                                onsetMs = t.onsetMs,
                            ),
                        )
                    }
                }
            }
        }
        // 落在回差带（闭眼阈值 ≤ prob ≤ [OPEN_RUN_ABOVE]）：保持上一次状态，等一个明确的读数。
    }
}
