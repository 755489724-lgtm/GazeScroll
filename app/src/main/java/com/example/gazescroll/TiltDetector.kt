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
 * 歪头（roll）→ 音量 检测器 —— v5.35 新增，v5.36/v5.37 迭代，v5.38 回退到"灵敏优先"。
 *
 * ## 判据（v5.38）
 *
 *  1. **相对本人基准线的倾斜**：`tilt = roll − baseline`。baseline 是最近 [BASELINE_SAMPLES] 帧
 *     滚转角的中位数，所以**天生歪着头看手机的人不会被误触发**，也自动跟随慢速的姿势变化。
 *  2. **歪着的时候不更新基准线**：只有"头基本在中间"（`|tilt| ≤ 阈值`）时才把这一帧喂进窗口，
 *     否则一次故意歪头（停 1~2 秒）会把中位数带过去，回正时就被读成反方向的倾斜。
 *  3. **必须真的歪到角度**：`|tilt| ≥ thresholdDeg`（设置里可选 10 / **13（默认）** / 16 / 20°）。
 *  4. **并且保持 [holdMs]**（可选 0.2 / **0.3（默认）** / 0.5 / 0.8 秒）。
 *  5. **一次歪头只调一组档位**：触发后必须回到中位带并保持 [NEUTRAL_REARM_MS] 才允许下一次。
 *  6. **两秒动作间隔、没有提前量**（v5.36 用户要求）：起手那一帧必须晚于
 *     `上一次任何动作 + 2 秒`（[gapStartAfterMs] 由服务每帧同步），早了整段作废；
 *     作废的那一段也会**闩锁**（[firedThisEpisode]），免得回正/甩到另一边被当成一次新歪头
 *     调成反方向（用户原话"音量不降反增"）。
 *  7. 读数异常（>55°，躺下 / 侧脸野值）→ 丢弃基准线窗口重学。
 *
 * ## ⚠️ v5.37 的三条判据已被**删除**（实测把它们撤掉了 —— 记为教训）
 *
 * v5.37 为了修"回正误触"加了三条：① 连续歪着 >2.5 秒判定为姿势并**重锚**基准线（+自禁判 2 秒）；
 * ② 起手前 1.2 秒内必须出现过中位带；③ 起手前头必须停住过（≥250ms 静止）。实机结果（用户反馈
 * 「真不如上一版灵敏」+日志）：
 *
 * ```
 * 23:08:42 重锚到 -29.3°   23:08:50 重锚到 -14.2°   23:09:00 重锚到 37.0°
 * 23:09:03 重锚到 5.7°     23:09:06 重锚到 11.0°      ← 25 秒里重锚 5 次，每次自禁判 2 秒
 * 右歪头 起手太早（还剩 89ms 才满两秒）→ 这一段不算
 * 左歪头 起手之前没有中位（距上次中位 从未）→ 这一段不算
 * ```
 *
 * 重锚风暴把可用时间吃掉大半；而"起手前必须有中位"在基准线一动就被自己否掉。
 * 更糟的是它与服务侧"歪头期间暂停翻页"叠加：诊断行里 `|tilt| > 0.4×阈值` 的占比从
 * v5.36 的 **12%** 涨到 v5.37 的 **35%** —— 翻页通道三分之一的时间拿不到头部数据，
 * 用户说的「近距离俯视仰头被弄死了」就是这么来的。
 *
 * 所以 v5.38 **只保留不花灵敏度的两条**：`reset()` 不清基准线窗口（回正误触的真正根因）、
 * 作废即闩锁（防反向）；三条 v5.37 判据全部删除；"暂停翻页"的门槛回收成 `|tilt| ≥ 阈值`
 * （只在真的歪出阈值时才暂停，不再占 0.4 倍那条带）。
 *
 * 本类不触发任何 Android API（只打日志），纯逻辑，可用 tools/tilt-replay 离线回放。
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

        /** 回到中位后必须保持这么久，才允许下一次歪头。 */
        private const val NEUTRAL_REARM_MS = 400L

        /** 中位带 = 阈值 × 这个系数（回差，避免在阈值附近反复触发）。 */
        private const val NEUTRAL_FACTOR = 0.4f

        /** 触发所需时长的可选档位（毫秒）。 */
        val HOLD_OPTIONS = longArrayOf(200L, 300L, 500L, 800L)

        /** 触发角度的可选档位（度）。 */
        val THRESHOLD_OPTIONS = floatArrayOf(10f, 13f, 16f, 20f)

        /** 默认触发角度（度）。 */
        const val DEFAULT_THRESHOLD_DEG = 13f

        /** 默认保持时长（毫秒）。 */
        const val DEFAULT_HOLD_MS = 300L

        /** 超过这个倾斜幅度就认为读数是野值（躺下、侧脸），丢弃基准线窗口重学。 */
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
     * 两秒，而且**不能有提前量**）。实现方式是：**超过阈值的那一帧（起手）必须晚于这个时刻**，
     * 早了就整段作废、必须回到中位再重新歪。
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

    /** 给用户看的最近一条说明（设置页实时区显示用）。 */
    @Volatile
    var lastNotice: String = ""
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

    /** 本段是不是"作废"的（起手太早）→ 整段不触发。 */
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

    /** 分页门控用：当前是不是真的已经歪出阈值（v5.38 起用 1.0×阈值，不再用 0.4×）。 */
    fun isTilted(): Boolean {
        val t = tiltDeg ?: return false
        return kotlin.math.abs(t) >= thresholdDeg.coerceIn(4f, 45f)
    }

    /**
     * 清掉手势状态（遮挡 / 静止硬锁定 / 换应用 / 重绑 / 功能被关掉时调用）。
     *
     * **刻意不清基准线窗口**（v5.37 起）：基准线记录的是"这个人的头姿"，遮挡一下、切个应用
     * 不该把它丢掉 —— 丢掉它正是 v5.36 那次事故的元凶（重置后基准线在"用户正歪着头"的
     * 那几帧上重建，于是锚到了 32.6°，回正就被读成反方向歪头 → 用户报"音量不降反增"）。
     */
    @Synchronized
    fun reset() {
        beyondSinceMs = 0L
        beyondSide = 0
        firedThisEpisode = false
        episodePeakDeg = 0f
        episodeGapBlocked = false
        reportedGapBlock = false
        heldMs = 0L
        tiltDeg = null
        peakDeg = 0f
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
            episodeGapBlocked = false
            heldMs = 0L
            tiltDeg = null
            return
        }
        lastFrameAtMs = nowMs

        val thresholdNow = thresholdDeg.coerceIn(4f, 45f)
        // 只有"头基本在中间"时才更新基准线（窗口还没建起来时一律喂，先把基准线立起来）。
        val previousTilt = tiltDeg
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

        // 读数太歪（躺下 / 侧卧 / 脸转走导致的野值）：这一段不判定，并把基准线窗口整个丢掉重学。
        if (kotlin.math.abs(tilt) > TILT_SANITY_DEG) {
            ringIndex = 0
            ringCount = 0
            java.util.Arrays.fill(ring, 0f)
            beyondSinceMs = 0L
            beyondSide = 0
            firedThisEpisode = false
            episodeGapBlocked = false
            heldMs = 0L
            baselineDeg = null
            tiltDeg = null
            lastNotice = "滚转角读数异常（>${"%.0f".format(TILT_SANITY_DEG)}°），已重置重新学"
            Log.i(TAG, "滚转读数异常 ${"%.1f".format(rollDeg)}° → 丢弃基准线重新学")
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
            // 在中位带之外、阈值之内：不算"超过阈值"，本段结束（保持峰值）。
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
            // 作废的这一次也要"占住这一段"（闩锁），否则回正/甩到另一边的动作会被当成
            // 一次新的歪头、调成反方向 —— 用户的原话是「音量不降反增」。
            if (episodeGapBlocked) firedThisEpisode = true
            if (episodeGapBlocked && !reportedGapBlock) {
                reportedGapBlock = true
                val remain = gapStartAfterMs - nowMs
                val label = if (side < 0) TiltSide.LEFT.label else TiltSide.RIGHT.label
                lastNotice = "上一次动作后还差 ${remain}ms 满两秒：这次歪头已作废，回正后重新歪"
                Log.i(TAG, "$label 起手太早（还剩 ${remain}ms 才满两秒）→ 这一段不算，回正后重新歪")
            }
        }
        heldMs = nowMs - beyondSinceMs

        if (!firedThisEpisode && !episodeGapBlocked && heldMs >= holdMs) {
            firedThisEpisode = true
            triggerCount++
            val label = if (side < 0) TiltSide.LEFT.label else TiltSide.RIGHT.label
            lastTiltLabel = "$label ${"%.1f".format(kotlin.math.abs(tilt))}° ${heldMs}ms"
            lastNotice = ""
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
