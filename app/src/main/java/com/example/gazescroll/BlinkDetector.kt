package com.example.gazescroll

import android.util.Log

/**
 * Blink detector — the primary swipe trigger.
 *
 * Pure logic, no Android APIs, so it stays readable and testable.
 *
 * ## Tuned for people wearing glasses
 *
 * ML Kit's eye-open probabilities read noticeably lower through lenses, and the
 * original 0.4 "closed" threshold made blinks hard to land. Three changes:
 *
 *  - **Higher threshold** ([closedBelow], default 0.55) with a hysteresis band up
 *    to [openAbove] (0.70).
 *  - **Single-eye trigger** — *either* eye dropping below the threshold counts as
 *    closed, so a lens reflection holding one eye high no longer hides a blink.
 *  - **Two-frame confirmation** ([requiredClosedFrames]) — a momentary dip cannot
 *    complete a blink on its own, which is what keeps the looser threshold from
 *    turning noise into phantom pages.
 *
 * A blink is the sequence 睁眼 → 闭眼 → 睁眼, i.e. the falling edge of the
 * "eyes closed" verdict.
 *
 * The user picks how many blinks in a row are needed (1, 2 or 3) — see
 * [requiredBlinks]. Every gap between consecutive blinks must fall inside
 * [blinkGapMinMs]..[blinkGapMaxMs]; a slower gap restarts the run. The run fires
 * as soon as it reaches [requiredBlinks], then the detector locks out for
 * [cooldownMs].
 */
class BlinkDetector(
    private val onTrigger: (reason: String) -> Unit,
) {

    companion object {
        private const val TAG = "Blink"

        /** Supported consecutive-blink counts. */
        val SUPPORTED_COUNTS = intArrayOf(1, 2, 3)

        /**
         * Nobody keeps their eyes shut for this long; if the "closed" verdict
         * persists past it, the reading is stuck (an occluded lens, a bad
         * detection) rather than a blink — clear it so blinks keep working.
         */
        private const val MAX_CLOSURE_MS = 3000L

        /** 近距判定：脸高占画面比例 ≥ 此值时收紧眨眼判定。 */
        private const val NEAR_FACE_RATIO = 0.55f

        /** 中距判定。 */
        private const val MID_FACE_RATIO = 0.38f

        /**
         * 近距离时要求的**连续闭眼帧数**（v5.11 引入，v5.12 撤销）。
         *
         * v5.11 曾把近距离要求提到 3 帧（v5.3 的设计值是 4），结果**用户的有意眨眼全被挡掉**：
         * 一整场会话里 `I/Blink:` **一行都没有**，用户原话「我眼睛眨烂了，只触发了一次」。
         * 原因是实测帧间隔 63~116ms（约 11fps）—— 加一帧就等于要求多闭眼约 90ms，
         * 而一次正常眨眼总共才 150~250ms。
         *
         * 所以 v5.12 起近距离**只降阈值、不加帧数**，帧数要求恒等于用户设定值。
         * 教训还是那一句：**凡是会连带挡住真实动作的手段，都不能用来防误触。**
         */
        private const val CLOSED_FRAMES_FOLLOW_USER = true
    }

    /** Below this, an eye counts as closed. Raised to 0.55 for glasses. */
    @Volatile
    var closedBelow: Float = 0.55f

    /** Above this, an eye counts as open again — the gap is the hysteresis band. */
    @Volatile
    var openAbove: Float = 0.70f

    /** Consecutive closed frames required before the closure is believed. */
    @Volatile
    var requiredClosedFrames: Int = 2

    /** Gap window between two blinks that still counts as "in a row". */
    @Volatile
    var blinkGapMinMs: Long = 200L

    @Volatile
    var blinkGapMaxMs: Long = 1500L

    /** How many consecutive blinks fire the swipe. User setting: 1 / 2 / 3. */
    @Volatile
    var requiredBlinks: Int = 2

    /**
     * 触发后的内部锁存，由服务每帧同步成用户设定的全局冷却时长。
     *
     * v4.3 起**面向用户的冷却由 [GlobalTriggerGate] 统一负责**，而且那个闸门是
     * 点头和眨眼共用的。这里保留一份，只是为了让检测器自己在冷却期内不再累加
     * `blinkCount`（否则设置页的「眨眼累计」会虚高）。
     */
    @Volatile
    var cooldownMs: Long = 1500L
    /** Completed blinks seen since the service started. */
    @Volatile
    var blinkCount: Int = 0
        private set

    /** Swipes this detector has fired. */
    @Volatile
    var triggerCount: Int = 0
        private set

    /** Blinks accumulated towards the current run; exposed for debugging. */
    @Volatile
    var pendingBlinks: Int = 0
        private set

    /** Confirmed-closed state (the hysteresis verdict). */
    private var eyesClosed = false

    /** Consecutive frames seen as closed. */
    private var closedFrames = 0

    /** When the current closure was confirmed, for the stuck-reading guard. */
    private var closedSinceMs = 0L

    /** 本次闭眼的第一帧时刻，用来量「闭眼持续了多久」（v5.11 诊断）。 */
    private var closureBeganAtMs = 0L

    /** 本次闭眼里左右眼各自的最深读数（v5.11 诊断）。 */
    private var closureMinLeft = 1f
    private var closureMinRight = 1f

    private var lastBlinkAtMs = 0L
    private var cooldownUntilMs = 0L

    /**
     * 人脸框高度占画面的比例（v5.3），由服务每帧同步。
     *
     * ML Kit 的 `eyeOpenProbability` 是**绝对**读数，不随脸的大小变化；但离得越近，
     * 眼睛在画面里的物理尺寸越大，睫毛、眼镜反光、轻微眯眼造成的遮挡就越明显，读数
     * 也就越容易跌破阈值。用户实测「30cm 静止时眨眼累计 86、已触发 63」——**近距离误触
     * 的主因就是它**（同一份数据里俯仰偏差为 0，排除了点头）。
     */
    @Volatile
    var faceRatio: Float? = null

    /** 当前生效的闭眼阈值（已按距离调整）；日志与界面显示用。 */
    @Volatile
    var effectiveClosedBelow: Float = 0.55f
        private set

    /** 当前生效的连续闭眼帧数要求（已按距离调整）。 */
    @Volatile
    var effectiveRequiredClosedFrames: Int = 2
        private set

    /**
     * 统一距离档的「是否近距离」（v5.11），由服务每帧从 [HeadPoseDetector] 同步。
     *
     * 为什么必须共用同一个真值：v5.3 的近距离收紧用的是本类自己的
     * `faceRatio >= 0.55`，而用户 30cm 实测只有 0.49~0.51 —— 于是收紧**从未生效**，
     * 一直跑在中距档（阈值 0.41 / 2 帧）。实测证据：静止 90 秒里的误触全部是
     * `lastTrigger=blink`，而诊断行显示 `blinkBelow=0.41 blinkFrames=2`。
     *
     * `null` 表示没有可用的距离档（例如头部两个轴都关了），此时退回本类自己的瞬时比值规则。
     */
    @Volatile
    var nearTier: Boolean? = null

    /**
     * 按距离收紧眨眼判定（v5.3 引入，v5.11 接到统一距离档上）。
     *
     * 近距离时同时做两件事：**降低**闭眼阈值（更难判成闭眼）并**提高**连续帧要求。
     * 只做其中一件不够：阈值降得太多会漏掉真实眨眼，而真实眨眼在近距离下持续帧数也更多
     * （眼睑扫过的画面距离更长），所以提高帧数要求对真实眨眼几乎无损，却能挡掉短促噪声。
     */
    private fun applyDistanceAdaptation() {
        val ratio = faceRatio
        // v5.11：近距离真值优先用**统一距离档**（带 EMA + 回差，由服务同步），
        // 这样「头部按近距离给灵敏度」与「眨眼按近距离收紧」用的是同一个真值，
        // 不会再出现 v5.9 那种「一端按近距离、另一端按中距算」的空档。
        val isNear = nearTier ?: (ratio != null && ratio >= NEAR_FACE_RATIO)
        val boost = when {
            isNear -> 2f
            ratio != null && ratio >= MID_FACE_RATIO -> 1.35f
            else -> 1f
        }
        // 阈值往「更难判成闭眼」的方向压，但要留足余量：真实眨眼时读数会掉到 0.2 以下，
        // 所以下限取 0.30 —— 再低就会漏掉真实眨眼，而"漏掉"比"误触"更让用户难受。
        // 默认 0.55：中距 → 0.407，近距 → 0.30。
        val below = closedBelow / boost
        effectiveClosedBelow = below.coerceIn(0.30f, closedBelow)
        // 帧数要求恒等于用户设定值（v5.12）—— 近距离**不再加帧数**，理由见
        // [CLOSED_FRAMES_FOLLOW_USER]：加帧数会把用户有意的眨眼一起挡掉。
        if (CLOSED_FRAMES_FOLLOW_USER) {
            effectiveRequiredClosedFrames = requiredClosedFrames
        } else {
            effectiveRequiredClosedFrames =
                (requiredClosedFrames * boost).toInt().coerceIn(requiredClosedFrames, 6)
        }
    }

    @Synchronized
    fun reset() {
        eyesClosed = false
        closedFrames = 0
        closedSinceMs = 0L
        closureBeganAtMs = 0L
        closureMinLeft = 1f
        closureMinRight = 1f
        lastBlinkAtMs = 0L
        pendingBlinks = 0
        cooldownUntilMs = 0L
    }

    fun inCooldown(nowMs: Long): Boolean = nowMs < cooldownUntilMs

    /**
     * Feed one frame's eye-open probabilities.
     *
     * @param left  `leftEyeOpenProbability`, or null when unavailable
     * @param right `rightEyeOpenProbability`, or null when unavailable
     */
    @Synchronized
    fun onEyeProbabilities(left: Float?, right: Float?, nowMs: Long) {
        // Need at least one eye; a frame with neither tells us nothing.
        if (left == null && right == null) {
            closedFrames = 0
            return
        }

        applyDistanceAdaptation()

        val closedNow = (left != null && left < effectiveClosedBelow) ||
            (right != null && right < effectiveClosedBelow)
        val openNow = (left != null && left > openAbove) ||
            (right != null && right > openAbove)

        if (closedNow) {
            if (closedFrames == 0) {
                // 新一次闭眼：开始记录这次的持续时间与最深读数（v5.11 诊断）。
                closureBeganAtMs = nowMs
                closureMinLeft = 1f
                closureMinRight = 1f
            }
            if (left != null) closureMinLeft = minOf(closureMinLeft, left)
            if (right != null) closureMinRight = minOf(closureMinRight, right)
            closedFrames++
            if (closedFrames >= effectiveRequiredClosedFrames && !eyesClosed) {
                eyesClosed = true
                closedSinceMs = nowMs
            }
        } else if (openNow) {
            val framesThisClosure = closedFrames
            val beganAt = closureBeganAtMs
            closedFrames = 0
            val completedBlink = eyesClosed
            eyesClosed = false
            closedSinceMs = 0L
            if (completedBlink) {
                registerBlink(nowMs, (nowMs - beganAt).coerceAtLeast(0L))
            } else if (framesThisClosure > 0) {
                // v5.12：**被拒掉的闭眼也留痕**。v5.11 那次把用户的眨眼挡掉了却什么都看不到，
                 // 只能靠用户描述"眼睛眨烂了"来发现问题。现在每一次抵达阈值的闭眼都有记录，
                 // 于是"该用户的有意眨眼到底多深、多久"是可直接量出来的数据。
                Log.i(
                    TAG,
                    "closure rejected: ${(nowMs - beganAt).coerceAtLeast(0L)}ms " +
                        "frames=$framesThisClosure/$effectiveRequiredClosedFrames " +
                        "minEye=${"%.2f".format(closureMinLeft)}/${"%.2f".format(closureMinRight)} " +
                        "below=${"%.2f".format(effectiveClosedBelow)} " +
                        "dist=${if (nearTier == true) "near" else "mid/far"}",
                )
            }
        }
        // Between the thresholds: keep the previous verdict (hysteresis).

        // Stuck-reading guard: release a closure that has clearly lasted too long.
        if (eyesClosed && nowMs - closedSinceMs > MAX_CLOSURE_MS) {
            eyesClosed = false
            closedFrames = 0
            closedSinceMs = 0L
        }
    }

    private fun registerBlink(nowMs: Long, closureMs: Long) {
        // Swallow everything during cooldown; the state above was still updated
        // so we do not fire the moment it expires.
        if (nowMs < cooldownUntilMs) return

        blinkCount++
        val previousBlinkAt = lastBlinkAtMs
        lastBlinkAtMs = nowMs
        val gapMs = if (previousBlinkAt == 0L) Long.MAX_VALUE else nowMs - previousBlinkAt

        // Consecutive run: a gap inside the window extends it, anything else restarts it.
        pendingBlinks = if (gapMs in blinkGapMinMs..blinkGapMaxMs) pendingBlinks + 1 else 1

        val needed = requiredBlinks.coerceIn(1, 3)

        // v5.11 诊断：每一次「被计数的眨眼」都留痕 —— 闭眼持续了多久、最深读到多少、
        // 与上一次眨眼的间隔、累计到几次。这是判断「有意眨眼 vs 不由自主的眨眼」
        // 唯一可靠的依据：前者深（读数掉到 0.2 以下）且久，后者短促。
        Log.i(
            TAG,
            "blink #$blinkCount closure=${closureMs}ms minEye=${"%.2f".format(closureMinLeft)}/" +
                "${"%.2f".format(closureMinRight)} below=${"%.2f".format(effectiveClosedBelow)} " +
                "needFrames=$effectiveRequiredClosedFrames " +
                "gap=${if (gapMs == Long.MAX_VALUE) "-" else "${gapMs}ms"} " +
                "run=$pendingBlinks/$needed dist=${if (nearTier == true) "near" else "mid/far"}",
        )
        if (pendingBlinks < needed) return

        triggerCount++
        pendingBlinks = 0
        cooldownUntilMs = nowMs + cooldownMs
        onTrigger(
            if (needed == 1) {
                "眨眼 1 次"
            } else {
                "连续眨眼 $needed 次（末次间隔 ${gapMs}ms）"
            },
        )
    }
}
