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
 * 「这只眼到底读到多少、另一只眼读到多少、闭了多久」在日志里一眼能核对。
 */
data class WinkEvent(
    val side: WinkSide,
    /** 本次单闭保持了多久（毫秒）。 */
    val heldMs: Long,
    /** 本帧左右眼睁开度。 */
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
)

/**
 * 单眼闭眼（wink）检测器 —— v5.30 新增，用来调音量。
 *
 * ## 判据
 *
 * 「**一只眼闭着、另一只眼明确睁着，并且保持 ≥ 1 秒**」才算一次有意单闭：
 *
 *  - 闭的那只眼读数 < [closedBelow]（与眨眼共用同一对阈值，也就是用户在设置里挑的
 *    眨眼灵敏度）。**不按距离放松阈值**：单闭是一个「完全闭上」的动作，读数会掉到
 *    0.1 以下，用原始阈值（默认 0.55）更严，正是防误触要的方向。
 *  - 另一只眼读数 > [openAbove]（默认 0.70，与眨眼的"重新睁开"阈值同一个）。
 *    两只眼同时低于阈值 = 眨眼（或眯眼），整段作废 —— 所以这条通道和眨眼通道
 *    不可能同时成立，互不干扰。
 *  - 保持满 [DEFAULT_HOLD_MS]（1 秒）才触发。实测帧间隔 63~116ms，1 秒 ≈ 9~16 帧，
 *    所以单帧噪声凑不出来。
 *
 * ## 防误触（用户明确要求「1 秒以上」，这里再补三条）
 *
 *  1. **一次单闭只调一档**：触发后闩锁（[Track.fired]），必须**睁眼**才能开始下一段。
 *     一直闭着眼不会每 1 秒掉一格音量（那会把音量一路推到 0）。
 *  2. **短于 1 秒的闭眼一律不触发**，并且**留痕**（`只闭了 Nms`）。用户报「单闭不灵」
 *     时，这条日志直接给出他到底闭了多久，不用猜。
 *  3. **卡死保护**：单只眼一直读到阈值以下超过 [STUCK_MS] 视为读数卡住 / 用户闭着眼
 *     休息，清掉状态等睁眼重新开始。
 *
 * ## 与其它通道的关系
 *
 * 既不读、也不改 [BlinkDetector] 与 [HeadPoseDetector] 的任何状态；它只吃同一帧的
 * 左右眼睁开度。服务那边对它的调用在遮挡 / 静止硬锁定（`gate`）之外仍然照常喂数据，
 * 因为它是一条**控制指令**（和「张嘴点击」同类），不是翻页动作 —— 不占用全局冷却，
 * 也不会被冷却挡住。
 *
 * 本类不触发任何 Android API，纯逻辑，方便读也方便测。
 */
class WinkDetector(
    private val onWink: (WinkEvent) -> Unit,
) {

    companion object {
        private const val TAG = "Wink"

        /**
         * 单闭要保持多久才算一次有意单闭。
         *
         * 用户要求「单闭眼睛 1 秒以上才触发」，理由是「这个判定很明显，1 秒能降低误触」。
         * 1 秒 ≈ 9~16 帧（实测帧间隔 63~116ms），而实测「有意眨眼」单次闭眼只有
         * 139~758ms —— 两类之间留了 240ms 以上的余量，所以正常的连眨不会误调音量。
         */
        const val DEFAULT_HOLD_MS = 1000L

        /**
         * 短于这个时长的单闭不打日志。
         *
         * 只对「另一只眼睁着」的单眼闭合记日志，所以正常眨眼（两只眼都闭）根本走不到
         * 这里；能留下痕迹的都是真正的单眼事件，250ms 只是滤掉单帧抖动。
         */
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

    /** 高于此值算「明确睁着」。服务每帧同步成眨眼的"重新睁开"阈值。 */
    @Volatile
    var openAbove: Float = 0.70f

    /** 距离档（只用于日志）。 */
    @Volatile
    var nearTier: Boolean? = null

    /** 已触发的单闭次数（每次 = 一档音量）。 */
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

    /** 单只眼的闭合过程状态。 */
    private class Track {
        /** 本段单闭开始时刻；0 = 当前没有单闭。 */
        var closedSinceMs = 0L

        /** 本段是否已经触发过（一次单闭只调一档）。 */
        var fired = false

        /** 本段里闭着的那只眼的最深读数。 */
        var minReading = 1f

        /** 是否已经打过「够久但另一只眼没睁着」的日志（每段只打一次）。 */
        var reportedNotOpen = false
    }

    private val leftTrack = Track()
    private val rightTrack = Track()

    /** 诊断行用的紧凑状态。 */
    fun stateLine(): String =
        "L=${leftHeldMs}ms R=${rightHeldMs}ms thr=${"%.2f".format(closedBelow)} " +
            "fired=$triggerCount last=$lastWinkLabel"

    /**
     * 清掉进行中的单闭（遮挡 / 静止硬锁定 / 服务重绑 / 功能被关掉时调用）。
     *
     * 累计次数不重置：界面上「已调音量 N 档」是这次运行的累计值。
     */
    @Synchronized
    fun reset() {
        clear(leftTrack)
        clear(rightTrack)
        leftHeldMs = 0L
        rightHeldMs = 0L
    }

    private fun clear(track: Track) {
        track.closedSinceMs = 0L
        track.fired = false
        track.minReading = 1f
        track.reportedNotOpen = false
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
        val track = if (side == WinkSide.LEFT) leftTrack else rightTrack

        // 两只眼都闭着 = 眨眼 / 眯眼，不是单闭：整段作废，等睁眼后重新开始。
        // （所以「连眨 3 次翻页」永远不可能被这条通道当成单闭。）
        if (other < below) {
            clear(track)
            return
        }

        if (prob < below) {
            if (track.closedSinceMs == 0L) {
                track.closedSinceMs = nowMs
                track.fired = false
                track.minReading = prob
                track.reportedNotOpen = false
            }
            track.minReading = minOf(track.minReading, prob)

            val held = nowMs - track.closedSinceMs
            if (held > STUCK_MS) {
                // 读数卡住（镜片反光 / 检测退化）或用户闭着眼休息：清掉，等睁眼重来。
                clear(track)
                return
            }

            if (!track.fired && held >= holdMs) {
                if (other > open) {
                    track.fired = true
                    triggerCount++
                    lastWinkLabel = "${side.label} ${held}ms"
                    onWink(
                        WinkEvent(
                            side = side,
                            heldMs = held,
                            eyeL = prob,
                            eyeR = other,
                            minClosed = track.minReading,
                            otherEye = other,
                            closedBelow = below,
                            nearTier = nearTier,
                        ),
                    )
                } else if (!track.reportedNotOpen) {
                    // 够久了，但另一只眼没明确睁着 —— 宁可漏一次，也不把"眯眼"当成单闭。
                    // 这行日志是"用户说单闭不灵"时第一个要看的地方。
                    track.reportedNotOpen = true
                    Log.i(
                        TAG,
                        "wink ${side.label} 保持 ${held}ms 但另一只眼没睁着" +
                            "（other=${"%.2f".format(other)} 需要 > ${"%.2f".format(open)}）→ 不触发 " +
                            "dist=${if (nearTier == true) "near" else "mid/far"}",
                    )
                }
            }
        } else if (prob > open) {
            if (track.closedSinceMs != 0L) {
                val held = nowMs - track.closedSinceMs
                if (!track.fired && held >= LOG_MIN_MS) {
                    // 留痕：用户报「单闭不灵」时能直接看到他实际闭了多久。
                    Log.i(
                        TAG,
                        "wink ${side.label} 只闭了 ${held}ms（< ${holdMs}ms）→ 不触发 " +
                            "min=${"%.2f".format(track.minReading)} other=${"%.2f".format(other)} " +
                            "thr=${"%.2f".format(below)} " +
                            "dist=${if (nearTier == true) "near" else "mid/far"}",
                    )
                }
                clear(track)
            }
        }
        // 落在回差带（闭眼阈值 ≤ prob ≤ 睁眼阈值）：保持上一次状态，等一个明确的读数。
    }
}
