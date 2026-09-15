package com.example.gazescroll

import android.util.Log
import java.util.Arrays
import kotlin.math.abs

/**
 * 头部动作检测器识别出的事件。
 *
 * 早期版本只有 [NodDown] / [TiltUp] 两种（直接当作“翻页”），v4.4 起改成事件流：
 * 上下俯仰和左右偏航都可能被识别，**由服务决定最终做什么**——包括「连续点头两次」
 * 这种需要跨动作累计的判断，检测器自己不该知道。
 */
sealed class HeadEvent {
    /** 相对基准线的角度（已按用户设置翻转符号）。 */
    abstract val degrees: Float

    /** 判定依据，直接进通知栏/日志，方便用户理解为什么触发了。 */
    abstract val reason: String

    /** 低头（下巴向下）—— 上一个视频。 */
    data class NodDown(override val degrees: Float, override val reason: String) : HeadEvent()

    /** 仰头（下巴向上）—— 下一个视频。 */
    data class TiltUp(override val degrees: Float, override val reason: String) : HeadEvent()

    /** 向左扭头 —— 向左滑。 */
    data class TurnLeft(override val degrees: Float, override val reason: String) : HeadEvent()

    /** 向右扭头 —— 向右滑。 */
    data class TurnRight(override val degrees: Float, override val reason: String) : HeadEvent()
}

/**
 * Head-pose detector: nod down / tilt up (pitch) **and** turn left / right (yaw).
 *
 * ## Pitch (`headEulerAngleX`)
 *
 * Per ML Kit's reference a **positive** euler X means the face is turned
 * **upward**, so relative to the baseline:
 *
 *   delta <= -threshold  ->  [HeadEvent.NodDown]
 *   delta >= +threshold  ->  [HeadEvent.TiltUp]
 *
 * ## Yaw (`headEulerAngleY`)
 *
 * This is a *front* camera, so the image is mirrored: turning your head to your
 * own **right** makes the detected face turn towards its left in image space.
 * The sign is therefore inverted here, so the events come out matching what the
 * user physically did. [invertYaw] flips it back for a device that disagrees.
 *
 * ## Baseline
 *
 * The resting angle depends entirely on how the phone is held, so it is never
 * hard-coded. Standstill baselines ([baselineDeg] for pitch, [baselineYawDeg] for
 * yaw) are the **median of a sliding window** of the last [WINDOW_SAMPLES]
 * readings. A median ignores a brief movement (a few frames out of ~3 s of
 * samples), so the baseline stays put while you act, yet still follows the slow
 * drift of holding the phone differently. Losing the face for a while clears the
 * windows so they re-learn on return.
 *
 * ## Recognising a deliberate movement rather than a slow lean
 *
 * Threshold alone is not enough — leaning back in a chair would cross it. So a
 * gesture must also be *quick*: the rise from half-threshold to full threshold has
 * to happen inside [motionWindowMs], and the peak must then be **held** for
 * [holdMs]. Falling back under the onset level at any point cancels it.
 *
 * Pitch and yaw are tracked in **separate** excursion state, so a nod and a turn
 * can never consume each other's timers: nodding while your head is slightly
 * turned still works, and vice versa.
 */
class HeadPoseDetector(
    private val onEvent: (HeadEvent) -> Unit,
) {

    companion object {
        private const val TAG = "HeadPose"

        /** Samples kept for the median baselines (~3 s at 15 fps). */
        private const val WINDOW_SAMPLES = 45

        /** Samples required before a cold baseline is trusted (~0.3 s). */
        private const val MIN_SAMPLES = 5

        /** No face for this long clears the baselines. */
        const val RECALIBRATE_AFTER_NO_FACE_MS = 3000L

        /** The excursion is considered to start at this fraction of the threshold. */
        private const val ONSET_FRACTION = 0.4f

        /**
         * 「明确是快速动作」的速度门限（度/毫秒）。
         *
         * 0.02°/ms 相当于 20°/秒。人做一次有意的点头/仰头大约 60~150°/秒，所以这个
         * 门限只拦得住真正的慢动作（慢慢靠椅背之类），不会误伤正常动作。
         */
        private const val FAST_PITCH_VELOCITY = 0.02f

        private const val FAST_YAW_VELOCITY = 0.03f

        /**
         * 允许触发所需的**最低**角速度（度/毫秒），无论「保持」了多久（v5.0）。
         *
         * 0.012°/ms ≈ 12°/秒。实测真实点头/仰头是 0.037~0.136°/ms，比它高出 3~11 倍，
         * 所以对正常动作没有任何影响；而静止噪声的角速度远低于它，因此被干净地挡掉。
         *
         * 这一条专门用来解决「一动不动也误触」——静止时唯一可能越过幅度阈值的东西就是
         * 检测噪声，而噪声没有速度。
         */
        private const val MIN_PITCH_VELOCITY = 0.012f

        private const val MIN_YAW_VELOCITY = 0.015f

        /** 速度估计的取样间隔上限；超过这么久没有新样本就不算「速度」。 */
        private const val VELOCITY_SAMPLE_MS = 200L

        /**
         * 回中锁定期间，判定「头部已经回到中性区」的阈值系数（v5.0）。
         *
         * 用动作阈值的一个比例（而不是绝对角度），这样用户把灵敏度调高调低时，
         * 「中性区」也跟着一起缩放。
         */
        private const val RECENTER_NEUTRAL_FRACTION = 0.4f

        /** 回到中性区后要稳定这么久才解除回中锁定。 */
        private const val RECENTER_SETTLE_MS = 150L

        /**
         * 姿势偏置自动校正（v5.2）。
         *
         * 实测数据暴露了 30cm 误触的真正原因：用户的自然姿势是「俯仰角 -2.85°、基准线
         * 3.69°」，偏差 6.5° —— **正好贴在 ±6° 阈值上**。此时静止噪声只要轻轻一推就越界，
         * 于是「一动不动也会误触」，而 50cm 时基准线跟得上、偏差小，就正常。
         *
         * 根因是：只要姿势**缓慢**变化（靠回椅背、把手机压低一点、凑近屏幕），滑动窗口中位数
         * 基准线就跟不上，偏差被永久保留下来，白白吃掉阈值余量。
         *
         * 判据很干净：一次有意动作在 [MOTION_WINDOW_MS] 内就结束了，**偏差持续超过
         * [BIAS_RECENTER_AFTER_MS] 且始终没有超过阈值**，那它不是动作，是新姿势。
         */
        private const val BIAS_RECENTER_AFTER_MS = 1500L

        /** 校正速率：每帧把基准线往当前姿势挪这么多比例，避免画面跳变。 */
        private const val BIAS_RECENTER_ALPHA = 0.06f

        /**
         * 俯视姿态的门限与持续时间（v5.4）。
         *
         * 判定的是**相对基准线**偏负多少度：3° 足以把"俯视"和"静止噪声"分开，又小到
         * 不会把正常的略微低头当成姿势变化。持续 2 秒是用户明确要求的时长。
         */
        private const val DOWN_GAZE_MIN_DEG = 3f
        private const val DOWN_GAZE_AFTER_MS = 2000L

        /**
         * 俯视增益的生效窗口与强度（v5.5）。
         *
         * 用户反馈「俯视看手机时再做点头动作很别扭，希望更轻松」。俯视时头部本来就低着，
         * 再往下点的**可用行程**比平视时短，所以同样幅度的动作只能产生更小的角度变化。
         *
         * 这里把点头阈值压到 [DOWN_GAZE_NOD_BOOST]（0.75 = 六折多一点的力度就能触发）。
         * 只压**幅度**阈值，[effectivePitchSpeedGate] 与静止锁定一律不动 —— 也就是说
         * "动作要快"这条门槛没放松，噪声依然过不来（噪声有幅度但没有速度）。
         *
         * 离开俯视后 6 秒内仍算生效（避免刷视频时头一抬一低就反复切换灵敏度），
         * 超时自动恢复 1.0。
         */
        private const val DOWN_GAZE_ACTIVE_WINDOW_MS = 6000L
        private const val DOWN_GAZE_NOD_BOOST = 0.75f

        /** 俯视增益的上下限，防止配置异常把它压得太低。 */
        private const val MIN_DOWN_GAZE_NOD_BOOST = 0.55f

        // ----------------------------- v5.6：绝对几何的距离 / 姿态判定 --

        /** 近距离判定：脸高占画面比例 ≥ 此值（实测 30cm 约 0.6，40cm 约 0.45）。 */
        private const val NEAR_DISTANCE_RATIO = 0.50f

        /**
         * 俯视判定的几何门限 —— **当前未启用**（v5.6）。
         *
         * 实测标定发现 `|下巴Y − 眼中心Y| / 脸高` 主要在反映距离而非姿态
         * （远距 0.335~0.368、近距 0.368~0.432），区分度不足以驱动灵敏度开关，
         * 因此点头增益改由**距离**驱动（见 [nearDownNodBoost]）。
         *
         * 保留这个常量与 [chinRatio] 诊断，是为了在拿到更干净的分组数据后可以重新启用；
         * 现在把它设成一个实测中不会达到的值，确保它**不会**意外生效。
         */
        private const val DOWN_POSTURE_CHIN_RATIO = 0.68f

        /** 俯视判定的回差：低于（门限 − 回差）才认为回到平视。 */
        private const val DOWN_POSTURE_HYSTERESIS = 0.04f

        /** 姿态平滑系数：越小越稳、越大越跟手。 */
        private const val POSTURE_EMA_ALPHA = 0.25f

        /**
         * 近距离俯视时的点头增益（v5.6）。
         *
         * 用户反馈「近距离俯视刷抖音时脖子很累」。此时点头的可用行程最短，
         * 所以给的增益比远距俯视更激进（0.68 对 0.75）。
         * 只压**幅度**阈值：速度门限、静止锁定、近距离静止硬锁定一律不动，
         * 所以"噪声有幅度没有速度"这道关卡照旧生效。
         */
        private const val NEAR_DOWN_NOD_BOOST = 0.68f

        /** chinRatio 滑动中位数的窗口（约 0.6 秒 @15fps，只用于标定显示）。 */
        private const val CHIN_WINDOW_SAMPLES = 9

        /**
         * 回中锁定的**最长**持续时间（v5.2）。
         *
         * 没有这条兜底时，锁会永久卡住：人的自然姿势长期偏离基准线（实测偏差 6.5°），
         * 永远进不了 ±2.4° 的中性区，于是反向动作被无限期屏蔽 —— 表现就是「仰头能用、
         * 点头全失效」。回正动作本身只要几百毫秒，400ms 足够覆盖它。
         */
        private const val RECENTER_MAX_LOCK_MS = 400L

        /** 静止判定的观察窗口。 */
        private const val STATIC_WINDOW_MS = 500L

        /** 窗口内俯仰/偏航峰峰值都小于这个度数，就认为「没在动」。 */
        private const val STATIC_RANGE_DEG = 1.5f

        /**
         * 贴近距（脸占画面比例）时静止门限的放大倍数（v4.9）。
         *
         * 30cm 时脸约画面高的 0.55~0.7，此时同样的头部微晃在图像上的位移明显更大，
         * ML Kit 的角度读数也随之更抖。放大 2 倍把这段噪声压回门限之下。
         */
        private const val STATIC_RANGE_NEAR_FACTOR = 2f

        /** 中距时静止门限的放大倍数。 */
        private const val STATIC_RANGE_MID_FACTOR = 1.35f

        /** 近距判定：脸高占画面 ≥ 此值。 */
        private const val NEAR_FACE_RATIO = 0.55f

        /** 中距判定：脸高占画面 ≥ 此值。 */
        private const val MID_FACE_RATIO = 0.38f

        /** 人脸消失这么久（哪怕只是被手挡一下）就作废基准线，回来重新学。 */
        const val BASELINE_INVALID_AFTER_NO_FACE_MS = 150L

        /** 静止判定滑窗的样本数（约 0.5 秒 @15fps 的读数，按时间再筛）。 */
        private const val MOTION_WINDOW_SAMPLES = 8

        /** Log excursions past this many degrees, to make the sign checkable. */
        private const val DIAGNOSTIC_LOG_DEG = 4f
    }

    // ---------------------------------------------------------------- 点头仰头 --

    /** Degrees from the baseline that count as a nod / tilt. User setting. */
    @Volatile
    var thresholdDeg: Float = 8f

    /** The baseline→peak rise must finish inside this window. */
    @Volatile
    var motionWindowMs: Long = 500L

    /** The peak must then be held this long. */
    @Volatile
    var holdMs: Long = 200L

    // ---------------------------------------------------------------- 左右扭头 --

    /** 启用左右扭头滑动。 */
    @Volatile
    var turnEnabled: Boolean = false

    /** 偏航角相对基准线多少度算「扭头了」。用户设置。 */
    @Volatile
    var turnThresholdDeg: Float = 20f

    /** 扭头也必须「快」，否则慢慢偏头也会被当成动作。 */
    @Volatile
    var turnMotionWindowMs: Long = 600L

    /** 扭头到位后要保持的时间（比点头略短，扭头更容易稳住）。 */
    @Volatile
    var turnHoldMs: Long = 150L

    /** 设备前后摄像头方向特殊时，用来反转左右。 */
    @Volatile
    var invertYaw: Boolean = false

    /**
     * 静止锁定放大系数（v4.8）。
     *
     * 连续 [STATIC_WINDOW_MS] 内俯仰/偏航的峰峰值都小于 [STATIC_RANGE_DEG]，说明用户
     * 是静止的。
     *
     * ## v5.5：它现在放大的是**速度门限**，不再是幅度阈值
     *
     * v5.2~v5.4 把 [staticLockFactor] 乘在**幅度阈值**上，于是静止时点头要 9°~12° 而不是
     * 6°，用户反馈「点头很费劲」。但放大幅度是错的工具：它不区分"这是噪声还是动作"，
     * 只会把所有真实动作一起挡掉。
     *
     * 正确的工具是速度：噪声**有幅度但没有速度**。所以现在静止锁定同时放大
     *  - [STATIC_RANGE_DEG]（静止判定的峰峰值门限，本来就该随距离放大），
     *  - **最低速度门限**（[effectivePitchSpeedGate] / [effectiveYawSpeedGate]）。
     *
     * 真的一动，锁定立刻解除、速度门限回到基准值，所以**动作幅度手感完全不变**，
     * 只是静止时那点抖动过不了速度这一关。
     */
    @Volatile
    var staticLockEnabled: Boolean = true

    /** 静止时速度门限的放大倍数（不再是幅度阈值的倍数，见上）。 */
    @Volatile
    var staticLockFactor: Float = 1.8f

    /** 当前是否处于静止锁定；界面显示用。 */
    @Volatile
    var staticLocked: Boolean = false
        private set

    /**
     * 回中锁定（v5.0）：上一次动作的方向。
     *
     * 用户实测反馈：**仰头触发上滑之后，把头放回正常位置的过程被判定成点头**，于是又触发
     * 一次下滑。原因很直白——仰头时俯仰角为正，回正时会从正经过 0 继续变成负，而判定逻辑
     * 只看「相对基准线的偏移量」，回正过程因此天然长得像一次反向动作。
     *
     * 现在的规则：一次动作触发后，**反方向**在「头部回到中性区并稳定下来」之前一律不接受。
     * 同方向仍然可以再次触发（连着点两下头是合理操作），所以手感不受影响。
     *
     * +1 表示上次是仰头（锁定低头），-1 表示上次是低头（锁定仰头），0 表示没有锁定。
     */
    @Volatile
    var recenterLockDirection: Int = 0
        private set

    /** 是否处于回中锁定；界面显示用。 */
    @Volatile
    var recenterLocked: Boolean = false
        private set

    /**
     * 人脸框高度占画面高度的比例（v4.9）。**这就是「离手机多远」的度量。**
     *
     * 由服务每帧同步。近距离时同样的微小晃动在画面里折算出的角度更大，所以静止门限要
     * 据此抬高——这正是用户反馈「50cm 不误触、30cm 一动不动也误触」的原因。
     */
    @Volatile
    var faceRatio: Float? = null

    /** 当前生效的静止峰峰值门限（已按距离调整）；界面与日志显示用。 */
    @Volatile
    var staticRangeDeg: Float = STATIC_RANGE_DEG
        private set

    /**
     * 当前生效的俯仰 / 偏航动作阈值（v5.5），日志显示用。
     *
     * 专门暴露出来是为了**一眼验证解耦生效**：
     * 近距离时 `pitchThresholdDeg` 应保持不变（不再随距离放大），而 `yawThresholdDeg` 会翻倍。
     */
    @Volatile
    var pitchThresholdDeg: Float = 8f
        private set

    @Volatile
    var yawThresholdDeg: Float = 20f
        private set

    /** 当前生效的俯仰最低速度门限（°/ms），日志显示用（v5.6）。 */
    @Volatile
    var pitchSpeedGate: Float = 0.012f
        private set

    /**
     * 单次动作后的内部锁存，由服务每帧同步成用户设定的全局冷却时长。
     *
     * v4.3 起**面向用户的冷却改由 [GlobalTriggerGate] 统一管理**（且与眨眼共用同一个
     * 计时器）。这里保留一份，只负责丢掉同一次动作残留的那几帧，避免触发计数被刷高。
     */
    @Volatile
    var cooldownMs: Long = 2000L

    @Volatile
    var invertPitch: Boolean = false

    @Volatile
    var calibrated: Boolean = false
        private set

    @Volatile
    var baselineDeg: Float = 0f
        private set

    /** 偏航基准线；扭头判定用的就是它。 */
    @Volatile
    var baselineYawDeg: Float = 0f
        private set

    /** Latest raw pitch, for the settings readout / logs. */
    @Volatile
    var lastAngleDeg: Float? = null
        private set

    /** Latest raw yaw, for the settings readout / logs. */
    @Volatile
    var lastYawDeg: Float? = null
        private set

    @Volatile
    var triggerCount: Int = 0
        private set

    /** 累计识别到的扭头次数（左右都算），用于设置页显示。 */
    @Volatile
    var turnCount: Int = 0
        private set

    private val pitchWindow = FloatArray(WINDOW_SAMPLES)
    private val yawWindow = FloatArray(WINDOW_SAMPLES)
    private val scratch = FloatArray(WINDOW_SAMPLES)
    private var pitchIndex = 0
    private var yawIndex = 0
    private var pitchCount = 0
    private var yawCount = 0

    // ---- 俯仰（点头/仰头）的进行中状态 ----
    private var pitchOnsetAtMs = 0L
    private var pitchReachedAtMs = 0L
    private var pitchArmed = false

    // ---- 偏航（左扭头/右扭头）的进行中状态，与俯仰完全独立 ----
    private var yawOnsetAtMs = 0L
    private var yawReachedAtMs = 0L
    private var yawArmed = false
    private var yawSign = 0

    private var cooldownUntilMs = 0L
    private var lastFaceAtMs = 0L
    private var lastLoggedPitch = 0f
    private var lastLoggedYaw = 0f

    // ---- 静止锁定的滑窗（记录最近一段时间的俯仰/偏航，只看峰峰值） ----
    private val motionPitch = FloatArray(MOTION_WINDOW_SAMPLES)
    private val motionYaw = FloatArray(MOTION_WINDOW_SAMPLES)
    private var motionIndex = 0
    private var motionCount = 0

    /** 人脸消失的起始时刻，0 表示脸还在。 */
    private var faceMissingSinceMs = 0L

    /** 这次的「看不到脸」是否已经久到足以作废基准线。 */
    private var faceLostLongEnough = false

    /** 头部回到中性区的起始时刻，用于回中锁定的解除计时。 */
    private var recenterNeutralSinceMs = 0L

    /** 回中锁定开始的时刻，用于 [RECENTER_MAX_LOCK_MS] 兜底。 */
    private var recenterLockedAtMs = 0L

    /** 姿势偏离持续到这一刻仍没变成动作，就认定是新姿势并校正基准线。 */
    private var biasSinceMs = 0L

    /** 最近一次判定为俯视的时刻，用于俯视增益的自动失效（v5.5）。 */
    private var lastDownGazeAtMs = 0L

    // ---- v5.6：chinRatio 的滑动窗口，用于中位数标定 ----
    private val chinWindow = FloatArray(CHIN_WINDOW_SAMPLES)
    private var chinIndex = 0
    private var chinCount = 0

    /** 累计执行过多少次姿势偏置校正，仅用于诊断。 */
    @Volatile
    var biasRecenterCount: Int = 0
        private set

    /** 累计识别到多少次俯视姿态并完成补偿，仅用于诊断（v5.4）。 */
    @Volatile
    var downGazeCount: Int = 0
        private set

    // ------------------------------------------- v5.6：绝对几何的距离与姿态判断 --

    /**
     * 俯视几何比例（`|下巴Y − 眼中心Y| / 脸高`），由服务每帧同步。
     *
     * 与「相对基准线的俯仰偏移」不同，这是**绝对几何**：用户一直俯视时基准线会自适应
     * 过去、相对偏移趋近 0，但绝对几何不会。所以它才是判断"相机在俯拍还是平拍"的可靠依据。
     */
    @Volatile
    var chinRatio: Float? = null

    /** 姿态判定的平滑值（EMA），避免单帧抖动导致灵敏度反复切换。 */
    @Volatile
    var smoothedChinRatio: Float? = null
        private set

    /** 当前是否判定为俯视姿态（基于绝对几何）。 */
    @Volatile
    var lookingDown: Boolean = false
        private set

    /** 当前是否近距离。 */
    @Volatile
    var nearDistance: Boolean = false
        private set

    /**
     * 最近一次 `pitchThresholdNow()` 实际乘上去的距离增益（v5.6）。
     *
     * 与 [nearDistance] 的区别在于"实际用了没有"：这个值只在阈值真的算过之后才更新，
     * 所以它和诊断行里的 `pitchTh` 永远自洽。排查时以它为准。
     */
    @Volatile
    var lastAppliedNodBoost: Float = 1f
        private set

    /**
     * 仰头（tilt up）方向当前实际生效的俯仰阈值（v5.7）。
     *
     * 近距离点头增益是**单向**的，所以低头和抬头的阈值会不一样；诊断行把两个都打出来，
     * 用户看到 `pitchTh=5.4° pitchThUp=8.0°` 就知道"点头更灵、仰头照旧"。
     */
    @Volatile
    var pitchThresholdUpDeg: Float = 0f
        private set

    /** 当前姿态标签，仅用于日志/界面。 */
    @Volatile
    var postureLabel: String = "未知"
        private set

    /** 累计"近距离俯视"增益生效的次数，仅用于诊断。 */
    @Volatile
    var nearDownBoostCount: Int = 0
        private set

    /**
     * 最近一段时间的 `chinRatio` 中位数（v5.6）。
     *
     * 用来**离线标定俯视门限**：中位数反映用户"最常出现的姿态"，
     * 把 `DOWN_POSTURE_CHIN_RATIO` 设在它稍上方就能区分"平常"与"俯视"。
     * 日志里同时打印它和原始值，不需要用户报数也能判断门限是否合理。
     */
    @Volatile
    var chinRatioMedian: Float? = null
        private set

    /**
     * 当前是否处于俯视姿态（v5.5）。以 [DOWN_GAZE_ACTIVE_WINDOW_MS] 内出现过俯视判定为准，
     * 离开该窗口后自动失效，所以"回到平视"时灵敏度会自己恢复。
     */
    @Volatile
    var downGazeActive: Boolean = false
        private set

    /** 俯视时对点头阈值的乘数（<1 表示更容易触发）。 */
    @Volatile
    var nodDownGazeBoost: Float = 1f
        private set

    // ---- 速度估计：用最近两个样本的差值判断「这是不是一次快速动作」 ----
    private var prevSignedPitch = 0f
    private var prevSignedPitchAtMs = 0L
    private var prevSignedYaw = 0f
    private var prevSignedYawAtMs = 0L

    /** 本帧算出的符号化读数，供下一帧估速度用。 */
    private var lastSignedPitch = 0f
    private var lastSignedYaw = 0f

    @Synchronized
    fun reset() {
        pitchCount = 0
        yawCount = 0
        pitchIndex = 0
        yawIndex = 0
        calibrated = false
        baselineDeg = 0f
        baselineYawDeg = 0f
        clearExcursion()
        clearTurn()
        cooldownUntilMs = 0L
        lastFaceAtMs = 0L
        lastAngleDeg = null
        lastYawDeg = null
        // 俯视增益一并清掉，避免"重置后仍带着一段时间的低阈值"。
        biasSinceMs = 0L
        lastDownGazeAtMs = 0L
        downGazeActive = false
        nodDownGazeBoost = 1f
        // v5.6：姿态/距离判定也清零，重新学习。
        smoothedChinRatio = null
        lookingDown = false
        nearDistance = false
        postureLabel = "未知"
    }

    /** Throw away the learned baselines; the next samples establish new ones. */
    @Synchronized
    fun recalibrate() {
        pitchCount = 0
        yawCount = 0
        pitchIndex = 0
        yawIndex = 0
        calibrated = false
        clearExcursion()
        clearTurn()
        Log.i(TAG, "baseline cleared, re-learning")
    }

    /**
     * Start from a known baseline instead of re-learning one.
     *
     * The service seeds this from the previous session when you re-enter a target
     * app, so nodding works immediately rather than after the window fills. The
     * ring buffers are pre-filled with the seed, so the medians keep tracking it
     * until real samples replace them.
     */
    @Synchronized
    fun seedBaseline(pitchDegrees: Float, yawDegrees: Float = 0f) {
        pitchWindow.fill(pitchDegrees)
        yawWindow.fill(yawDegrees)
        pitchCount = WINDOW_SAMPLES
        yawCount = WINDOW_SAMPLES
        pitchIndex = 0
        yawIndex = 0
        baselineDeg = pitchDegrees
        baselineYawDeg = yawDegrees
        calibrated = true
        clearExcursion()
        clearTurn()
        Log.i(
            TAG,
            "baseline seeded with pitch ${"%.1f".format(pitchDegrees)}° / yaw ${"%.1f".format(yawDegrees)}°",
        )
    }

    private fun clearExcursion() {
        pitchOnsetAtMs = 0L
        pitchReachedAtMs = 0L
        pitchArmed = false
        lastLoggedPitch = 0f
    }

    private fun clearTurn() {
        yawOnsetAtMs = 0L
        yawReachedAtMs = 0L
        yawArmed = false
        yawSign = 0
        lastLoggedYaw = 0f
    }

    /**
     * Feed one frame.
     *
     * @param pitchDeg `headEulerAngleX`, or null when no face was detected
     * @param yawDeg   `headEulerAngleY`, or null when no face was detected
     */
    @Synchronized
    fun onHeadPose(pitchDeg: Float?, yawDeg: Float?, nowMs: Long) {
        if (pitchDeg == null && yawDeg == null) {
            // 记录「从哪一刻开始看不到脸」。哪怕只是被手挡一下，也足以让基准线失效：
            // 手上移挡脸时 median 窗口里会混进遮挡前/后的两段完全不同的读数，手拿开后
            // 立刻算出一个巨大的 delta，从而误触发上滑/下滑/左滑——这正是用户反馈的
            // 「手在脸旁边再拿开就乱滑」。所以不等到 3 秒，只给一帧的容错。
            if (faceMissingSinceMs == 0L) faceMissingSinceMs = nowMs
            if (lastFaceAtMs != 0L &&
                nowMs - lastFaceAtMs > BASELINE_INVALID_AFTER_NO_FACE_MS
            ) {
                lastFaceAtMs = 0L
                faceLostLongEnough = true
            }
            if (nowMs - faceMissingSinceMs > RECALIBRATE_AFTER_NO_FACE_MS) {
                recalibrate()
            }
            return
        }

        val justReturned = faceMissingSinceMs != 0L
        faceMissingSinceMs = 0L
        lastFaceAtMs = nowMs
        lastAngleDeg = pitchDeg
        lastYawDeg = yawDeg

        if (justReturned && faceLostLongEnough) {
            // 脸回来了：整条基准线重新学，避免用被污染的窗口做判定。
            faceLostLongEnough = false
            Log.i(TAG, "face lost briefly — baseline invalidated, re-learning")
            pitchCount = 0
            yawCount = 0
            pitchIndex = 0
            yawIndex = 0
            calibrated = false
            motionCount = 0
            clearExcursion()
            clearTurn()
        }

        if (pitchDeg != null) pushPitch(pitchDeg)
        if (yawDeg != null) pushYaw(yawDeg)

        val pitchReady = pitchDeg != null && pitchCount >= MIN_SAMPLES
        val yawReady = yawDeg != null && yawCount >= MIN_SAMPLES
        if (!pitchReady && !yawReady) return

        if (pitchReady) baselineDeg = median(pitchWindow, pitchCount)
        if (yawReady) baselineYawDeg = median(yawWindow, yawCount)
        calibrated = true

        // 静止锁定要在冷却之前更新：冷却期内也要继续观察「用户到底有没有在动」，
        // 否则冷却一结束，锁定状态会是陈旧的。
        updateStaticLock(pitchDeg, yawDeg)

        // 冷却期内所有头部动作都不再判定；进行中的动作全部丢弃。
        if (nowMs < cooldownUntilMs) {
            clearExcursion()
            clearTurn()
            return
        }

        // 距离系数每帧只算一次，仅供偏航（扭头）使用；俯仰自 v5.5 起与距离解耦。
        val boost = distanceBoost()
        // v5.6：先更新距离/姿态判定，再让 v5.4 的时间型俯视增益失效，最后才评估动作。
        updateDistanceAndPosture(nowMs)
        refreshDownGazeBoost(nowMs)
        if (pitchReady) evaluatePitch(pitchDeg!!, nowMs, boost)
        if (turnEnabled && yawReady) evaluateYaw(yawDeg!!, nowMs, boost)
    }

    /**
     * 静止锁定：窗口内俯仰/偏航的峰峰值都很小 → 认定用户在静止，把阈值抬高。
     *
     * 真实动作一出现（峰峰值超过静止门限）立刻解锁，所以**不影响正常动作的响应速度**，
     * 只压制静止时由检测噪声引起的误触发。
     *
     * v4.9：静止门限本身按**离手机的远近**放大。近距离时脸在画面里占得大，同样的微小
     * 晃动折算出的角度更大，用固定的 1.5° 根本挡不住——这就是「50cm 不误触、30cm 一动
     * 不动也误触」的成因。
     */
    private fun updateStaticLock(pitchDeg: Float?, yawDeg: Float?) {
        motionPitch[motionIndex] = pitchDeg ?: (baselineDeg + motionPitch[motionIndex])
        motionYaw[motionIndex] = yawDeg ?: (baselineYawDeg + motionYaw[motionIndex])
        motionIndex = (motionIndex + 1) % MOTION_WINDOW_SAMPLES
        if (motionCount < MOTION_WINDOW_SAMPLES) {
            motionCount++
            staticLocked = false
            return
        }
        staticRangeDeg = effectiveStaticRange()
        val pitchRange = peakToPeak(motionPitch, motionCount)
        val yawRange = peakToPeak(motionYaw, motionCount)
        staticLocked = staticLockEnabled &&
            pitchRange < staticRangeDeg &&
            yawRange < staticRangeDeg
    }

    private fun peakToPeak(values: FloatArray, count: Int): Float {
        if (count <= 1) return 0f
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (i in 0 until count) {
            val v = values[i]
            if (v < min) min = v
            if (v > max) max = v
        }
        return max - min
    }

    /**
     * 距离放大系数：脸离得越近，同样的头部微晃在画面里折算出的角度越大（v5.2 起）。
     *
     * 实测数据：50cm 时 `faceRatio≈0.35` 一切正常；30cm 时 `faceRatio≈0.68`，静止也会误触。
     * 所以近距离下静止门限与速度门限都要同步提高——**只提高一个是不够的**：抬高幅度门限
     * 挡不住偶尔偏大的噪声样本，抬高速度门限才能把它们区分开（噪声没有速度）。
     */
    private fun distanceBoost(): Float {
        val ratio = faceRatio ?: return 1f
        return when {
            ratio >= NEAR_FACE_RATIO -> 2f
            ratio >= MID_FACE_RATIO -> 1.35f
            else -> 1f
        }
    }

    /**
     * **偏航**（扭头）阈值：只乘距离系数。
     *
     * v5.5 起不再乘静止锁定 —— 见 [pitchThresholdNow] 的说明。防静止误触交给速度门限，
     * 幅度阈值保持用户设定值（"中 20°" 就真是 20°）。
     */
    private fun turnThresholdNow(distanceBoost: Float): Float =
        turnThresholdDeg * distanceBoost

    /**
     * 俯仰（点头/仰头）的动作阈值（v5.5 起既与距离解耦、也与静止锁定解耦）。
     *
     * ## 为什么把这两层放大都去掉
     *
     * v5.4 的公式是 `基础 × 静止锁定系数 × 距离系数`，两个系数叠在一起，
     * 用户实测的两条抱怨正好指向它：
     *
     *  - **近距离点头费劲**：30cm 时距离系数 ×2，阈值 6° → 12°，要走两倍幅度；
     *  - **静止时点头也费劲**：静止锁定 ×1.5，阈值又变成 9°~12°。
     *
     * 关键认识是：**抑制静止噪声根本不需要放大幅度阈值**。噪声**有幅度但没有速度**，
     * 而[最低速度门限][effectivePitchSpeedGate]已经在拦它了；[静止锁定]同时还在把
     * 速度门限和静止峰峰值门限一起放大。幅度阈值放大只会连带把真实动作也挡掉——
     * 它不区分"这是噪声还是动作"。
     *
     * 所以现在俯仰幅度阈值 = `用户设定值 × 俯视增益`，干净、可预期：
     * 用户把灵敏度设成 6°，那就是 6°（俯视时 4.5°）。
     *
     * 误触防线一道没少：静止峰峰值门限、最低速度门限、近距离静止硬锁定、
     * 遮挡抑制、回中锁定全部保留，且仍然按距离缩放。
     */
    private fun pitchThresholdNow(signedPitch: Float): Float {
        val boost = nearNodDownBoost(signedPitch)
        // v5.6：把"这一帧实际生效的距离增益"记下来给诊断行用。
        // 之前诊断打的是 nodDownGazeBoost（俯视增益常量），近距离下会显示 1.00，
        // 而阈值其实已经乘过 0.68 —— 字段名与实际不符，排查时会误判功能没生效。
        lastAppliedNodBoost = boost
        return thresholdDeg * (nodDownGazeBoost * boost)
    }

    /** 按距离缩放后的静止峰峰值门限。 */
    private fun effectiveStaticRange(): Float = STATIC_RANGE_DEG * distanceBoost()

    /**
     * 按距离缩放后的最低速度门限（v5.2 引入，v5.5 叠加静止锁定）。
     *
     * 50cm 下是 0.012°/ms（实测真实动作 0.037~0.136°/ms，有三倍以上余量）；
     * 30cm 下提到 0.024°/ms，仍然远低于真实动作，但把静止时的角度噪声挡在外面。
     *
     * v5.5：静止锁定时再乘 [staticLockFactor] —— 这是"静止防误触"现在的**主要手段**，
     * 取代了 v5.4 的"放大幅度阈值"。真实动作的速度是噪声的十几倍，所以这一条对
     * 正常点头/仰头几乎没有成本，却能干净地把噪声挡掉。
     */
    private fun effectivePitchSpeedGate(): Float =
        MIN_PITCH_VELOCITY * distanceBoost() * (if (staticLocked) staticLockFactor else 1f)

    /** 按距离缩放后的偏航速度门限（同样叠加静止锁定）。 */
    private fun effectiveYawSpeedGate(): Float =
        MIN_YAW_VELOCITY * distanceBoost() * (if (staticLocked) staticLockFactor else 1f)

    /**
     * 判定「峰值是否保持住了」所需的时间（v4.8）。
     *
     * 以前固定用 [holdMs]（200ms），而「动作要足够快」的上限是 [motionWindowMs]（500ms）——
     * 于是做出动作只要 200ms，**松开 / 保持**却要额外再等 200ms，总延迟 400ms 起步。
     * 用户的原话是「点头几十毫秒就能触发，仰头要等一倍的时间」。
     *
     * 现在改成两者取较小值：保持要求永远不会比动作本身更严格。默认配置下
     * `min(200, 500) = 200`，行为与原来一致；但用户把 holdMs 调大、motionWindowMs 调小时，
     * 也不会再出现「动作做完了还在干等」的情况。
     */
    private fun requiredHoldMs(): Long = minOf(holdMs, motionWindowMs)

    private fun requiredTurnHoldMs(): Long = minOf(turnHoldMs, turnMotionWindowMs)

    // ------------------------------------------------------------- 俯仰：点头 --

    private fun evaluatePitch(pitchDeg: Float, nowMs: Long, distanceBoost: Float) {
        val delta = pitchDeg - baselineDeg
        val signedPitch = if (invertPitch) -delta else delta
        val magnitude = abs(signedPitch)
        // 静止锁定期间的阈值会被放大，用来压掉「一动不动也触发」的噪声。
        // 静止锁定期间的阈值会被放大，用来压掉「一动不动也触发」的噪声。
        // v5.5：俯仰幅度阈值**不乘**距离系数（距离缩放交给静止门限与速度门限），
        //       只乘静止锁定与俯视增益，这样近距离点头不再需要两倍幅度。
        // v5.7：近距离增益改成**单向**，必须把方向传进去——否则仰头也会被压低，
        //       造成「近距离仰视误触」（实测 tiltUp 在 4.2° 就触发过）。
        //
        // 注意 lastAppliedNodBoost 只能在**本方向**算完之后赋值：诊断行要与 pitchTh 自洽，
        // 所以先把反方向阈值算完，最后才记录本帧实际生效的增益。
        val threshold = pitchThresholdNow(signedPitch)
        pitchThresholdDeg = threshold
        // 反方向阈值一并算出来给诊断行：用户直接能看到"低头 5.4° / 抬头 8.0°"。
        pitchThresholdUpDeg = pitchThresholdNow(-signedPitch)
        lastAppliedNodBoost = nearNodDownBoost(signedPitch)
        lastSignedPitch = signedPitch

        if (magnitude >= DIAGNOSTIC_LOG_DEG && abs(signedPitch - lastLoggedPitch) >= 3f) {
            Log.i(
                TAG,
                "pitch $pitchDeg° base $baselineDeg° delta ${"%.1f".format(signedPitch)}°" +
                    yawLogSuffix(),
            )
            lastLoggedPitch = signedPitch
        }

        // 速度必须在所有提前返回之前算一次：否则「刚进动作的那一帧」会被当成第一帧
        // （没有可比的前一点），快通道就永远赶不上最快的那一段。
        val velocity = pitchVelocity(nowMs)
        val speedGate = effectivePitchSpeedGate()
        pitchSpeedGate = speedGate

        // 回中锁定：只在**已经越过 onset 门槛**时才参与，这样它也会在头部静止时被
        // 正常解除（v5.1 之前把解除逻辑放在 onset 判断之后，静止时锁永远不释放）。
        val lockDir = recenterLockDirection
        if (lockDir != 0) {
            val sameDirection = (signedPitch > 0 && lockDir > 0) || (signedPitch < 0 && lockDir < 0)
            updateRecenterLock(signedPitch, threshold, sameDirection, nowMs)
        }

        // 所有「不触发」的原因都收敛到这一个变量，便于日志里直接说明为什么没触发。
        val reject: String? = run {
            if (magnitude < threshold * ONSET_FRACTION) return@run "below-onset"
            if (recenterLockDirection != 0 && recenterLocked &&
                !((signedPitch > 0 && recenterLockDirection > 0) ||
                    (signedPitch < 0 && recenterLockDirection < 0))
            ) {
                return@run "recenter-lock"
            }
            if (pitchOnsetAtMs == 0L) pitchOnsetAtMs = nowMs
            if (magnitude < threshold) return@run "below-threshold"
            if (pitchReachedAtMs == 0L) {
                pitchReachedAtMs = nowMs
                val riseMs = nowMs - pitchOnsetAtMs
                pitchArmed = riseMs <= motionWindowMs
                if (!pitchArmed) Log.i(TAG, "ignored slow lean: rise ${riseMs}ms > ${motionWindowMs}ms")
            }
            if (!pitchArmed) return@run "slow-rise"
            val fast = velocity >= FAST_PITCH_VELOCITY
            if (!fast && nowMs - pitchReachedAtMs < requiredHoldMs()) return@run "hold-not-met"
            // 最低速度门限：噪声有幅度但没有速度，所以再加一道与幅度无关的门。
            if (!fast && velocity < speedGate) return@run "speed-gate"
            null
        }

        updatePostureRecenter(pitchDeg, baselineDeg, magnitude, threshold, reject, nowMs)

        if (reject != null) {
            // 只在「看起来像一次动作」时才记录，避免每帧刷屏。
            if (magnitude >= threshold * 0.8f) {
                // v5.7：日志要能一眼看出方向 —— 原来两个方向都打 "nodDown candidate"，
                // 排查「近距离仰视误触」时会把仰头候选误读成点头。
                val dir = if (signedPitch < 0f) "nodDown" else "tiltUp"
                Log.i(
                    TAG,
                    "$dir candidate rejected: pitch=${"%.1f".format(signedPitch)}° " +
                        "speed=${"%.4f".format(velocity)}°/ms gate=${"%.4f".format(speedGate)}°/ms " +
                        "threshold=${"%.1f".format(threshold)}° " +
                        "dist=${if (nearDistance) "near" else "far"} " +
                        "boost=${"%.2f".format(nearNodDownBoost(signedPitch))} reason=$reject",
                )
            }
            // 只有「真的回到静止」才清掉进行中的动作；被锁或速度不足时保留计时段，
            // 免得用户动作做到一半就被重置掉。
            if (reject == "below-onset") clearExcursion()
            updatePitchVelocitySample(signedPitch, nowMs)
            return
        }

        // 从「开始偏离」到「真正触发」的总耗时，用户能据此判断手感是否对等。
        val latencyMs = nowMs - pitchOnsetAtMs
        val fast = velocity >= FAST_PITCH_VELOCITY
        triggerCount++
        cooldownUntilMs = nowMs + cooldownMs
        clearExcursion()

        // 回中锁定（v5.0）：记下这次的方向，反方向要等头部回到中性区才放行，
        // 这样「仰头之后把头放回去」不会被当成一次点头。v5.2 加了 400ms 兜底，
        // 避免姿势偏置导致它永久卡死。
        recenterLockDirection = if (signedPitch < 0) -1 else 1
        recenterLocked = true
        recenterNeutralSinceMs = 0L
        recenterLockedAtMs = nowMs

        val staticNote = if (staticLocked) " · 静止锁定 ${"%.1f".format(threshold)}°" else ""
        val how = if (fast) "fast" else "held"
        val boosted = nearDistance
        val boostNote = if (boosted) {
            " (boosted, dist=near posture=${if (lookingDown) "down" else "flat"} " +
                "threshold=${"%.1f".format(threshold)}°)"
        } else {
            ""
        }
        if (signedPitch < 0) {
            Log.i(
                TAG,
                "nodDown triggered$boostNote pitch=${"%.1f".format(signedPitch)}° " +
                    "latency=${latencyMs}ms ($how, v=${"%.3f".format(velocity)}°/ms)$staticNote",
            )
            onEvent(
                HeadEvent.NodDown(
                    signedPitch,
                    "低头 ${signedPitch.toInt()}°（${latencyMs}ms 内触发）",
                ),
            )
        } else {
            // v5.7：仰头触发也把完整判据打出来（用户明确要求的格式），
            // 这样"近距离仰视误触"的每一次误触都能直接读出当时的速度与阈值。
            Log.i(
                TAG,
                "tiltUp triggered: pitch=${"%.1f".format(signedPitch)}° " +
                    "speed=${"%.4f".format(velocity)}°/ms threshold=${"%.1f".format(threshold)}° " +
                    "dist=${if (nearDistance) "near" else "far"} " +
                    "boost=${"%.2f".format(nearNodDownBoost(signedPitch))} " +
                    "latency=${latencyMs}ms ($how)$staticNote",
            )
            onEvent(
                HeadEvent.TiltUp(
                    signedPitch,
                    "仰头 ${signedPitch.toInt()}°（${latencyMs}ms 内触发）",
                ),
            )
        }
    }

    /**
     * 姿势偏置自动校正 + 俯视姿态识别（v5.2 引入，v5.4 扩展）。
     *
     * ## 为什么需要
     *
     * 用户大多数时间是**俯视**看手机（手机在下方、眼睛往下看），此时俯仰角长期偏负。
     * 如果基准线不跟着走，这个偏负的偏移就会被当成"一直在低头"，白白吃掉阈值余量：
     * 往下动一点立刻越过阈值（误触），往上一动却要走很远（不灵敏）。
     *
     * 两条路径共用同一套动作，只是**触发所需的持续时间不同**：
     *
     *  - **俯视姿态**（持续 [DOWN_GAZE_AFTER_MS] = 2 秒）：用户明确要求的行为——
     *    长期俯视时把当前姿态当作新的中性点。
     *  - **一般姿势偏置**（持续 [BIAS_RECENTER_AFTER_MS] = 1.5 秒）：靠椅背、凑近屏幕
     *    这类缓慢变化。
     *
     * 两者都必须满足「越过死区、但始终没到阈值」：到了阈值就是一次动作，绝不能把动作
     * 当成姿势。有意动作在 500ms 内就结束或已触发，能维持 1.5~2 秒的只可能是姿势。
     *
     * 判据用的是**相对基准线**的偏移，不是绝对俯仰角 —— 所以倒着拿手机、躺着看这些
     * 姿势同样适用，不需要为每个姿势单独设阈值。该逻辑只作用于俯仰轴，
     * 不碰偏航（扭头）与眨眼。
     */
    private fun updatePostureRecenter(
        pitchDeg: Float,
        baseline: Float,
        magnitude: Float,
        threshold: Float,
        reject: String?,
        nowMs: Long,
    ) {
        val inDeadzone = magnitude < threshold * ONSET_FRACTION
        val isBiased = !inDeadzone && magnitude < threshold
        if (!isBiased) {
            biasSinceMs = 0L
            return
        }
        if (biasSinceMs == 0L) {
            biasSinceMs = nowMs
            return
        }

        val heldMs = nowMs - biasSinceMs
        val relative = pitchDeg - baseline

        // 俯视：相对基准线长期偏负超过门限。
        if (relative <= -DOWN_GAZE_MIN_DEG && heldMs >= DOWN_GAZE_AFTER_MS) {
            downGazeCount++
            val corrected = applyBaselineShift(pitchDeg, baseline, nowMs)
            markDownGazeActive(nowMs)
            Log.i(
                TAG,
                "posture: looking down detected (held ${heldMs}ms, " +
                    "relative ${"%.1f".format(relative)}°), baseline gradually shifted to " +
                    "${"%.1f".format(corrected)}° (#${downGazeCount}) " +
                    "-> downGaze active, nod sensitivity boosted, threshold adjusted to " +
                    "${"%.1f".format(pitchThresholdNow(-1f))}° (nod down), " +
                    "${"%.1f".format(pitchThresholdNow(1f))}° (tilt up)",
            )
            return
        }

        // 一般姿势偏置（任何方向）。
        if (heldMs >= BIAS_RECENTER_AFTER_MS) {
            biasRecenterCount++
            val corrected = applyBaselineShift(pitchDeg, baseline, nowMs)
            Log.i(
                TAG,
                "posture bias recentred #$biasRecenterCount: baseline " +
                    "${"%.1f".format(baseline)}° -> ${"%.1f".format(corrected)}° " +
                    "(held ${"%.1f".format(magnitude)}° for ${heldMs}ms without reaching " +
                    "${"%.1f".format(threshold)}°, lastReject=$reject)",
            )
        }
    }

    /**
     * 距离与姿态判定（v5.6）。
     *
     * 用**绝对几何**而不是相对基准线的偏移：
     *
     *  - **距离**用 [faceRatio]（脸高占画面比例）——脸大 = 近，脸小 = 远；
     *  - **姿态**用 [chinRatio]（`|下巴Y − 眼中心Y| / 脸高`）——下巴占比大 = 俯拍，
     *    五官分布正常 = 平拍。
     *
     * 这样判断的好处是它**不受基准线自适应影响**：用户一直俯视时基准线早就移过去了，
     * 相对偏移趋近 0，v5.4 的"相对俯视"判据因此一直不成立（实测 `downGaze=0`）；
     * 而绝对几何始终反映真实的拍摄角度。
     *
     * 姿态带迟滞 + EMA 平滑，避免在门限附近反复切换灵敏度。
     */
    private fun updateDistanceAndPosture(nowMs: Long) {
        nearDistance = (faceRatio ?: 0f) >= NEAR_DISTANCE_RATIO

        val raw = chinRatio
        if (raw == null) {
            // 没有关键点（侧脸、遮挡）：保持上一帧的判定，不要凭空翻转灵敏度。
            postureLabel = if (lookingDown) "俯视(旧)" else "平视(旧)"
            return
        }

        val previous = smoothedChinRatio
        val smoothed = if (previous == null) raw else previous + POSTURE_EMA_ALPHA * (raw - previous)
        smoothedChinRatio = smoothed
        pushChinSample(smoothed)

        val wasDown = lookingDown
        lookingDown = if (wasDown) {
            // 已在俯视：要跌到（门限 − 回差）以下才算回到平视。
            smoothed >= DOWN_POSTURE_CHIN_RATIO - DOWN_POSTURE_HYSTERESIS
        } else {
            smoothed >= DOWN_POSTURE_CHIN_RATIO
        }

        val distance = if (nearDistance) "near" else "far"
        postureLabel = if (lookingDown) "down" else "flat"

        if (lookingDown != wasDown) {
            Log.i(
                TAG,
                "posture changed: ${if (wasDown) "down" else "flat"} -> $postureLabel " +
                    "(dist=$distance, chinRatio=${"%.3f".format(smoothed)} " +
                    "downThreshold=${"%.2f".format(DOWN_POSTURE_CHIN_RATIO)}) — " +
                    "note: nod boost is distance-driven, posture is diagnostic only",
            )
        }
    }

    /**
     * 当前生效的点头增益（v5.6）。
     *
     * ## 为什么最终是「按距离」而不是「按俯视几何」
     *
     * 用户原始需求是"近距离俯视时点头再灵敏一点"，并给了很直观的设想：脸大=近、
     * 下巴占比大=俯视。实机标定（各姿势保持 20 秒、按时间轴对齐）的结果是：
     *
     * ```
     * 远距   faceRatio 0.37~0.41   chinRatio 0.335~0.368
     * 近距   faceRatio 0.51~0.66   chinRatio 0.368~0.432
     * ```
     *
     * 两个结论：
     *  1. **`chinRatio` 主要在反映距离，而不是姿态**（近距比远距高出约 0.06，而"俯视"
     *     本身只带来很小的额外变化）。它的区分度不足以驱动灵敏度开关。
     *  2. 按几何推算的 0.68 门槛在实测里**从未达到**过，那样写等于功能不存在。
     *
     * 所以改成**只用距离**驱动：`faceRatio` 是干净、可靠、已经验证过的信号。
     * 这同时满足用户的两条硬要求——「近距离更轻松」与「远距离俯视不要改」——
     * 因为远距离本就不在增益范围内。
     *
     * 俯视几何（[chinRatio]）仍然保留在诊断行里，等有更干净的标定数据再考虑启用；
     * 现在**绝不**把不可靠的信号接到灵敏度上。
     */
    /**
     * 近距离的「点头向下」增益（v5.6 引入，v5.7 收窄方向）。
     *
     * ## v5.7 修的是什么
     *
     * v5.6 把 0.68 乘在**共用的俯仰阈值**上，于是**仰头方向也一起吃到了这个系数**：
     * 用户灵敏度设 8° 时，近距离仰头只要 5.4° 就触发（设 6° 时只要 4.1°）。
     * 实机日志坐实了这一点：
     *
     * ```
     * tiltUp triggered pitch=4.2°  (当时 faceRatio=0.54, pitchTh=4.1°)
     * tiltUp triggered pitch=6.0°  (当时 faceRatio=0.50, pitchTh=6.0°)
     * 45 次 tiltUp vs 14 次 nodDown
     * ```
     *
     * 用户的原话是「近距离仰视会误触，而平视不会」。被动仰视（顺着脖子往后靠、
     * 抬头看远处）幅度就在 4~7°，正好被压低的阈值放行；而用户**主动**仰头是 9~11°。
     *
     * 所以增益必须是**单向**的：只压低头方向，抬头方向回到用户设定值。
     * 这样被动仰视（4~7°）够不到 8°，主动仰头（9~11°）照样过 —— 正是用户要的
     * 「被动仰视不触发，主动仰头才触发」。
     *
     * 顺带说明为什么不能靠"抬大幅度阈值"来修：那样会把主动仰头一起挡住。
     * 单向增益既保住了近距离点头的省力，又不动仰头的手感。
     *
     * @param signedPitch 本帧的有符号俯仰偏移：负 = 低头（nod down），正 = 抬头（tilt up）
     */
    private fun nearNodDownBoost(signedPitch: Float): Float =
        if (nearDistance && signedPitch < 0f) NEAR_DOWN_NOD_BOOST else 1f

    /** 维护 `chinRatio` 的滑动中位数，供离线标定俯视门限（v5.6）。 */
    private fun pushChinSample(value: Float) {
        chinWindow[chinIndex] = value
        chinIndex = (chinIndex + 1) % CHIN_WINDOW_SAMPLES
        if (chinCount < CHIN_WINDOW_SAMPLES) chinCount++
        if (chinCount < 4) return
        System.arraycopy(chinWindow, 0, scratch, 0, chinCount)
        java.util.Arrays.sort(scratch, 0, chinCount)
        chinRatioMedian = scratch[chinCount / 2]
    }

    /**
     * 标记「当前处于俯视姿态」并刷新俯视增益（v5.5）。
     *
     * 用时间戳 + 窗口而不是布尔量，这样"回到平视"不需要任何显式通知就会自动恢复：
     * 只要超过 [DOWN_GAZE_ACTIVE_WINDOW_MS] 没有新的俯视判定，增益就回到 1.0。
     */
    private fun markDownGazeActive(nowMs: Long) {
        lastDownGazeAtMs = nowMs
        downGazeActive = true
        nodDownGazeBoost = DOWN_GAZE_NOD_BOOST.coerceAtLeast(MIN_DOWN_GAZE_NOD_BOOST)
    }

    /** 每帧检查俯视增益是否该失效（回到平视）。 */
    private fun refreshDownGazeBoost(nowMs: Long) {
        if (!downGazeActive) return
        if (nowMs - lastDownGazeAtMs <= DOWN_GAZE_ACTIVE_WINDOW_MS) return
        downGazeActive = false
        nodDownGazeBoost = 1f
        Log.i(TAG, "posture: back to level gaze — nod sensitivity restored to normal")
    }

    /**
     * 把基准线往当前姿态挪一个比例（渐进，避免画面跳变），并重新计时，让它在随后几帧
     * 继续收敛而不是一帧到位。
     *
     * @return 挪动后的基准线，便于日志显示
     */
    private fun applyBaselineShift(pitchDeg: Float, baseline: Float, nowMs: Long): Float {
        val corrected = baseline + (pitchDeg - baseline) * BIAS_RECENTER_ALPHA
        pitchWindow.fill(corrected)
        baselineDeg = corrected
        biasSinceMs = nowMs
        return corrected
    }

    /**
     * 维护回中锁定状态。
     *
     * ## v5.2 修的关键 bug：这个锁曾经会**永久卡住**
     *
     * v5.0/v5.1 只用「角度回到中性区并稳定 150ms」来解除，而中性区是相对基准线的
     * `±阈值×0.4`（默认 ±2.4°）。问题是**人的自然姿势常常长期偏离基准线**——实测数据：
     * 俯仰角 -2.85°、基准线 3.69°，偏差 6.5°，永远进不了 2.4° 的中性区。
     *
     * 结果就是：仰头触发一次之后，回中锁定**再也不会解除**，反向的点头被永久屏蔽。
     * 用户反馈「仰头好用了，但点头全失效了」正是这个原因。
     *
     * 现在加两条独立的解除条件，满足任意一条就解锁：
     *  1. 回到中性区并稳定 [RECENTER_SETTLE_MS]（原逻辑，正常情况走这条）；
     *  2. **最长锁定 [RECENTER_MAX_LOCK_MS]**（兜底）。回正动作本身只要几百毫秒，
     *     锁定超过这个时间就说明用户早就回正了、只是姿势偏置让条件 1 失效。
     *
     * 用户当初的原始需求也确实是「回中抑制期 300–500ms」，所以这个兜底同时是把行为
     * 拉回设计意图。
     */
    private fun updateRecenterLock(
        signedPitch: Float,
        threshold: Float,
        sameDirection: Boolean,
        nowMs: Long,
    ) {
        // 兜底：锁得太久就强制解除，绝不允许它变成永久屏蔽。
        if (recenterLocked && nowMs - recenterLockedAtMs >= RECENTER_MAX_LOCK_MS) {
            recenterLockDirection = 0
            recenterLocked = false
            recenterNeutralSinceMs = 0L
            Log.i(
                TAG,
                "recenter lock released by timeout (${RECENTER_MAX_LOCK_MS}ms) — " +
                    "posture offset kept it out of the neutral zone",
            )
            return
        }

        val neutral = threshold * RECENTER_NEUTRAL_FRACTION
        if (abs(signedPitch) > neutral) {
            // 还没回到中性区（或者又跑出去了）：重新计时。
            recenterNeutralSinceMs = 0L
            recenterLocked = true
            return
        }
        if (!sameDirection) {
            // 停在中性区里：连续稳定一小会儿就解除。
            if (recenterNeutralSinceMs == 0L) {
                recenterNeutralSinceMs = nowMs
                recenterLocked = true
                return
            }
            if (nowMs - recenterNeutralSinceMs >= RECENTER_SETTLE_MS) {
                recenterLockDirection = 0
                recenterLocked = false
                recenterNeutralSinceMs = 0L
                Log.i(TAG, "recenter lock released, back to neutral")
            }
        }
    }

    /**
     * 俯仰角速度（度/毫秒），用最近两个样本估计。
     *
     * 返回 0 表示「刚刚开始动作、还没有可比的前一点」，此时不支持快通道——这样第一帧
     * 的抖动永远无法借速度之名溜过去。
     */
    private fun pitchVelocity(nowMs: Long): Float {
        val previousAt = prevSignedPitchAtMs
        if (previousAt == 0L || nowMs - previousAt > VELOCITY_SAMPLE_MS) return 0f
        val dt = (nowMs - previousAt).coerceAtLeast(1L)
        return abs(lastSignedPitch - prevSignedPitch) / dt
    }

    private fun yawVelocity(nowMs: Long): Float {
        val previousAt = prevSignedYawAtMs
        if (previousAt == 0L || nowMs - previousAt > VELOCITY_SAMPLE_MS) return 0f
        val dt = (nowMs - previousAt).coerceAtLeast(1L)
        return abs(lastSignedYaw - prevSignedYaw) / dt
    }

    /** 把本帧读数变成下一帧的「前一点」。 */
    private fun updatePitchVelocitySample(signedPitch: Float, nowMs: Long) {
        prevSignedPitch = signedPitch
        prevSignedPitchAtMs = nowMs
    }

    private fun updateYawVelocitySample(signedYaw: Float, nowMs: Long) {
        prevSignedYaw = signedYaw
        prevSignedYawAtMs = nowMs
    }

    // ----------------------------------------------------------- 偏航：左右扭头 --

    private fun evaluateYaw(yawDeg: Float, nowMs: Long, distanceBoost: Float) {
        // 前面是前置摄像头，画面是镜像的：往自己右边扭头，画面里的脸是往它的左边转，
        // ML Kit 给出的偏航角符号因此与物理方向相反，这里先翻正。
        val delta = yawDeg - baselineYawDeg
        val mirrored = -delta
        val signedYaw = if (invertYaw) -mirrored else mirrored
        val magnitude = abs(signedYaw)
        // 静止锁定同样作用于扭头，压制「手在脸旁晃动」这类横向噪声；
        // v5.4 起再乘距离系数，近距离下阈值同步放大。
        val turnThreshold = turnThresholdNow(distanceBoost)
        yawThresholdDeg = turnThreshold
        lastSignedYaw = signedYaw

        if (magnitude >= DIAGNOSTIC_LOG_DEG && abs(signedYaw - lastLoggedYaw) >= 5f) {
            Log.i(
                TAG,
                "yaw $yawDeg° base $baselineYawDeg° delta ${"%.1f".format(signedYaw)}°",
            )
            lastLoggedYaw = signedYaw
        }

        val onsetDeg = turnThreshold * ONSET_FRACTION
        if (magnitude < onsetDeg) {
            clearTurn()
            updateYawVelocitySample(signedYaw, nowMs)
            return
        }

        // 换方向了：以新方向的起始时刻重新计时，避免「左转-右转」连成一次。
        val sign = if (signedYaw < 0) -1 else 1
        if (yawSign != sign) {
            clearTurn()
            yawSign = sign
        }

        // 速度在提前返回之前算一次，理由同俯仰：刚进动作那一帧才是最快的。
        val velocity = yawVelocity(nowMs)

        if (yawOnsetAtMs == 0L) yawOnsetAtMs = nowMs
        if (magnitude < turnThreshold) {
            updateYawVelocitySample(signedYaw, nowMs)
            return
        }

        if (yawReachedAtMs == 0L) {
            yawReachedAtMs = nowMs
            val riseMs = nowMs - yawOnsetAtMs
            yawArmed = riseMs <= turnMotionWindowMs
            if (!yawArmed) {
                Log.i(TAG, "ignored slow turn: rise ${riseMs}ms > ${turnMotionWindowMs}ms")
            }
        }
        if (!yawArmed) {
            updateYawVelocitySample(signedYaw, nowMs)
            return
        }

        // 与俯仰一致：够快立刻触发，否则才要求保持。
        val fast = velocity >= FAST_YAW_VELOCITY
        if (!fast && nowMs - yawReachedAtMs < requiredTurnHoldMs()) {
            updateYawVelocitySample(signedYaw, nowMs)
            return
        }

        // 最低速度门限，理由同俯仰：噪声有幅度但没有速度（近距离同样放大）。
        if (!fast && velocity < effectiveYawSpeedGate()) {
            clearTurn()
            yawSign = sign
            updateYawVelocitySample(signedYaw, nowMs)
            return
        }

        // 注意：延迟必须在 clearTurn() **之前**取，因为 clearTurn 会把 yawOnsetAtMs 归零。
        // v4.8 就是在这里写反了，日志里打印出 29.8 亿毫秒这种天文数字。
        val latencyMs = nowMs - yawOnsetAtMs

        turnCount++
        triggerCount++
        cooldownUntilMs = nowMs + cooldownMs
        clearTurn()
        updateYawVelocitySample(signedYaw, nowMs)

        val direction = if (sign < 0) "左" else "右"
        val reason = "向${direction}扭头 ${abs(signedYaw).toInt()}°（${latencyMs}ms 内触发）"
        Log.i(
            TAG,
            "$direction turn triggered yaw=${"%.1f".format(abs(signedYaw))}° latency=${latencyMs}ms " +
                "(${if (fast) "fast" else "held"})",
        )
        onEvent(
            if (sign < 0) {
                HeadEvent.TurnLeft(signedYaw, reason)
            } else {
                HeadEvent.TurnRight(signedYaw, reason)
            },
        )
    }

    private fun yawLogSuffix(): String =
        lastYawDeg?.let { " yaw $it°" } ?: ""

    // --------------------------------------------------------------- 基准线 --

    private fun pushPitch(value: Float) {
        pitchWindow[pitchIndex] = value
        pitchIndex = (pitchIndex + 1) % WINDOW_SAMPLES
        if (pitchCount < WINDOW_SAMPLES) pitchCount++
    }

    private fun pushYaw(value: Float) {
        yawWindow[yawIndex] = value
        yawIndex = (yawIndex + 1) % WINDOW_SAMPLES
        if (yawCount < WINDOW_SAMPLES) yawCount++
    }

    /** Median of the values currently in the ring buffer. */
    private fun median(buffer: FloatArray, count: Int): Float {
        if (count <= 0) return 0f
        System.arraycopy(buffer, 0, scratch, 0, count)
        Arrays.sort(scratch, 0, count)
        return scratch[count / 2]
    }
}
