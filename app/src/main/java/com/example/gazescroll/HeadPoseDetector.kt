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
         * 方向仲裁的速度判据系数（v5.8）。
         *
         * 仲裁**只看速度**，见 [isYawMovingNow] 的说明：幅度在两个轴之间
         * 没有鉴别力（扭头时偏航幅度本来就大），第一版加了幅度兜底反而挡掉了合格的扭头。
         */
        private const val ARBITRATION_VELOCITY_FRACTION = 0.4f

        /**
         * 方向仲裁的「占优」系数（v5.11）：偏航速度必须至少是俯仰速度的这么多倍，
         * 才允许把这次俯仰候选判成「其实在扭头」。见 [isYawMovingNow] 的实机数据。
         */
        private const val ARBITRATION_DOMINANCE_RATIO = 1f

        /** 方向仲裁的偏航幅度闸门（v5.11）：至少偏到扭头阈值的这么多倍才算「真在扭头」。 */
        private const val ARBITRATION_YAW_FRACTION = 0.3f

        // ------------------- v5.9 试过、v5.10 已撤销：触发确认窗口 -------------------
        //
        // v5.9 曾要求「阈值必须在最近 3 帧里至少 2 帧被越过」才允许触发，意图是挡掉
        // 单帧跳变。实机（v59-verify.log，用户按阶段标记复测）的结论是**必须撤销**：
        //
        //  - **误触一次都没减少**：静止 90 秒（30cm 俯视）里头部路径的越阈值次数是 **0**，
        //    那几次误触全部是 `lastTrigger=blink`（眨眼），确认窗口根本不在那条链路上。
        //  - **真实动作被明显拖慢**：正常使用阶段一共打出 37 条 `DISCARDED`
        //    （阶段④ 21 条、阶段⑥ 16 条），用户反馈「要更大的角度才触发」、
        //    「仰头和点头要等一会，大概 0.5 秒，不像之前那么丝滑」——
        //    轻快的点头只会跨一帧阈值，确认窗口把它整个吃掉了。
        //
        // 教训与 v5.5/v5.7 一致：**不要用会连带拖慢真实动作的手段去防误触**，
        // 先找到误触到底来自哪个检测器（这次是眨眼），再动那一条链路。
        // 逐帧上下文日志（见 [CONTEXT_SAMPLES]）保留下来，它正是这次能一眼定案的原因。

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

        // ----------------------------- v5.9：统一且带滞回的距离档 --

        /**
         * 进入「近距离」档的阈值（v5.9）。
         *
         * ## 为什么从 0.50 / 0.55 统一到 0.46，并且带滞回
         *
         * 实机标定（v5.9 采集，4 分钟带标记数据）暴露了一个**结构性空档**：用户真实的
         * 30cm 俯视姿势下 `faceRatio` 稳定落在 **0.45~0.55**，而代码里三个「近距离」判据
         * 用了两个不同的门槛：
         *
         * ```
         * 点头省力增益 0.68（阈值 6.0°→4.1°）  faceRatio >= 0.50
         * 速度门限 ×2 / 静止峰峰值 ×2 / 眨眼收紧   faceRatio >= 0.55
         * 30cm 静止硬锁定                       faceRatio >= 0.55
         * ```
         *
         * 于是 0.50~0.55 这一段成了「**灵敏度按近距离给、防护按中距离算**」的空档，
         * 而且 0.50 这条线正好落在用户的抖动带里，逐帧抖动 → `nodBoost` 在
         * 0.68 与 1.00 之间反复跳、`distMode` 在 near/far 之间反复跳（日志实测）。
         *
         * 现在只保留**一个**真值 [nearDistance]，由本组常量 + EMA 平滑 + 回差共同决定，
         * 所有按距离缩放的东西（点头增益、速度门限、静止峰峰值）全部读它。
         *
         * 门槛取 0.46：略低于用户实测的 0.45~0.55 区间下沿，保证 30cm 稳定判为近距离；
         * 50cm（实测 0.37~0.41）与 40cm（约 0.42~0.45）**回不到近距离档**，
         * 所以「远距离点头/仰头/扭头正常」这一条不受影响。
         */
        private const val NEAR_ENTER_RATIO = 0.46f

        /**
         * 退出「近距离」档的阈值（v5.9）：比进入门槛低 [NEAR_HYSTERESIS_RATIO]，
         * 这样在门槛附近抖动（实测 ±0.02）不会来回切档。
         */
        private const val NEAR_EXIT_RATIO = 0.40f

        /** `faceRatio` 的 EMA 平滑系数：越小越稳、越大越跟手。 */
        private const val FACE_RATIO_EMA_ALPHA = 0.35f

        /**
         * 俯视判定的几何门限 —— **未启用**（v5.6 试过、v5.12 起彻底不用）。
         *
         * 实测标定发现 `|下巴Y − 眼中心Y| / 脸高` 主要在反映距离而非姿态
         * （远距 0.335~0.368、近距 0.368~0.432），区分度不足以驱动灵敏度开关，
         * 因此点头增益改由**距离**驱动（见 [nearNodDownBoost]）。
         *
         * v5.12 找到了更好的俯视判据：**基准俯仰角**（见 [DOWN_POSTURE_BASE_DEG]，
         * 实测 30cm 俯视 base=7.8~14.4、50cm 平视 base=0~2，分离干净）。
         * 这两个常量与 [chinRatio] 现在**只作标定记录与诊断**保留，不参与任何判定。
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

        // ------------------------- v5.12：近距离俯视点头专用档 + 晃动过滤 --

        /**
         * 「俯视」判据：**基准俯仰角**（`baselineDeg`，即静止时人脸相对摄像头的绝对俯仰中位数）
         * 达到这个度数就认为用户在俯视看手机（v5.12）。
         *
         * ## 为什么这次能用基准俯仰角，而 v5.6 用 chinRatio 失败
         *
         * v5.6 试过用几何比例 `|下巴Y−眼中心Y| / 脸高` 判断俯视，实测发现它主要在反映**距离**
         * （远距 0.335~0.368、近距 0.368~0.432），区分度不足以驱动灵敏度开关，于是作废。
         *
         * 基准俯仰角是另一回事：它是**摄像头与脸的相对角度**，物理上直接对应
         * 「手机在脸的下方、眼睛往下看」。该用户实测：
         *
         * ```
         * 30cm 俯视   base = 7.8 ~ 14.4
         * 50cm 平视   base = 0 ~ 2
         * ```
         *
         * 分离干净，而且**不需要任何新传感器或新代码** —— 它本来就是基线学习中位数的输出。
         */
        private const val DOWN_POSTURE_BASE_DEG = 4f

        /** 俯视判定的回差：低于这个值才回到平视，避免在门限附近反复切换。 */
        private const val DOWN_POSTURE_EXIT_DEG = 3f

        /**
         * **近距离 + 俯视**时的点头增益：`6.0° × 0.42 = ` **2.5°**。
         *
         * ## 实机依据
         *
         * v5.12 用 0.50（3.0°）后，用户复测反馈「轻轻点头还是不触发，必须还要稍微动作大一点」
         * —— 日志里他的轻点头落在 3.0~4.4°，而 3.0° 恰好卡在门上。再降一档到 **2.5°**。
         *
         * v5.14 同时修掉了这个阈值的**不稳定**：它以前会与 v5.4 的俯视增益叠乘，
         * 于是随那条状态在 2.25° 与 3.0° 之间来回跳（见 [pitchThresholdNow]）。
         * 现在它是**一个稳定的数**，用户能记住"轻轻一点就够"。
         *
         * ⚠️ 阈值压低之后必须有东西挡住晃动与眨眼带来的假信号，否则地铁上、走路时会疯狂误触。
         * 现在的三道闸门是：路径效率（[SHAKE_PATH_EFFICIENCY]）、手机自身运动
         * （[PhoneMotionMonitor]）、以及闭眼后的姿态不可信期（[EYE_UNRELIABLE_MS]）。
         */
        private const val NEAR_LOOKDOWN_NOD_BOOST = 0.42f

        // ------------------------- v5.25：仰头方向的近距离档 + 参考点确认 --

        /**
         * **近距离 + 俯视**时的**仰头**增益：`6.0° × 0.63 = ` **3.8°**（v5.25）。
         *
         * 用户复测 v5.24 的原话：「仰头比之前好点了，但是还是不够灵敏，我的角度还是需要大一点，
         * 而且**仰头太不灵敏了，我仰头了，要等个差不多 0.4-0.5 秒才触发**」。
         *
         * 实机延迟统计（16 次仰头）：中位数 **202ms**，其中 3 次 ≥500ms —— 正好对上"等半秒"。
         * 原因不是识别慢，而是**阈值太高**：仰头的起点在 2.4°（onset）附近慢慢抬，
         * 要涨到 6.0° 才放行，于是从起点到触发要 200~760ms。降到 3.8° 直接把这个过程砍掉近一半。
         *
         * ⚠️ v5.7 曾因"近距离仰视被误触"把仰头增益**单向化**（只压低头方向）。
         * 现在敢给仰头也开一档，是因为同时加了**参考点确认**（v5.25 起；v5.27 改成看
         * 证人自己确认的结论 `refRelVerdict`，见该字段的说明）：
         * 实机数据里"鼻子相对眼睛的位移"在 16 次真实仰头中 14 次方向一致、**0 次相反**，
         * 而身体动一下只会让整张脸平移、不改变这个相对量 —— 用它把被动仰视挡在外面。
         */
        private const val NEAR_LOOKUP_BOOST = 0.63f

        /**
         * 晃动判据（v5.16 改判据）：**方向反转次数**，只拦高频抖动。
         *
         * ## 为什么把 v5.12 的"路径效率"换掉
         *
         * v5.12~v5.15 用的是路径效率（`|净位移| / Σ|相邻差值|`）。实机复测（v515-verify.log）
         * 证明它**把走路时的点头全挡了**：用户「走路的时候疯狂点头，但是只触发了一次」，
         * 日志里 19 次 `reason=shake`，幅度 −6.4~−9.3°、速度 0.05~0.08°/ms 的真实动作被拒。
         *
         * 根因是判据本身：走路时头部俯仰是**真实的周期性摆动（10~25°）**，
         * 于是**任何**窗口里都含摆动 → 路径效率必然低 → 一律判成晃动。这不是调参能解决的，
         * 是判据选错了。
         *
         * ## 新判据：数"方向反转"
         *
         * 两者的**频率**差得很远：
         *
         * ```
         * 走路（约 2Hz，实测帧间隔 ~90ms）：约 5~6 帧才反转一次 → 6 帧窗口内 ≤1 次
         * 手抖 / 高频摇晃（3~5Hz）：1~2 帧就反转一次    → 6 帧窗口内 3~6 次
         * 有意点头：单调推进，几乎没有反转              → 0~1 次
         * ```
         *
         * 所以只要"窗口内反转 ≥ [SHAKE_MIN_REVERSALS] 次"才算晃动。走路与点头都能过，
         * 高频抖动照样拦得住。至于**低频摇摆手机**（v5.14 ⑥ 那种），由
         * [PhoneMotionMonitor] 的陀螺仪闸门负责 —— 那是"手机在转"，比数头部反转更直接。
         */
        private const val SHAKE_WINDOW_SAMPLES = 6

        /** 窗口内至少这么多次方向反转才算"在抖"。 */
        private const val SHAKE_MIN_REVERSALS = 3

        /** 单帧变化小于这个度数就不算一个方向（噪声死区）。 */
        private const val SHAKE_DELTA_DEADBAND_DEG = 0.4f

        // ------------------- v5.17：轻通道的「运动起点」参考值 --

        /** 稳定判定的样本数。 */
        private const val SETTLED_WINDOW_SAMPLES = 3

        // ------------------- v5.31：基准线重建期禁止触发 --

        /**
         * 基准线被作废（丢脸回来 / recalibrate / reset）后，必须攒够这么多帧才允许触发。
         *
         * 为什么需要：`MIN_SAMPLES`（5）只保证"有 5 个样本能算中位数"，而**丢脸回来后的
         * 头几帧角度读数本身是脏的**（v5.30 实测：脸消失 100 秒回来后，原始俯仰从 -1.9°
         * 跳到 10.2°，45 帧中位数基准线一度算成 15.7°），于是 `signedPitch = 原始 − 基准`
         * 凭空差出 5°，在近距离俯视档（阈值 2.5°）直接触发一次下滑。
         *
         * 12 帧 ≈ 1.1 秒（实测帧间隔 63~116ms），足够让中位数基准线稳定下来；正常换应用
         * 进目标 App 时服务会用 [seedBaseline] 预填窗口，所以**这条门只在"基准线刚被作废"
         * 之后生效**，不影响日常响应速度。
         */
        private const val BASELINE_SETTLE_SAMPLES = 12

        /**
         * 连续 [SETTLED_WINDOW_SAMPLES] 帧的峰峰值小于这个度数，就认为"头是稳的"，
         * 把当前值记作 [settledPitch]（轻通道的运动起点）。
         *
         * 取 1.0° 是因为实测 30cm 处静止时的逐帧噪声就是 0.5~1°，再小就永远记不下起点。
         */
        private const val SETTLED_RANGE_DEG = 1.0f

        /**
         * 单帧最大可信台阶（v5.17）：相邻两帧的位移超过这个度数就不算"渐进动作"。
         *
         * 实测轻点头每帧只走 1~2°、有意仰头每帧 2~5°，所以 4° 不会误伤正常动作；
         * 而 ⑥ 静止段那两次误触是单帧跳 5~10°，正好被它挡住（见跳变确认处的说明）。
         */
        private const val JUMP_MAX_RAMP_STEP_DEG = 4.0f

        /**
         * 路径效率的**判决门限已废弃**（v5.12 引入，v5.16 起不再用于判定）。
         *
         * 效率仍然会被算出来并打进日志（`shake=`），因为它是"这次动得有多乱"的直观指标；
         * 但**判决改用 [SHAKE_MIN_REVERSALS]** —— 走路时头部是真摆动，任何窗口的效率都低，
         * 用它判决会把走路时的点头全部误杀（实测 19 次）。见 [SHAKE_MIN_REVERSALS] 的说明。
         */
        private const val SHAKE_PATH_EFFICIENCY = 0.45f

        /** 路径太短时效率没有意义（纯噪声）；只用于日志。 */
        private const val SHAKE_MIN_PATH_DEG = 1.5f

        // ------------------- v5.13：轻点头速度通道 + 手机自身运动闸门 --

        /**
         * 轻点头通道的速度门槛（v5.13）。
         *
         * ## 为什么必须有它（「疯狂点头却没反应」）
         *
         * 用户 v5.12 复测里出现了一段"疯狂点头但不触发，过一会才恢复"。日志坐实了原因：
         * 他的**轻点头速度只有 0.014~0.018°/ms**，而快通道的门槛 [FAST_PITCH_VELOCITY] 是 0.02：
         *
         * ```
         * 03:33:39.430 nodDown rejected: pitch=-3.2° speed=0.0179°/ms reason=hold-not-met
         * 03:34:02.575 nodDown rejected: pitch=-2.6° speed=0.0183°/ms reason=below-threshold
         * 03:34:07.626 nodDown rejected: pitch=-3.5° speed=0.0161°/ms reason=hold-not-met
         * 03:34:07.983 nodDown triggered: pitch=-4.7° speed=0.0290°/ms   ← 等它自己变快才成
         * ```
         *
         * 达不到 0.02 就落到"保持"通道，而那条通道要求**保持 150ms 且速度 ≥ 速度门限**
         * —— 近距离速度门限是 `0.012×2 = 0.024`，**比 0.02 还高**，所以那条路实际上永远走不通。
         * 结果就是"速度不够快的点头一律无效"，而轻点头天然就是慢的。
         *
         * 这个门槛只作用于**近距离 + 俯视 + 低头**（与 ×0.42 增益完全同一条件），
         * 远距离与仰头方向一概不受影响。
         *
         * ## v5.26：门槛从 0.009 拉回 **0.020**（= 标准快通道门槛）
         *
         * 用户复测 v5.25 时自己给出了解法，而且是对的：
         *
         * > 「点头和仰头你单独写个判定的方式，比如我真要下滑，我就用**快速点头**，
         * > 而不是**慢慢**的点头。有时候慢慢的点头，还是会被误判，所以要改一改。」
         *
         * 实机数据证实了这一点：**慢动作期间基准线/参考点会跟着跑，方向就会飘** ——
         * 日志里那些"仰头被判成下拉"的事件，位移通道读到的起点（settledPitch）已经过期
         * （例如 `light=yes(16.5→-12.6)`，29° 的"位移"其实是换了一次姿势），
         * 而它们的延迟都很大（807ms / 588ms / 544ms）—— 一眼可见是慢动作。
         *
         * 快速动作一瞬间完成，基准线与参考点来不及漂，所以方向是可靠的。
         * 于是两个方向统一要求"够快"，慢动作一律不触发 —— 这也让手势变得**可学习**：
         * 「快速点头 = 下滑、快速仰头 = 上滑」。
         */
        private const val LIGHT_NOD_FAST_VELOCITY = 0.020f

        /**
         * 轻通道允许的最大位移（v5.26）。
         *
         * 参考点（[settledPitch]）只在头部稳定时更新，所以一次大范围姿势变化之后它可能"过期"：
         * 实测出现过 `light=yes(16.5→-12.6)` —— 29° 的所谓"位移"根本不是点头，是换姿势，
         * 却因为位移巨大而轻松越过阈值。加上这个上限（12°）之后，这类事件不再走轻通道，
         * 只能走标准通道（要求快、且阈值本就是用户设定值）。
         */
        private const val LIGHT_MAX_DISPLACEMENT_DEG = 12f

        /**
         * 轻点头通道的确认时间（v5.13）：越过阈值后必须**再撑过这么久**才放行。
         *
         * 取 60ms 而不是更大，是因为实测帧间隔是 63~116ms —— 60ms 刚好保证"下一帧还活着就通过"，
         * 于是它等价于「**这个动作必须跨到下一帧**」：单帧跳变会在下一帧掉回 below-threshold
         * 并清掉计时段，永远过不来（这正是 v5.9 那次全局确认窗口想做的事，
         * 区别是现在只作用在这一条通道上，代价只有一帧，不会拖慢其他动作）。
         */
        private const val LIGHT_NOD_CONFIRM_MS = 60L

        /**
         * 闭眼之后「头部姿态不可信」的保持时长（v5.14 引入，v5.16 从 120ms 收到 50ms）。
         *
         * ## 用户报的那次误触，日志给了完整的因果链
         *
         * ```
         * 03:44:00.020  I/Blink: closure rejected: 63ms frames=1/2 minEye=0.98/0.14
         * 03:44:00.127  I/HeadPose: nodDown triggered pitch=-3.5° speed=0.037°/ms — ctx … -99ms|0.1 … 0ms|-3.5
         * ```
         *
         * 也就是：**一帧的单眼闭合之后 107ms，俯仰读数从 +0.1° 直接跳到 −3.5°**
         * （上下文里那一格是孤立的大值，前后都是 0 附近 —— 典型的单帧跳变）。
         * 用户的原话也对上了：「触发的时候我不受控制的自己眨眼了几次」。
         *
         * 道理很直白：ML Kit 的头部姿态是**靠关键点回归**出来的，眼睛闭上时眼部关键点消失，
         * 那一帧的姿态估计会跳。所以闭眼（哪怕只有一帧的浅闭）之后的短时间里，
         * 俯仰/偏航读数不能用来判定动作。
         *
         * 取 50ms 而不是更大（v5.16 从 120ms 下调），是实机数据逼出来的：
         *
         * v5.14 用 120ms 之后，`eyes-unreliable` 一共拒掉了 **126 次**越阈值的候选，
         * 而且里面全是**真实动作**：
         *
         * ```
         * tiltUp  candidate rejected: pitch=25.2° reason=eyes-unreliable
         * tiltUp  candidate rejected: pitch=17.5° reason=eyes-unreliable
         * nodDown candidate rejected: pitch=-3.2° reason=eyes-unreliable   ← 用户的轻点头
         * ```
         *
         * 原因是 30cm 处"某只眼睛跌破阈值"极其频繁（日志里几乎每秒都有），
         * 120ms 的窗口把头部判定压制了近三成时间 —— 用户反馈的
         * 「要等 0.5 秒才能触发」就是这么来的。
         *
         * 50ms 略小于一帧（实测帧间隔 63~116ms），效果是**只跳过闭眼那一帧本身**，
         * 而它后面那些帧交给 v5.15 的 [JUMP_CONFIRM_MS]（跳变确认）处理 ——
         * 实测那次眨眼误触正是一帧从 +0.1 跳到 −3.5，跳变确认足以拦住，
         * 不会像 120ms 那样连 25° 的真实动作一起挡掉。
         */
        private const val EYE_UNRELIABLE_MS = 50L

        // ------------------- v5.27：强候选（动作已经做出来了）--

        /**
         * 「强候选」余量（v5.27）：越阈值这么多度，就算**动作已经做出来了**，
         * 不再让软性门（闭眼不可信、保持时间、最低速度）有机会把它拖住。
         *
         * ## 实机依据（v5.26 日志，20:30:40.5~41.2，近距离俯视）
         *
         * 用户做了一次 3.5°→17.2° 的连续仰头（约 330ms，速度 0.04~0.08°/ms），
         * 结果**一帧都没放行**：
         *
         * ```
         * 40.665 tiltUp candidate rejected: pitch=10.9° reason=ref-veto-up
         * 40.759 tiltUp candidate rejected: pitch=14.6° reason=eyes-unreliable
         * 40.865 tiltUp candidate rejected: pitch=16.5° reason=eyes-unreliable
         * 40.933 tiltUp candidate rejected: pitch=17.2° reason=eyes-unreliable
         * 41.005 tiltUp candidate rejected: pitch=14.5° reason=eyes-unreliable
         * 41.092 tiltUp candidate rejected: pitch=13.3° reason=ref-veto-up
         * … 回摆末段 42.551 nodDown triggered（点成下滑）← 用户说的"延迟/判反/误触"
         * ```
         *
         * 两个原因都跟"幅度已经很大了"无关：仰头时眼皮被挤成半闭 → `eyeDip` 连续为真；
         * 证人位移在起手瞬间≈0 → 否决成立。这种时候再等软性门，等到的只有误判。
         *
         * ## 为什么 2.0° 是安全的
         *
         * 唯一的已知单帧跳变噪声是 3.5°（见 [EYE_UNRELIABLE_MS] 里那次眨眼误触）。
         * 近距离俯视的阈值是点头 2.5° / 仰头 3.8°，加上 2.0° 余量分别是 4.5° / 5.8°，
         * 都高于 3.5°；而且跳变确认（[JUMP_CONFIRM_MS]）、晃动判据、手机运动判据
         * 三道门都还在强候选前面，一个都没去掉。
         *
         * ⚠️ 只在**近距离档**（`nearDistance`）生效：用户的反馈只针对"近距离俯视"，
         * 远距离那一套一个字都不改。
         */
        private const val STRONG_MARGIN_DEG = 2.0f

        // ------------------- v5.28：扭头优先 / 位移判据 -------------------

        /**
         * 「头正在左右摆」的判据（v5.28）：**原始偏航**在 [YAW_SWING_WINDOW_MS] 内的摆幅。
         *
         * ## 为什么要另起一条判据，而不是修 v5.8 的仲裁
         *
         * v5.8/v5.11 的仲裁（[isYawMovingNow]）读的是**本帧**的有符号偏航偏移，而那个量
         * 依赖 `baselineYawDeg`。v5.27 实机日志里抓到一个致命组合：
         *
         * ```
         * 20:47:37.097 pitch 19.1° yaw -29.77°                     ← 用户正在往右扭头
         * 20:47:37.115 tiltUp triggered: pitch=9.3° … ctx 0ms yaw=1.5  ← 俯仰侧只看到 1.5°
         * 20:47:37.119 turnR candidate rejected: yaw=29.5° reason=eyes-unreliable
         * 20:47:37.124 A11y: swipe UP                                ← 用户想要 RIGHT，得到 UP ✗
         * ```
         *
         * 同一帧里扭头通道看到 **29.5°**（速度 0.051~0.118°/ms，明显在转头），
         * 俯仰通道只看到 **1.5°**（基准线当时刚被重学/重置），于是"让位给扭头"没成立，
         * 俯仰直接触发。用户的原话：「47分30秒左右，我左右扭，但是被判定成了上滑」。
         *
         * 所以判据换成**与基准线无关**的原始偏航摆幅：扭头时原始偏航实测摆 25~59°，
         * 而点头/仰头时只有 1.7~10.4°（同一场 43 次仰头触发的逐帧上下文统计）。
         * 取 12°：真扭头全部命中，真点头全部不命中。
         */
        private const val TURN_SWING_YIELD_DEG = 12f

        /** 原始偏航摆幅的观察窗口。 */
        private const val YAW_SWING_WINDOW_MS = 500L

        /** 原始偏航摆幅的采样数（窗口内最多保留这么多帧，约 5 帧 @11fps）。 */
        private const val YAW_SWING_SAMPLES = 8

        /**
         * 位移判据（v5.28）：**判定域**里从运动起点到当前必须真的走过这么多（× 当帧阈值）。
         *
         * 依据同样是 v5.27 日志：近距离档有若干次「头根本没动」却触发了 ——
         *
         * ```
         * 20:47:22 nodDown triggered  ctx 0.7 → 0.1   （位移 0.6°，却报了 -5.5° 的位移量）
         * 20:47:24 tiltUp  triggered  ctx 5.1 → 5.8   （位移 0.7°）
         * 20:46:58 nodDown triggered  ctx 0.0 → -1.3  （位移 1.3°）
         * ```
         *
         * 共同形状：头部姿势**本来就已经越过阈值**（相对滞后的基准线），此时一次轻微晃动
         * 就让"越阈值"成立 —— 用户说的「晃动的时候产生的误触」正是这一类。
         * 加上"必须从运动起点真正走过 0.5×阈值"之后，这类只剩噪声位移的候选被清掉，
         * 而真实动作（起点在 0.4×阈值、终点在阈值之上，天然走过 0.6×阈值）不受影响。
         */
        private const val MIN_TRAVEL_FRACTION = 0.5f

        /**
         * 近距离档的「突然性」窗口（v5.28）：从 onset（0.4×阈值）涨到阈值最多容许这么久。
         *
         * 用户的原话：「点头和扭头的判定是**突然性**的，就是突然快速的点头，而不是慢慢的晃动，
         * 所以加一个判定方式吧，**不是快速扭头点头的时候，就不触发**」。
         *
         * 近距离开头用 500ms（远处也一直是 500ms），实测近距离所有**真实**动作的
         * 「onset → 触发」延迟都在 249ms 以内（本场 52 次触发统计：中位数约 100ms、
         * 最长 249ms），所以 300ms 只砍得掉慢动作：头部从 1.5° 慢慢爬到 2.5~3.8°
         * （速度 ≤0.006°/ms，就是"慢慢晃"），同时保留全部真实快速动作。
         *
         * ⚠️ 为什么不用"触发延迟"直接做判据：延迟里**包含门控造成的等待**
         * （v5.27 远距离那两次 575/624ms 其实是很快的仰头，只是被闭眼门等了 2~3 帧），
         * 拿它当"慢"的判据会误杀真实动作。这里只量**从起点到第一次越阈值**的用时。
         */
        private const val NEAR_SUDDEN_RISE_MS = 300L

        /**
         * 近距离档「突然扭头」的窗口（v5.28）：转速慢于这个节奏就不算扭头。
         *
         * 比俯仰宽松一点（400ms vs 300ms），因为扭头本身幅度大（用户 20~40°），
         * onset（0.4×阈值=8°）到阈值之间要走的位移比俯仰多。实测本场 21 次真实扭头的
         * 「onset → 触发」延迟是 65~217ms，所以 400ms 只砍慢动作。
         */
        private const val NEAR_SUDDEN_TURN_RISE_MS = 400L

        // ------------------- v5.15：区分「渐进动作」与「单帧跳变」 --

        /**
         * 判断「上一帧」是否还在这个时间窗内（v5.15）。实测帧间隔 63~116ms，取 250ms 留足余量。
         */
        private const val JUMP_RAMP_WINDOW_MS = 250L

        /**
         * 单帧跳变的确认时间（v5.15）：越过阈值后必须再撑过这么久才放行。
         *
         * ## 判据：看**前一帧**在不在动，而不是看这一帧跳得多高
         *
         * v5.14 用户复测反馈「②误触了 4 次以上，说实话不如上一版」。日志里两种形状一眼可分：
         *
         * ```
         * 真实轻点头：… -0.2  0.0  0.3  -2.0  -2.8  -2.4  -2.7  -3.4   ← 前一帧已经在动
         * 误触      ：… -0.5  0.4  -3.1                              ← 前一帧还在 +0.4
         * ```
         *
         * 也就是：**真实动作是渐进的**（越过阈值那一帧的前一帧已经在向同一方向移动），
         * 而噪声/眨眼造成的跳变是"前一帧还在 0 附近、一帧跳过去"。
         *
         * 所以规则是：**渐进动作零延迟**（照旧立刻触发，绝不拖慢手感），
         * 只有"前一帧还没动"的跳变才要求**再撑过一帧**（本帧 ≥ 阈值、下一帧仍 ≥ 阈值）。
         * 单帧跳变在下一帧必然掉回值以下 → 被 `below-threshold` 清掉，永远不会触发。
         *
         * ⚠️ **这不是 v5.9 那个被撤销的确认窗口**。v5.9 要求"最近 3 帧里至少 2 帧越过
         * **完整阈值**"，于是轻点头被二次抬高门槛、用户反馈"要更大角度、要等 0.5 秒"。
         * 这里只针对**跳变型**（渐进型完全不受影响），而且确认帧走的是正常阈值判定，
         * 代价只有**跳变型动作**多一帧（约 90ms）。
         */
        private const val JUMP_CONFIRM_MS = 60L

        /** chinRatio 滑动中位数的窗口（约 0.6 秒 @15fps，只用于标定显示）。 */
        private const val CHIN_WINDOW_SAMPLES = 9

        /**
         * 回中锁定的**最短**持续时间（v5.2 是"最长 400ms 兜底"，v5.27 改成"最短"）。
         *
         * 这一条同时服务于两个目的：
         *  1. 用户最初的需求就是「回中抑制期 300~500ms」—— 触发后短时间内不许反向动作；
         *  2. 把 v5.2 那条**无条件解锁**的兜底换成"最短时间 + 真的停住了"。
         *
         * ## 为什么必须改（v5.26 实测 20:30:37.475 → 20:30:42.551）
         *
         * 旧逻辑 400ms 一到就解锁，**完全不看头在哪**：仰头触发后 400ms 头还在 15°~20° 高位
         * 就已经解锁了；随后用户把头放回来（这是一次 18° 的下行回摆），
         * 回摆末段穿过中性区时又被 `|pitch| < 中性区` 的 150ms 计时刷满，
         * 于是在 42.551 被判成一次"点头" → 又翻了一页。用户的原话是
         * 「仰头后还是有延迟翻页 + 还是有误触 + 判定不准」，这一次三样全占了。
         *
         * 现在解除必须同时满足「不在动」和下面任一条：
         *  - 回到中性区并稳定 [RECENTER_SETTLE_MS]（原逻辑）；
         *  - **或**头就停在自己当前的姿势上（`|signed − settledPitch| ≤ 中性区`）——
         *    这条专门给"姿势偏置"用：基准线偏了 6.5° 时 `|signed|` 永远进不了中性区，
         *    但 `settledPitch` 会跟着真实姿势走，所以差值很小，锁照样解得开，不会永久卡死。
         *
         * [RECENTER_HARD_CAP_MS] 是最后一道保险：停住再久也强制解锁。
         */
        private const val RECENTER_MIN_LOCK_MS = 400L

        /** 回中锁定的硬上限（v5.27）：不再看任何条件，只要"停住了"且超过它就解锁。 */
        private const val RECENTER_HARD_CAP_MS = 3000L

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

        /**
         * 中距判定：脸高占画面 ≥ 此值（v5.9 起**只**用于中距增益，近距一律走 [nearDistance]）。
         *
         * v5.9 之前这里还有一个 `NEAR_FACE_RATIO = 0.55`，与点头增益用的 0.50 分裂成
         * 两个门槛 —— 那正是「近距离只有灵敏度没有防护」的根因，已删除，见
         * [NEAR_ENTER_RATIO] 的说明。
         */
        private const val MID_FACE_RATIO = 0.38f

        /** 人脸消失这么久（哪怕只是被手挡一下）就作废基准线，回来重新学。 */
        const val BASELINE_INVALID_AFTER_NO_FACE_MS = 150L

        /** 静止判定滑窗的样本数（约 0.5 秒 @15fps 的读数，按时间再筛）。 */
        private const val MOTION_WINDOW_SAMPLES = 8

        /** Log excursions past this many degrees, to make the sign checkable. */
        private const val DIAGNOSTIC_LOG_DEG = 4f

        /**
         * 触发上下文保留的帧数（v5.9）。
         *
         * 之前只有 3 秒一条的 `GazeDiag` 汇总行，误触发生时**前几百毫秒到底发生了什么
         * 完全看不到** —— 这正是 v5.9 之前几轮排查只能靠猜的原因。现在每次触发都把
         * 前 [CONTEXT_SAMPLES] 帧的 `(dt, pitch, yaw, faceRatio)` 原始读数一并打出，
         * 于是「单帧尖峰」和「平滑上升」在日志里是一眼可辨的两种形状。
         */
        private const val CONTEXT_SAMPLES = 24

        /** 人脸重新出现、且消失了这么久以上，就打一条 `MARK` 行（v5.9 采集对齐用）。 */
        private const val FACE_BACK_MARKER_MIN_MS = 400L
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

    /**
     * 扭头也必须「快」，否则慢慢偏头也会被当成动作。
     *
     * v5.8：默认值从 600ms 提到 **900ms**（实机日志驱动）。
     *
     * 原因是这个窗口对扭头来说**先天太紧**，而它对俯仰是合适的：
     *  - 俯仰起点很容易定准 —— 点头一开始就是俯仰角在变；
     *  - 扭头的起点则常常**先有一段几乎不动的准备**（脖子先转、脸还没跟上），
     *    这段被算进 rise，于是正常的一次扭头会超窗，被 `slow-rise` 直接判死。
     *
     * 实机日志里这是**最多的一类扭头拒绝**，而且症状正好是用户说的"第一下打不中"：
     *
     * ```
     * turnL candidate rejected: yaw=54.4° speed=0.1496°/ms threshold=20.0° reason=slow-rise
     * turnL candidate rejected: yaw=39.4°  speed=0.1116°/ms reason=slow-rise   // 连续 8 帧
     * ```
     *
     * 54° 的扭头、0.1496°/ms 的速度（远高于 0.015 的速度门限）显然是一次真实动作，
     * 却被当成"慢慢蹭"。**"慢"不该由这个窗口定义，速度门限已经在管这件事了** ——
     * 这跟 v5.5「不要用幅度阈值挡噪声」是同一个道理：用错工具会连真实动作一起挡掉。
     *
     * 放宽到 900ms 后，速度门限仍然是唯一负责"够不够快"的判据，不会放行真正的慢偏头。
     */
    @Volatile
    var turnMotionWindowMs: Long = 900L

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

    /**
     * 基准线正在重建（v5.31）：这段窗口内**不允许任何头部触发**。
     *
     * 置位时机：丢脸回来（基准线作废）、[recalibrate]、[reset]。
     * 解除时机：俯仰窗口重新攒够 [BASELINE_SETTLE_SAMPLES] 帧。
     *
     * 依据（v5.30 实测日志 22:05:32）：脸消失 100 秒后回来，头几帧的原始俯仰从 -1.9°
     * 跳到 10.2°、中位数基准线一度算成 15.7°（真实值约 10.5°），于是 `原始 − 基准`
     * 凭空差出 5°，在近距离俯视档（阈值 2.5°）触发了一次**没人动过头部**的下滑。
     */
    @Volatile
    var baselineSettling: Boolean = false
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

    // ---- 触发上下文环形缓冲（v5.9）：每次触发把前约 1.2 秒的原始读数打进日志 ----

    /** 上下文保留的帧数（实测帧间隔约 47~66ms，24 帧约 1.1~1.6 秒）。 */
    private val ctxPitch = FloatArray(CONTEXT_SAMPLES)
    private val ctxYaw = FloatArray(CONTEXT_SAMPLES)
    private val ctxFaceRatio = FloatArray(CONTEXT_SAMPLES)
    private val ctxAtMs = LongArray(CONTEXT_SAMPLES)
    private var ctxIndex = 0
    private var ctxCount = 0

    // ---- 晃动过滤的环形缓冲（v5.12）：只看有符号俯仰「走过的路」 ----
    private val shakePitch = FloatArray(SHAKE_WINDOW_SAMPLES)
    private var shakeIndex = 0
    private var shakeCount = 0

    /**
     * 当前路径效率（v5.12），诊断用：`|净位移| / Σ|相邻差值|`。
     *
     * 越接近 1 越像"有意动作"，越低越像"来回晃"。挂在诊断行上，
     * 于是"地铁上到底算不算晃"可以直接读日志判断，不用猜。
     */
    @Volatile
    var shakeEfficiency: Float = 1f
        private set

    /**
     * 当前窗口内的**方向反转次数**（v5.16），诊断用 —— 晃动判决用的就是它。
     *
     * 走路（约 2Hz）6 帧内 ≤1 次、有意点头 0~1 次、手抖/高频摇晃 3~6 次。
     */
    @Volatile
    var shakeReversals: Int = 0
        private set

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

    /** 回中锁定开始的时刻（v5.27：用于最短抑制期与硬上限，见 [RECENTER_MIN_LOCK_MS]）。 */
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
     * **手机本身**是否正在被顿挫（v5.13），由服务从 [PhoneMotionMonitor] 每帧同步。
     *
     * 为 true 时一律不接受俯仰/偏航候选。理由见 [PhoneMotionMonitor]：
     * 点头是头在转（手机不动），急停/急刹是整个人和手机一起顿 —— 摄像头分不出来，
     * 加速度计分得出来。判据是"手机在动"，所以**不影响任何正常坐着/躺着刷的场景**。
     */
    @Volatile
    var phoneMoving: Boolean = false

    /**
     * 本帧是否出现了「眼睛读数跌破闭眼阈值」（v5.14），由服务从 [BlinkDetector] 同步。
     *
     * 注意这里用的是**单帧的浅闭也算**（`closedNow`），而不是"已确认的眨眼"：
     * 实测那次误触的元凶正是一帧的单眼浅闭（`frames=1/2`，永远不会被确认成眨眼）。
     * 配合 [EYE_UNRELIABLE_MS] 的恢复期使用，见该常量的因果链说明。
     */
    @Volatile
    var eyeDip: Boolean = false

    /**
     * 「鼻子相对眼睛」的位移（v5.25），由服务从 [ReferencePointDetector] 每帧同步。
     *
     * 负值 = 鼻子在脸内部往上走 = 仰头方向。诊断用途；**否决判据已改用 [refRelVerdict]**：
     * 它是**平移无关**的，所以"身体动一下让整张脸平移"不会改变它，
     * 只有头真的俯仰（透视缩短）才会变 —— 实机 16 次仰头里 14 次方向一致、0 次相反。
     */
    @Volatile
    var refRelNoseDy: Float = 0f

    /**
     * v5.27：`rel` 参考点轨道**自己确认过的**方向（+1 仰头 / -1 点头 / 0 无结论），
     * 由服务每帧同步（读到的比本帧早一帧，正好相当于"上帧就已确认"）。
     *
     * 为什么不再直接看 [refRelNoseDy]：位移在动作**起手**那一刻必然≈0，
     * 「要求位移已经朝仰头方向动过」就等于在每次起手时先否掉一帧 —— v5.26 实测
     * `ref-veto-up` 2 分钟拦了 **89 次**，其中 20:30:40.665 那一帧与同毫秒的诊断行
     * `rel dy=-0.054 opt=ok`（方向完全一致）自相矛盾；20:30:40.5~41.2 用户做了一次
     * 3.5°→17.2° 的连续仰头，被 veto/eyes 连拦 1.3 秒**一次都没触发**，
     * 结果回摆末段反而被当成点头下滑 —— 用户看到的正是"仰头后延迟翻页/判反"。
     */
    @Volatile
    var refRelVerdict: Int = 0

    /** v5.27：`rel` 轨道当前阈值，否决时要求位移明显越过它（而不是只看符号）。 */
    @Volatile
    var refRelThreshold: Float = 0.025f

    /** 闭眼不可信期的截止时刻（v5.14），由 [eyeUnreliable] 维护。 */
    private var eyeUnreliableUntilMs = 0L

    /** 上一帧的有符号俯仰与时刻（v5.15），用来判断这次越阈值是"渐进"还是"跳变"。 */
    private var previousFramePitch = 0f
    private var previousFramePitchAtMs = 0L

    // ---- v5.28：扭头优先（与基准线无关的原始偏航摆幅）----

    private val yawSwingRing = FloatArray(YAW_SWING_SAMPLES)
    private val yawSwingAtMs = LongArray(YAW_SWING_SAMPLES)
    private var yawSwingIndex = 0
    private var yawSwingCount = 0

    /** 当前窗口内的原始偏航摆幅（度），见 [TURN_SWING_YIELD_DEG]。 */
    var yawSwingDeg: Float = 0f
        private set

    // ---- v5.28：位移判据（判定域）----

    /**
     * 上一帧「判定域」的取值（`signed`，可能是轻通道位移，也可能是相对基线量）。
     *
     * 必须以**判定域**记录：v5.17 那次「域混用」把原始俯仰和轻通道位移相减，
     * 结果方向整体翻了一次。位移判据同样不能跨域比。
     */
    private var lastDecisionValue = 0f
    private var lastDecisionWasLight = false

    /** 本次动作的起点（判定域），在 onset 声明时取上一帧的值。 */
    private var excursionStartValue = 0f
    private var excursionStartValid = false

    // ---- 运动起点的稳定值（v5.17）：轻通道的参考点 ----
    private val settleRing = FloatArray(SETTLED_WINDOW_SAMPLES)
    private var settleIndex = 0
    private var settleCount = 0

    /**
     * 运动开始前的稳定读数（v5.17），轻通道用它当参考点。
     *
     * 只有在头部稳定（连续 [SETTLED_WINDOW_SAMPLES] 帧峰峰值 < [SETTLED_RANGE_DEG]）时才更新，
     * 所以它天然是"这次动作的起点"，不会像 45 帧中位数基线那样滞后。
     */
    @Volatile
    var settledPitch: Float = 0f
        private set

    /**
     * `faceRatio` 的 EMA 平滑值（v5.9），距离档判定的依据；日志/界面显示用。
     *
     * 用平滑值而不是瞬时值切档，是为了让「手机在手里轻微前后晃」不会把档位切来切去。
     */
    @Volatile
    var smoothedFaceRatio: Float? = null
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

    /**
     * 本帧的点头/仰头是否因为「扭头信号明显更强」而让位（v5.8），仅用于诊断。
     *
     * 这是「近距离俯视扭头变上下滑」修复里仲裁机制的可观测结果：
     * 正常点头时它恒为 false，只有真的在扭头却同时带出俯仰时才会短暂为 true。
     */
    @Volatile
    var pitchYieldedToYaw: Boolean = false
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

    /**
     * 本帧算出的符号化读数，供下一帧估速度用。
     *
     * v5.8 起对外开放（`private set`）：诊断行要同时打出 yaw 与 pitch 的实时值，
     * 才能一眼分辨「扭头根本没到阈值」和「扭头带出了俯仰」。
     */
    @Volatile
    var lastSignedPitch = 0f
        private set

    @Volatile
    var lastSignedYaw = 0f
        private set

    @Synchronized
    fun reset() {
        pitchCount = 0
        yawCount = 0
        pitchIndex = 0
        yawIndex = 0
        calibrated = false
        baselineSettling = true
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
        // v5.9：距离档的平滑值与上下文缓冲一并清零，避免用旧脸型尺寸判断新距离。
        smoothedFaceRatio = null
        ctxCount = 0
        ctxIndex = 0
        // v5.12：晃动判定的缓冲也清空。
        shakeCount = 0
        shakeIndex = 0
        shakeEfficiency = 1f
        shakeReversals = 0
        // v5.14：闭眼不可信期也清零。
        eyeUnreliableUntilMs = 0L
        // v5.17：轻通道的运动起点重新学。
        settleCount = 0
        settleIndex = 0
        settledPitch = 0f
    }

    /** Throw away the learned baselines; the next samples establish new ones. */
    @Synchronized
    fun recalibrate() {
        pitchCount = 0
        yawCount = 0
        pitchIndex = 0
        yawIndex = 0
        calibrated = false
        // v5.31：基准线重建期间不允许触发（见 [BASELINE_SETTLE_SAMPLES]）。
        baselineSettling = true
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
        // v5.28：位移判据的起点一并作废，避免下一次动作拿旧起点算位移。
        excursionStartValid = false
        excursionStartValue = 0f
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
        val missingFor = if (justReturned) nowMs - faceMissingSinceMs else 0L
        faceMissingSinceMs = 0L
        lastFaceAtMs = nowMs
        lastAngleDeg = pitchDeg
        lastYawDeg = yawDeg

        // v5.9：人脸消失 ≥400ms 再回来时打一条 MARK 行。采集标定数据时用户用
        // 「手掌捂住摄像头 3 秒」当阶段标记，这一行让标记在日志里**不可能认错** ——
        // 上一轮只有 3 秒一条的汇总行，标记全靠采样碰运气，整段采集没法对齐时间轴。
        if (missingFor >= FACE_BACK_MARKER_MIN_MS) {
            Log.i(TAG, "MARK face-back lost=${missingFor}ms baselineInvalidated=$faceLostLongEnough")
        }

        if (justReturned && faceLostLongEnough) {
            // 脸回来了：整条基准线重新学，避免用被污染的窗口做判定。
            faceLostLongEnough = false
            Log.i(TAG, "face lost briefly — baseline invalidated, re-learning")
            pitchCount = 0
            yawCount = 0
            pitchIndex = 0
            yawIndex = 0
            calibrated = false
            // v5.31：重建期间不允许触发 —— 丢脸回来后的头几帧角度读数是脏的，
            // 拿它减一个还没稳定的中位数基准线会凭空造出 5° 的"位移"。
            baselineSettling = true
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

        // v5.31：窗口攒够了，解除"基准线重建期禁止触发"。
        if (baselineSettling && pitchCount >= BASELINE_SETTLE_SAMPLES) {
            baselineSettling = false
            Log.i(TAG, "baseline settled (pitchCount=$pitchCount) — head triggers re-armed")
        }

        // v5.9：距离档必须**最先**更新，因为静止峰峰值门限、速度门限、点头增益
        // 三者都读它。放在 updateStaticLock 之前，本帧的档位与本帧的判定才自洽。
        updateDistanceTier()

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

        // v5.31：基准线重建期一律不判定动作。这段窗口里角度读数与基准线都是脏的，
        // 判出来的"位移"不是用户的动作（证据见 [baselineSettling]）。
        if (baselineSettling) {
            clearExcursion()
            clearTurn()
            return
        }

        if (pitchReady) evaluatePitch(pitchDeg!!, yawDeg, nowMs, boost)
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
     * 更新唯一的距离档 [nearDistance]（v5.9）。
     *
     * 三步：**EMA 平滑 → 带滞回的阈值 → 记录切换**。
     *
     *  - **平滑**：`faceRatio` 是「脸框高 / 画面高」，手机在手里轻微前后晃就会抖 ±0.02，
     *    直接拿瞬时值切档会让档位（以及挂在它上面的灵敏度）逐帧乱跳 —— v5.9 之前的日志里
     *    `distMode` 正是这样在 near/far 之间反复横跳的。
     *  - **滞回**：进入用 [NEAR_ENTER_RATIO]、退出用更低的 [NEAR_EXIT_RATIO]，
     *    这样在门槛附近抖动不会来回切档。
     *  - **没脸时保持原判**：`faceRatio == null`（侧脸、遮挡）不改档，避免凭空翻转灵敏度。
     */
    private fun updateDistanceTier() {
        val raw = faceRatio ?: return
        val previous = smoothedFaceRatio
        val smoothed = if (previous == null) {
            raw
        } else {
            previous + FACE_RATIO_EMA_ALPHA * (raw - previous)
        }
        smoothedFaceRatio = smoothed

        val wasNear = nearDistance
        nearDistance = if (wasNear) smoothed > NEAR_EXIT_RATIO else smoothed >= NEAR_ENTER_RATIO
        if (nearDistance == wasNear) return

        // 切档必须留痕：这一行同时把「切档后本帧实际生效的三个量」打出来，
        // 验证 v5.9 修复时可以直接对照 speedGate / staticRange / nodBoost 是否跟着变。
        val boost = distanceBoost()
        Log.i(
            TAG,
            "distance tier: ${if (wasNear) "near" else "mid/far"} -> " +
                "${if (nearDistance) "near" else "mid/far"} " +
                "(faceRatio raw=${"%.2f".format(raw)} smoothed=${"%.2f".format(smoothed)} " +
                "enter=$NEAR_ENTER_RATIO exit=$NEAR_EXIT_RATIO) -> " +
                "boost=×${"%.2f".format(boost)} " +
                "speedGate=${"%.4f".format(MIN_PITCH_VELOCITY * boost)}°/ms " +
                "staticRange=${"%.1f".format(STATIC_RANGE_DEG * boost)}° " +
                "nodDownThreshold=${"%.1f".format(thresholdDeg * if (nearDistance) NEAR_DOWN_NOD_BOOST else 1f)}°",
        )
    }

    /**
     * 距离放大系数：脸离得越近，同样的头部微晃在画面里折算出的角度越大（v5.2 起）。
     *
     * 实测数据：50cm 时 `faceRatio≈0.35` 一切正常；30cm 时 `faceRatio≈0.50`，静止也会误触。
     * 所以近距离下静止门限与速度门限都要同步提高——**只提高一个是不够的**：抬高幅度门限
     * 挡不住偶尔偏大的噪声样本，抬高速度门限才能把它们区分开（噪声没有速度）。
     *
     * v5.9：近距离不再自己判 `faceRatio >= 0.55`，而是读唯一的距离档 [nearDistance]
     * （带 EMA 与回差，门槛 0.46）。这样「灵敏度按近距离给」和「防护按近距离给」
     * 用的是同一个真值，不可能再出现空档或逐帧抖动。
     */
    private fun distanceBoost(): Float = when {
        nearDistance -> 2f
        (smoothedFaceRatio ?: faceRatio ?: 0f) >= MID_FACE_RATIO -> 1.35f
        else -> 1f
    }

    /**
     * **偏航**（扭头）阈值 = 用户设定值，**与距离解耦**（v5.8）。
     *
     * ## v5.8 修的是什么（「近距离俯视扭头不好使」）
     *
     * v5.4 起这里乘了距离系数，近距离（`faceRatio >= 0.45`）时**翻倍**：
     * 用户设的「中 20°」在 30cm 处实际是 **40°**。实机日志正好抓在门限上：
     *
     * ```
     * 01:57:37.093 turn triggered yaw=40.5° latency=102ms (fast)
     * GazeDiag: faceRatio=0.51 dist=中 ... yawTh=40.0° speedGate=0.0240°/ms
     * ```
     *
     * 而用户的扭头幅度就是 40~52° —— **刚好卡在门上，稍微小一点就完全不触发**。
     *
     * 这和 v5.5 在俯仰轴上修掉的是**同一个错误**：当年也想用"放大幅度阈值"来挡近距离噪声，
     * 后来证明那是错的方法（噪声有幅度但没有速度，该挡它的是速度门限）。
     * 俯仰轴改过之后没有回头改偏航轴，这条就一直留着。
     *
     * 所以现在两个轴统一：**幅度阈值 = 用户设定值**（俯仰再乘单向的近距离点头增益），
     * 距离缩放只作用于**速度门限**与静止门限。远距离行为完全不变（系数本来就是 1.0），
     * 只有"近距离够不到"这个毛病被修掉。
     */
    private fun turnThresholdNow(): Float = turnThresholdDeg

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
        val nearBoost = nearNodDownBoost(signedPitch)
        // v5.6：把"这一帧实际生效的距离增益"记下来给诊断行用。
        // 之前诊断打的是 nodDownGazeBoost（俯视增益常量），近距离下会显示 1.00，
        // 而阈值其实已经乘过 0.68 —— 字段名与实际不符，排查时会误判功能没生效。
        lastAppliedNodBoost = nearBoost

        // v5.14：v5.4 的俯视增益（nodDownGazeBoost）必须**单向**，而且不能与近距离俯视档叠乘。
        //
        // 两个都是 v5.14 修掉的实机缺陷：
        //
        //  ① **方向泄漏**：它以前乘在**共用**的俯仰阈值上，于是仰头方向也吃到了 0.75。
        //     实机日志里能看到 `tiltUp triggered ... threshold=4.5°`，而用户设定的是 6.0°
        //     —— 这与 v5.7 修掉的是同一个错误，只是当年只改了"近距离增益"那一条，
        //     这条基于**相对基准线偏移**的俯视增益一直漏着。仰头阈值被偷偷压低 25%，
        //     正是"被动仰视 4.5~6° 就误触"的来源。
        //
        //  ② **状态抖动**：近距离俯视档（0.42）已经把"俯视"这件事用**可靠判据**
        //     （基准俯仰角，见 DOWN_POSTURE_BASE_DEG）算进去了；再叠乘这条基于相对偏移的
        //     俯视增益，阈值就会在那条状态开关时于 **2.25° 与 3.0° 之间来回跳**
        //     （实测 337 帧里有 133 帧处于该状态），手感时灵时不灵。
        //
        // 所以：只有**没有**吃到任何近距离档（即远距离、或近距离平视）时，
        // 才让 v5.4 那条俯视增益作用于低头方向；仰头方向永远不吃它。
        val gazeBoost = if (signedPitch < 0f && nearBoost == 1f) nodDownGazeBoost else 1f

        // v5.25：**仰头**方向在「近距离 + 俯视」时也开一档（3.8°）。
        // 用户复测 v5.24：「仰头太不灵敏了，我仰头了要等 0.4~0.5 秒才触发」——
        // 实机 16 次仰头的中位延迟正好 202ms、3 次 ≥500ms，根因是阈值 6.0° 太高。
        val upBoost = if (signedPitch > 0f && nearDistance && lookingDown) NEAR_LOOKUP_BOOST else 1f
        lastAppliedNodBoost = nearBoost * upBoost
        return thresholdDeg * (gazeBoost * nearBoost * upBoost)
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

    private fun evaluatePitch(
        pitchDeg: Float,
        yawDeg: Float?,
        nowMs: Long,
        distanceBoost: Float,
    ) {
        val delta = pitchDeg - baselineDeg
        val signedPitch = if (invertPitch) -delta else delta
        // v5.8：把本帧的偏航也翻正，供方向仲裁判断"用户是不是正在扭头"。
        // 必须在这里算，因为俯仰是先于偏航评估的 —— 用上一帧的偏航会让仲裁失灵。
        // 仲裁只关心速度大小，所以符号沿用 evaluateYaw 的翻正规则即可（镜像 + 可选用户反转）。
        val yawDelta = yawDeg?.minus(baselineYawDeg)
        val signedYawNow = yawDelta?.let { if (invertYaw) it else -it }

        // ---- v5.17：轻通道改用「相对**运动起点**的位移」判方向与幅度 ----
        //
        // 用户报的「轻轻点头，没有下滑，倒是变成了**上滑**」，根因就在基准线上：
        //
        // ```
        // 04:18:23.300  rawPitch=11.28°  base=5.12°  → signed=-6.2° → nodDown（下滑）✓
        // 04:18:25.316  rawPitch=2.82°   base=9.52°  → signed=+6.7° → tiltUp（上滑）✗
        // ```
        //
        // `baselineDeg` 是 45 帧中位数，**本身在追用户的姿势**（上面 2 秒内就从 5.12 跳到 9.52，
        // 因为中途丢过一次脸、基线重学）。轻点头的位移只有 5~8°，与滞后的基线一比，
        // "起点落在基线的哪一侧"就决定了方向 —— **小幅度动作必然判反**。
        //
        // 所以轻通道（近距离 + 俯视）改用 [settledPitch]（运动开始前的稳定值）作参考点：
        // 位移 = 当前值 − 稳定值，方向 = 位移符号，幅度 = 位移大小。
        // 这样"点头 = 向低头方向移动 2.5°"就是一句能兑现的话，与基线漂到哪无关。
        //
        // 只作用于轻通道：远距离、仰头方向、以及回中锁定/仲裁/晃动等其它逻辑
        // 仍然用原来相对基线的量，行为不变。
        // ⚠️ v5.17 初版这里写成了 `pitchDeg - settledPitch`：**域混用**。
        // `pitchDeg` 是原始俯仰角，而 `settledPitch` 是用 `signedPitch` 记下来的**有符号值**
        // （已经过 invertPitch 翻转、且扣掉了基准线）。两者相减等于把方向整体翻了一次、
        // 还叠上基准线偏移，于是 v5.17 初版"轻点头全变上滑、静止时疯狂上拉"。
        // 位移必须在**同一个域**里算 —— 都用有符号值。
        val signedLight = signedPitch - settledPitch
        // v5.26：位移超过上限就不走轻通道 —— 参考点在换姿势后可能过期，
        // 实测出现过 29° 的"位移"（其实是换姿势）被当成点头。
        val useLight = nearDistance && lookingDown && signedLight < 0f &&
            abs(signedLight) <= LIGHT_MAX_DISPLACEMENT_DEG
        // 判定用的有符号量与幅度：轻通道用位移，其余用相对基线。
        val signed = if (useLight) signedLight else signedPitch
        val magnitude = abs(signed)
        // 静止锁定期间的阈值会被放大，用来压掉「一动不动也触发」的噪声。
        // 静止锁定期间的阈值会被放大，用来压掉「一动不动也触发」的噪声。
        // v5.5：俯仰幅度阈值**不乘**距离系数（距离缩放交给静止门限与速度门限），
        //       只乘静止锁定与俯视增益，这样近距离点头不再需要两倍幅度。
        // v5.7：近距离增益改成**单向**，必须把方向传进去——否则仰头也会被压低，
        //       造成「近距离仰视误触」（实测 tiltUp 在 4.2° 就触发过）。
        //
        // 注意 lastAppliedNodBoost 只能在**本方向**算完之后赋值：诊断行要与 pitchTh 自洽，
        // 所以先把反方向阈值算完，最后才记录本帧实际生效的增益。
        val threshold = pitchThresholdNow(signed)
        pitchThresholdDeg = threshold
        // 反方向阈值一并算出来给诊断行：用户直接能看到"低头 5.4° / 抬头 8.0°"。
        pitchThresholdUpDeg = pitchThresholdNow(-signed)
        lastAppliedNodBoost = nearNodDownBoost(signed)
        lastSignedPitch = signedPitch

        // v5.13：轻点头通道的生效条件与 ×0.42 增益**完全一致**（近距离 + 俯视 + 低头**位移**），
        // 所以"阈值被压到 2.5°"和"速度门槛降到 0.009"永远同时生效，不会只松一半。
        val lightNodAllowed = useLight

        // ---- v5.9：逐帧上下文（必须在任何提前返回**之前**维护，否则轨迹会缺帧）----
        pushContext(signedPitch, signedYawNow, nowMs)
        // v5.28：原始偏航摆幅（扭头优先判据），同样必须在提前返回之前维护。
        pushYawSwing(yawDeg, nowMs)
        // v5.12：晃动判定的缓冲同理，必须在提前返回之前维护。
        pushShakeSample(signedPitch)
        // v5.17：维护"运动起点"的稳定值（轻通道的参考点）。
        updateSettled(signedPitch)

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
            // v5.27：把"还在动"传给锁定逻辑 —— 解除必须建立在真的停住了之上（见 [RECENTER_MIN_LOCK_MS]）。
            updateRecenterLock(signedPitch, threshold, sameDirection, velocity >= speedGate, nowMs)
        }

        // v5.27：强候选 = 幅度已经明显越过阈值，见 [STRONG_MARGIN_DEG]。
        // 必须在下面的 run 之前算出来，好让软性门（闭眼/保持时间/最低速度）对它让路。
        // **只在近距离档生效**：用户的反馈只针对"近距离俯视"，远距离那一套（v5.16 之后
        // 一直好用）一个字都不改。
        val strong = nearDistance && magnitude >= threshold + STRONG_MARGIN_DEG

        // v5.28：位移判据要用的"上一帧判定域取值"。必须在 run 之前抓住，
        // 因为 onset 声明时取的就是"上一点"（这样单帧大幅动作也算走过了位移）。
        val previousDecisionValue = lastDecisionValue
        val previousDecisionWasLight = lastDecisionWasLight
        lastDecisionValue = signed
        lastDecisionWasLight = useLight

        // 所有「不触发」的原因都收敛到这一个变量，便于日志里直接说明为什么没触发。
        val reject: String? = run {
            if (magnitude < threshold * ONSET_FRACTION) return@run "below-onset"
            // v5.28：**头正在左右摆 → 俯仰一律让位** —— 见 [TURN_SWING_YIELD_DEG]。
            // 放在最前面（onset 声明之前）是有意的：扭头期间俯仰通道连动作都不该开始记，
            // 否则扭头一结束，那个"已经越阈值"的残留姿势会立刻补一次上下滑。
            if (nearDistance && yawSwingDeg >= TURN_SWING_YIELD_DEG) {
                return@run "yaw-swing-arbitration"
            }
            if (recenterLockDirection != 0 && recenterLocked &&
                !((signedPitch > 0 && recenterLockDirection > 0) ||
                    (signedPitch < 0 && recenterLockDirection < 0))
            ) {
                return@run "recenter-lock"
            }
            if (pitchOnsetAtMs == 0L) {
                pitchOnsetAtMs = nowMs
                // v5.28：运动起点 = 上一帧的判定域取值（同域，见 [lastDecisionValue]）。
                excursionStartValue = previousDecisionValue
                excursionStartValid = useLight == previousDecisionWasLight
            }
            if (magnitude < threshold) return@run "below-threshold"
            // v5.28：**位移判据** —— 见 [MIN_TRAVEL_FRACTION]。
            // 姿势本来就压在阈值上时，一点晃动就能"越阈值"，但它没有位移。
            if (nearDistance && excursionStartValid &&
                abs(signed - excursionStartValue) < threshold * MIN_TRAVEL_FRACTION
            ) {
                return@run "no-travel"
            }
            // v5.25：**仰头方向的参考点确认**（v5.27 改用证人自己的结论，见 [refRelVerdict]）。
            // 身体动一下只会让整张脸在画面里平移，不会改变"鼻子在脸内部的相对位置"，
            // 所以要求参考点通道给出**仰头方向的结论**，才能放行仰头。
            // v5.26：**对称地给低头方向也加上**。用户复测 v5.25 报「仰头有时候会判定下拉」——
            // 逐个核对发现 17 次点头里有 4 次证人方向相反，那几次正是"仰头被判成下拉"。
            // v5.27：判据从「看原始位移 refRelNoseDy」改成「看证人自己确认的结论 refRelVerdict」——
            // 位移在起手瞬间必然≈0，旧判据等于每次起手先否掉一帧（实测 2 分钟误否 89 次，
            // 其中整整一次连续仰头被否到完全没触发）。现在只有证人**确认了相反方向**
            // 且位移明显越过它自己的阈值时才否决，起手帧与动作窗外的噪声都不再参与。
            if (nearDistance && refRelVerdict != 0 && abs(refRelNoseDy) >= refRelThreshold) {
                if (signed > 0f && refRelVerdict < 0) return@run "ref-veto-up"
                if (signed < 0f && refRelVerdict > 0) return@run "ref-veto-down"
            }
            // v5.14：闭眼（哪怕一帧浅闭）之后的姿态读数不可信 —— 见 EYE_UNRELIABLE_MS。
            // v5.27：**只对边缘候选生效** —— 仰头本身会把眼皮挤成半闭（实测连续 4 帧 eyeDip），
            // 幅度已经明确越过阈值的候选再被它拦住，用户看到的就是"仰头不翻页"。
            // 注意这里仍然照常调用 eyeUnreliable()（它负责维护恢复期），只是不再据此否决强候选。
            val eyeBlocked = eyeUnreliable(nowMs)
            if (eyeBlocked && !strong) return@run "eyes-unreliable"
            // v5.12 晃动过滤：地铁/手抖是**来回抖**（走过的路远大于净位移），
            // 即使顶穿了阈值也不算动作。必须在压低阈值（2.5°）之后有它兜底。
            if (isShaking()) {
                clearExcursion()
                return@run "shake"
            }
            // v5.13：手机自己被顿了一下（急停/急刹/被撞）—— 那是整个人在动，不是头在转。
            // 摄像头区分不了，加速度计分得出来（见 PhoneMotionMonitor）。
            if (phoneMoving) return@run "phone-motion"
            if (pitchReachedAtMs == 0L) {
                pitchReachedAtMs = nowMs
                val riseMs = nowMs - pitchOnsetAtMs
                // v5.28：近距离档要求"突然"（见 [NEAR_SUDDEN_RISE_MS]），远距离仍用用户设置的动作窗口。
                val window = if (nearDistance) minOf(motionWindowMs, NEAR_SUDDEN_RISE_MS) else motionWindowMs
                pitchArmed = riseMs <= window
                if (!pitchArmed) Log.i(TAG, "ignored slow lean: rise ${riseMs}ms > ${window}ms")
            }
            if (!pitchArmed) return@run "slow-rise"
            // v5.15：这次越阈值是「渐进动作」还是「单帧跳变」？见 JUMP_CONFIRM_MS。
            // 渐进动作（前一帧已经在阈值以上方向移动）**零延迟**放行，
            // 只有跳变型才要求再撑过一帧 —— 于是不会重犯 v5.9 拖慢全部动作的错。
            val cameFromRamp = nowMs - previousFramePitchAtMs <= JUMP_RAMP_WINDOW_MS &&
                abs(previousFramePitch) >= threshold * ONSET_FRACTION &&
                // v5.17：还要求"台阶"不大。实测 ⑥ 静止段有两次误触是
                // 「抖动 + 单帧跳 5~10°」，前一帧恰好已在阈值附近（被晃动判据拦下了），
                // 于是"渐进"成立、跳变确认没要求再撑一帧 —— 加上台阶限制就堵住了。
                // 代价只是"极快的大幅动作"也要多撑一帧（约 90ms），轻点头不受影响
                // （实测轻点头每帧只走 1~2°）。
                abs(signedPitch - previousFramePitch) <= JUMP_MAX_RAMP_STEP_DEG
            if (!cameFromRamp && nowMs - pitchReachedAtMs < JUMP_CONFIRM_MS) {
                return@run "jump-confirm"
            }
            val fast = velocity >= FAST_PITCH_VELOCITY
            // v5.13：近距离俯视的**轻点头通道** —— 见 LIGHT_NOD_FAST_VELOCITY 的实机数据。
            val light = !fast && lightNodAllowed && velocity >= LIGHT_NOD_FAST_VELOCITY
            // v5.27：强候选不再等保持时间/轻通道确认/最低速度 —— 这三道门都是为了
            // "幅度刚好压在阈值上"的候选防噪声，而强候选的幅度已经明确越过阈值
            // （见 [STRONG_MARGIN_DEG] 的实机依据）。实测这三道门各会拖掉一帧（63~116ms）。
            // 前面还有晃动判据、手机运动判据、跳变确认三道门，一个都没去掉。
            if (!fast && !light && !strong && nowMs - pitchReachedAtMs < requiredHoldMs()) {
                return@run "hold-not-met"
            }
            // 轻通道必须多撑过一帧（单帧跳变会在下一帧掉回 below-threshold，永远过不来）。
            if (light && !strong && nowMs - pitchReachedAtMs < LIGHT_NOD_CONFIRM_MS) {
                return@run "light-confirm"
            }
            // 最低速度门限：噪声有幅度但没有速度，所以再加一道与幅度无关的门。
            if (!fast && !light && !strong && velocity < speedGate) return@run "speed-gate"
            // v5.8 方向仲裁：偏航正在明显转动 → 这次让位给扭头。
            // 近距离俯视扭头会同时带出一个俯仰分量（实测 ±3~±10°），而俯仰阈值被单向
            // 增益压到 0.68 倍（4.1°/5.4°），不让位的话"想扭头"永远先变成上下滑。
            // 真正的点头不带偏航角速度，所以对纯点头零影响。
            if (signedYawNow != null && isYawMovingNow(signedYawNow, nowMs, velocity)) {
                return@run "yaw-dominant-arbitration"
            }
            null
        }

        pitchYieldedToYaw = reject == "yaw-dominant-arbitration" || reject == "yaw-swing-arbitration"

        updatePostureRecenter(pitchDeg, baselineDeg, abs(signedPitch), threshold, reject, nowMs)

        if (reject != null) {
            // 只在「看起来像一次动作」时才记录，避免每帧刷屏。
            if (magnitude >= threshold * 0.8f) {
                // v5.7：日志要能一眼看出方向 —— 原来两个方向都打 "nodDown candidate"，
                // 排查「近距离仰视误触」时会把仰头候选误读成点头。
                val dir = if (signed < 0f) "nodDown" else "tiltUp"
                Log.i(
                    TAG,
                    "$dir candidate rejected: pitch=${"%.1f".format(signed)}° " +
                        "speed=${"%.4f".format(velocity)}°/ms gate=${"%.4f".format(speedGate)}°/ms " +
                        "threshold=${"%.1f".format(threshold)}° " +
                        "dist=${if (nearDistance) "near" else "far"} " +
                        "posture=${if (lookingDown) "down" else "flat"} " +
                        "shake=${"%.2f".format(shakeEfficiency)} rev=$shakeReversals " +
                        "boost=${"%.2f".format(nearNodDownBoost(signedPitch))} " +
                        // v5.27：强候选标记 + 证人自己的结论（判据是否用得上，一眼可核对）。
                        "strong=${if (strong) 1 else 0} " +
                        "refV=$refRelVerdict/${"%+.3f".format(refRelNoseDy)}" +
                        " thr=${"%.3f".format(refRelThreshold)} " +
                        // v5.28：扭头摆幅 + 已走过的位移（两个新判据的输入，可直接核对）。
                        "yawSwing=${"%.1f".format(yawSwingDeg)} " +
                        "travel=${if (excursionStartValid) "%.2f".format(abs(signed - excursionStartValue)) else "-"}" +
                        " reason=$reject",
                )
            }
            // 只有「真的回到静止」才清掉进行中的动作；被锁或速度不足时保留计时段，
            // 免得用户动作做到一半就被重置掉。
            if (reject == "below-onset") clearExcursion()
            // v5.29：**闭眼期这一帧的读数不可信，不能当成"上一帧在动"的证据。**
            // 因果链见 [EYE_UNRELIABLE_MS]：闭眼会让 ML Kit 的姿态跳一下。
            // v5.27 的强候选豁免让那一帧能直接触发也就罢了，但它还会被记成 `previousFramePitch`，
            // 于是下一帧的跳变确认看到"前一帧已经越阈值"→ 判定为渐进动作 → 零延迟放行 ✗。
            // 实测（21:00:29.848，用户报"人没动"）：
            //   ctx … -123ms|-0.4 → -72ms|-3.5 → 0ms|-5.4   （静止 2 秒后突然单帧跳 3.1°）
            //   29.768 那一帧已经被 eyes-unreliable 拒掉，却正是它让 29.848 这一帧"看起来是渐进"。
            // 所以闭眼帧既不作证据、也不留速度样本 —— 下一帧必须自己站稳（跳变确认会等一帧）。
            if (reject == "eyes-unreliable") return
            updatePitchVelocitySample(signedPitch, nowMs)
            previousFramePitch = signedPitch
            previousFramePitchAtMs = nowMs
            return
        }

        // 从「开始偏离」到「真正触发」的总耗时，用户能据此判断手感是否对等。
        val latencyMs = nowMs - pitchOnsetAtMs
        val fast = velocity >= FAST_PITCH_VELOCITY
        triggerCount++
        cooldownUntilMs = nowMs + cooldownMs
        clearExcursion()
        // v5.15：触发路径同样要维护"上一帧"的快照，否则冷却结束后的第一帧会被当成跳变。
        previousFramePitch = signedPitch
        previousFramePitchAtMs = nowMs

        // 回中锁定（v5.0）：记下这次的方向，反方向要等头部回到中性区才放行，
        // 这样「仰头之后把头放回去」不会被当成一次点头。
        // v5.27：兜底不再是"400ms 无条件解锁" —— 改成"停住 + 已回稳/已回中性区"（见 RECENTER_MIN_LOCK_MS）。
        recenterLockDirection = if (signed < 0) -1 else 1
        recenterLocked = true
        recenterNeutralSinceMs = 0L
        recenterLockedAtMs = nowMs

        val staticNote = if (staticLocked) " · 静止锁定 ${"%.1f".format(threshold)}°" else ""
        val how = if (fast) "fast" else "held"
        // v5.9：每次触发都把前约 1.2 秒的原始读数打出来（见 CONTEXT_SAMPLES 的说明）。
        val ctxNote = " — " + contextDump()
        // v5.28：两个新判据的输入 + 晃动指标（触发线也要留痕，否则"这次为什么算数"只能靠猜）。
        val judgeNote = " travel=${if (excursionStartValid) "%.2f".format(abs(signed - excursionStartValue)) else "-"}" +
            " yawSwing=${"%.1f".format(yawSwingDeg)}" +
            " shake=${"%.2f".format(shakeEfficiency)} rev=$shakeReversals"
        val boosted = nearDistance
        val boostNote = if (boosted) {
            " (boosted, dist=near posture=${if (lookingDown) "down" else "flat"} " +
                "threshold=${"%.1f".format(threshold)} " +
                // v5.17：轻通道打出"起点 → 终点"，方向与幅度一眼可核对。
                "light=${if (useLight) "yes(${"%.1f".format(settledPitch)}→${"%.1f".format(signed)})" else "no"}°" +
                // v5.27：这次是靠"强候选"（免等软性门）放行的吗？看日志就能核对手感来源。
                " strong=${if (strong) "yes" else "no"})"
        } else {
            ""
        }
        if (signed < 0) {
            Log.i(
                TAG,
                "nodDown triggered$boostNote pitch=${"%.1f".format(signed)}° " +
                    "latency=${latencyMs}ms ($how, v=${"%.3f".format(velocity)}°/ms)$judgeNote" +
                    "$staticNote$ctxNote",
            )
            onEvent(
                HeadEvent.NodDown(
                    signed,
                    "低头 ${signed.toInt()}°（${latencyMs}ms 内触发）",
                ),
            )
        } else {
            // v5.7：仰头触发也把完整判据打出来（用户明确要求的格式），
            // 这样"近距离仰视误触"的每一次误触都能直接读出当时的速度与阈值。
            Log.i(
                TAG,
                "tiltUp triggered: pitch=${"%.1f".format(signed)}° " +
                    "speed=${"%.4f".format(velocity)}°/ms threshold=${"%.1f".format(threshold)}° " +
                    "dist=${if (nearDistance) "near" else "mid/far"} " +
                    "boost=${"%.2f".format(nearNodDownBoost(signed))} " +
                    "latency=${latencyMs}ms ($how)$judgeNote$staticNote$ctxNote",
            )
            onEvent(
                HeadEvent.TiltUp(
                    signed,
                    "仰头 ${signed.toInt()}°（${latencyMs}ms 内触发）",
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
        // 距离档已由 [updateDistanceTier] 在每帧开头更新（v5.9）；这里只做姿态识别。

        // ---- v5.12：俯视判据改用**基准俯仰角**（绝对姿态），不再用 chinRatio ----
        // 必须先做，且不依赖 chinRatio —— 基准俯仰角是基线学习中位数的输出，与它无关。
        // 依据见 DOWN_POSTURE_BASE_DEG：实测 30cm 俯视 base=7.8~14.4、50cm 平视 base=0~2。
        val wasDown = lookingDown
        lookingDown = if (wasDown) {
            baselineDeg > DOWN_POSTURE_EXIT_DEG
        } else {
            baselineDeg >= DOWN_POSTURE_BASE_DEG
        }

        // chinRatio 保留为**纯诊断**（v5.6 已证明它主要在反映距离，不接任何判定）。
        val raw = chinRatio
        if (raw == null) {
            postureLabel = if (lookingDown) "俯视(旧)" else "平视(旧)"
            reportPostureChange(wasDown, null)
            return
        }

        val previous = smoothedChinRatio
        val smoothed = if (previous == null) raw else previous + POSTURE_EMA_ALPHA * (raw - previous)
        smoothedChinRatio = smoothed
        pushChinSample(smoothed)
        reportPostureChange(wasDown, smoothed)
    }

    /** 姿态标签与切换日志（v5.12 拆出来，因为俯视判据已与 chinRatio 解耦）。 */
    private fun reportPostureChange(wasDown: Boolean, smoothedChinRatio: Float?) {
        postureLabel = if (lookingDown) "down" else "flat"
        if (lookingDown == wasDown) return
        val ratioText = smoothedChinRatio?.let { " chinRatio=${"%.3f".format(it)}" } ?: ""
        Log.i(
            TAG,
            "posture changed: ${if (wasDown) "down" else "flat"} -> $postureLabel " +
                "(baseline=${"%.1f".format(baselineDeg)}° baseThreshold=$DOWN_POSTURE_BASE_DEG°" +
                "$ratioText) — " +
                "近距离俯视点头增益 ${if (lookingDown) "×$NEAR_LOOKDOWN_NOD_BOOST" else "off"}",
        )
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
    private fun nearNodDownBoost(signedPitch: Float): Float = when {
        // 仰头方向一律不动（v5.7：被动仰视 4~7° 不能被放行）。
        signedPitch >= 0f -> 1f
        // v5.12：**近距离 + 俯视**再给一档 —— 见 NEAR_LOOKDOWN_NOD_BOOST 的实机数据。
        nearDistance && lookingDown -> NEAR_LOOKDOWN_NOD_BOOST
        // v5.6 的近距离档：脸大但姿态不是俯视时仍然省力（远距离完全不受影响）。
        nearDistance -> NEAR_DOWN_NOD_BOOST
        else -> 1f
    }

    /**
     * 方向仲裁：本帧的偏航信号是否明显强于俯仰信号（v5.8）。
     *
     * ## 为什么需要它（「近距离俯视时扭头变成上下滑」）
     *
     * 近距离俯视时，俯仰阈值被单向增益压到 0.68 倍（灵敏度 6° → 4.1°，8° → 5.4°），
     * 而真人在俯视姿态下扭头，头部并不会纯绕 Y 轴旋转 —— 脖子带着一个俯仰分量，
     * 实测就有 −3~−7°。扭头这件动作在俯仰轴上长得**和点头一模一样**。
     * 而 [onHeadPose] 里点头是先于扭头评估的，于是它抢先一步把翻页变成了上下滑。
     * 实机日志：
     *
     * ```
     * 02:00:25.848 nodDown triggered pitch=-12.0°
     * 02:00:25.850 turnR candidate rejected: yaw=23.5° ... reason=below-threshold
     * ```
     *
     * ## 判据为什么**只看速度**
     *
     * 关键区别不在幅度，而在**角速度归属**：
     *
     *  - 真正的扭头是绕 Y 轴转动 → **偏航角速度必然明显**（实测 0.13~0.25°/ms）；
     *  - 真正的点头是绕 X 轴转动 → **偏航角速度几乎为零**；
     *  - 而"扭头带出的俯仰分量"在偏航轴上一定带着那个转动速度。
     *
     * ⚠️ 所以**绝不能加幅度兜底判据**。第一版就是加了 `yaw >= 阈值×0.6` 才出的错：
     * 扭头时偏航幅度本来就大，于是"我正在扭头"被误判成"俯仰更强"，反而把合格的扭头
     * 挡掉了 —— 日志里的 `yaw=30.7° speed=0.2481°/ms reason=pitch-dominant-arbitration`
     * 就是它干的。**幅度在这里没有鉴别力，速度才有。**
     *
     * 门限取 [MIN_YAW_VELOCITY] 的 [ARBITRATION_VELOCITY_FRACTION] 倍而不是它本身：
     * 这个值已经远高于静止噪声（噪声没有速度），又远低于真实扭头（0.13~0.25），
     * 所以在"动作刚起"的那一帧就能认出这是扭头，不用等到幅度堆起来。
     *
     * @param signedYawNow 本帧刚算出的有符号偏航偏移；null 表示这一帧没有偏航读数
     */
    /**
     * 偏航是否正在明显转动（v5.8 引入，v5.11 收紧）：所有**俯仰候选**的否决判据。
     *
     * ## v5.11 修的是什么（「点头要更大的角度才触发」）
     *
     * v5.8 的判据**只有一条绝对速度门限**：
     *
     * ```kotlin
     * yawVelocity >= MIN_YAW_VELOCITY * ARBITRATION_VELOCITY_FRACTION   // 0.015 × 0.4 = 0.006°/ms
     * ```
     *
     * 0.006°/ms 是 6°/秒 —— 而 30cm 处偏航读数的**正常抖动**（±1~3°）在约 11fps 下折算出来
     * 就是 **0.01~0.03°/ms**，轻松越过这条线。于是「我在扭头」被误判，点头让位。
     * 实机日志（v510-verify.log，用户明确在点头）：
     *
     * ```
     * 03:10:35.990 nodDown candidate rejected: pitch=-7.7°  reason=yaw-dominant-arbitration
     * 03:10:36.065 nodDown candidate rejected: pitch=-12.2° reason=yaw-dominant-arbitration
     * 03:10:38.915 nodDown candidate rejected: pitch=-13.8° reason=yaw-dominant-arbitration
     * 03:10:38.985 nodDown candidate rejected: pitch=-15.2° reason=yaw-dominant-arbitration
     * 03:10:39.062 nodDown triggered                      pitch=-17.3°   ← 加大力气才成
     * ```
     *
     * 阶段④ 一共 **39 次**俯仰候选被这条规则拒掉（点头 19、仰头 18），是最大的一类拒绝。
     *
     * ## 三条判据，缺一不可
     *
     * 函数叫「偏航占优」，可 v5.8 从来没有比较过两个轴的大小。现在补齐：
     *
     *  1. **偏航确实在动**（[ARBITRATION_VELOCITY_FRACTION] × 最低速度门限，保留 v5.8 的原始判据）；
     *  2. **偏航强过俯仰**（[ARBITRATION_DOMINANCE_RATIO]）—— 这才是"占优"的字面含义。
     *     实测：真实扭头 0.13~0.25°/ms，扭头带出的俯仰分量只有约 0.02°/ms；
     *     而点头时俯仰 0.04~0.13°/ms、偏航抖动 0.01~0.02°/ms。**速度的比较在两个方向上都有鉴别力**；
     *  3. **头部确实偏到轴外**（[ARBITRATION_YAW_FRACTION] × 扭头阈值）—— 真实扭头
     *     实测 23~45°（阈值 20°），而点头时偏航抖动只有 ±1~3°。
     *
     * ⚠️ 第 3 条是**闸门**，不是 v5.8 文档里警告过的"幅度兜底判据"：那次的错误是拿幅度
     * **替代**速度判据（`yaw >= 阈值×0.6` 就当"俯仰更强"），结果把合格扭头挡掉了。
     * 这里是"速度已经成立之后，再要求幅度也成立"，两条同时满足才让位，
     * 所以真正的扭头（幅度 23~45°）照样让位，而姿态抖动（幅度 ±1~3°）不再误伤点头。
     *
     * @param currentYaw 本帧的有符号偏航偏移
     * @param pitchSpeed 本帧的俯仰角速度（已在 evaluatePitch 里算过，直接复用避免重复计算）
     */
    private fun isYawMovingNow(currentYaw: Float, nowMs: Long, pitchSpeed: Float): Boolean {
        val yawSpeed = yawVelocityWith(currentYaw, nowMs)
        if (yawSpeed < MIN_YAW_VELOCITY * ARBITRATION_VELOCITY_FRACTION) return false
        if (yawSpeed < pitchSpeed * ARBITRATION_DOMINANCE_RATIO) return false
        return abs(currentYaw) >= turnThresholdDeg * ARBITRATION_YAW_FRACTION
    }

    /** 扭头候选让位给俯仰。同样只看速度——幅度在两个轴之间没有鉴别力。 */
    private fun isPitchDominantOverYaw(nowMs: Long): Boolean =
        abs(pitchVelocity(nowMs)) >= MIN_PITCH_VELOCITY * ARBITRATION_VELOCITY_FRACTION

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
     * ## v5.27：解除条件从"到点就放"改成"停住了才放"
     *
     * v5.2 的兜底是**无条件**的 400ms（`RECENTER_MAX_LOCK_MS`）：时间一到就解锁，
     * 完全不看头在哪、也不看还在不在动。实机后果见 [RECENTER_MIN_LOCK_MS] 的说明 ——
     * 仰头之后把头放回来的那一次下行回摆，末段被当成一次"点头"多翻一页。
     *
     * 现在解除必须**先满足"不在动"**（`moving == false`，由调用方用速度判据给出），再看：
     *  1. 回到中性区并稳定 [RECENTER_SETTLE_MS]（原逻辑）；
     *  2. **或**头就停在自己当前的姿势上（`|signed − settledPitch| ≤ 中性区`）——
     *     这是"姿势偏置"的出口：基准线偏了 6.5° 时条件 1 永远不成立，
     *     但 `settledPitch` 跟着真实姿势走，差值很小，锁照样解得开；
     *  3. [RECENTER_HARD_CAP_MS] 硬上限：停住超过 3 秒一律解锁，保证不可能永久屏蔽。
     *
     * 两条主条件都要求锁已持续 [RECENTER_MIN_LOCK_MS]，也就是用户要的「回中抑制期」。
     */
    private fun updateRecenterLock(
        signedPitch: Float,
        threshold: Float,
        sameDirection: Boolean,
        moving: Boolean,
        nowMs: Long,
    ) {
        val neutral = threshold * RECENTER_NEUTRAL_FRACTION
        val elapsed = nowMs - recenterLockedAtMs

        // 还在动：锁一律不放（这正是 v5.27 要堵的口子 —— 回摆过程中解除锁定）。
        if (moving) {
            if (abs(signedPitch) > neutral) recenterNeutralSinceMs = 0L
            recenterLocked = true
            return
        }

        if (elapsed >= RECENTER_HARD_CAP_MS) {
            recenterLockDirection = 0
            recenterLocked = false
            recenterNeutralSinceMs = 0L
            Log.i(
                TAG,
                "recenter lock released by hard cap (${RECENTER_HARD_CAP_MS}ms) — " +
                    "head still, pitch=${"%.1f".format(signedPitch)} settled=${"%.1f".format(settledPitch)}",
            )
            return
        }

        if (elapsed < RECENTER_MIN_LOCK_MS) {
            // 最短抑制期还没到：先维持锁。
            if (abs(signedPitch) > neutral) recenterNeutralSinceMs = 0L
            recenterLocked = true
            return
        }

        // 姿势偏置出口：头停在自己当前的姿势上（与"运动起点参考值"一致）。
        if (abs(signedPitch - settledPitch) <= neutral) {
            recenterLockDirection = 0
            recenterLocked = false
            recenterNeutralSinceMs = 0L
            Log.i(
                TAG,
                "recenter lock released, head settled at own posture " +
                    "(pitch=${"%.1f".format(signedPitch)} settled=${"%.1f".format(settledPitch)} " +
                    "neutral=±${"%.1f".format(neutral)} ${elapsed}ms)",
            )
            return
        }

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
                Log.i(TAG, "recenter lock released, back to neutral (${elapsed}ms)")
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

    /**
     * 用**本帧刚拿到的**偏航读数算角速度（v5.8）。
     *
     * ## 为什么必须有这一个
     *
     * [onHeadPose] 里俯仰是**先于**偏航评估的，所以俯仰在做仲裁判断时，
     * `prevSignedYaw` 还停在**上一帧**。第一版仲裁就是因此失灵的：
     *
     * ```
     * 02:02:10.536 nodDown triggered (boosted, near threshold=4.1°) pitch=-9.9°
     * 02:02:10.537 turnR rejected: yaw=28.3° speed=0.1792°/ms reason=pitch-dominant-arbitration
     * ```
     *
     * 同一个瞬间，扭头侧读到的偏航速度是 0.1792°/ms（明显在转头），
     * 而俯仰侧却把这帧当成"没有扭头"而放行了点头 —— 自己的耦合检测反而失灵。
     * 所以仲裁要用本帧读数现算，不能读上一帧。
     */
    private fun yawVelocityWith(currentYaw: Float, nowMs: Long): Float {
        val previousAt = prevSignedYawAtMs
        if (previousAt == 0L || nowMs - previousAt > VELOCITY_SAMPLE_MS) return 0f
        val dt = (nowMs - previousAt).coerceAtLeast(1L)
        return abs(currentYaw - prevSignedYaw) / dt
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

    private fun evaluateYaw(
        yawDeg: Float,
        nowMs: Long,
        @Suppress("UNUSED_PARAMETER") distanceBoost: Float,
    ) {
        // 前面是前置摄像头，画面是镜像的：往自己右边扭头，画面里的脸是往它的左边转，
        // ML Kit 给出的偏航角符号因此与物理方向相反，这里先翻正。
        val delta = yawDeg - baselineYawDeg
        val mirrored = -delta
        val signedYaw = if (invertYaw) -mirrored else mirrored
        val magnitude = abs(signedYaw)
        // 静止锁定同样作用于扭头，压制「手在脸旁晃动」这类横向噪声。
        // v5.8：不再乘距离系数 —— 近距离把「中 20°」变成 40°，用户根本够不到。
        // 距离缩放改由 [effectiveYawSpeedGate] 承担（与俯仰轴的设计一致）。
        val turnThreshold = turnThresholdNow()
        yawThresholdDeg = turnThreshold
        lastSignedYaw = signedYaw

        // v5.9：逐帧上下文（与俯仰同一套机制）；同一帧已经推过就只更新当前槽。
        pushContext(lastSignedPitch, signedYaw, nowMs)

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
        // 在 run 块**外面**算一次：后面触发分支也要用它（以前它只在快通道判断里用，
        // 现在候选拒绝日志同样要用，写在块里会作用域不够）。
        val fast = velocity >= FAST_YAW_VELOCITY

        // v5.8：所有「不触发」的原因都收敛到一个变量，并在退出前打进日志。
        // 以前扭头**完全没有候选日志**（只有成功触发才打），于是「近距离扭头不好使」
        // 只能靠猜——到底是没到阈值、被封顶，还是被俯仰抢了先，日志里一个字都没有。
        val reject: String? = run {
            if (yawOnsetAtMs == 0L) yawOnsetAtMs = nowMs
            if (magnitude < turnThreshold) return@run "below-threshold"
            // v5.14：闭眼后的姿态不可信期（扭头同样受影响）。
            // v5.28：**强扭头豁免** —— 扭头本身会把眼皮挤成半闭。v5.27 实测 25 次扭头候选里
            // 有 12 次被这条拒掉，包括 20:47:37 那次 yaw=29.5°（用户明确在往右扭头，
            // 结果只等来一个 UP）。判据与俯仰侧一致：越阈值 + [STRONG_MARGIN_DEG]。
            // 注意照常调用 eyeUnreliable()（它维护恢复期），只是不再据此否决强扭头。
            val strongTurn = nearDistance && magnitude >= turnThreshold + STRONG_MARGIN_DEG
            val eyeBlocked = eyeUnreliable(nowMs)
            if (eyeBlocked && !strongTurn) return@run "eyes-unreliable"
            // v5.13：手机自己被顿了一下（急停/急刹）—— 扭头同样不成立。
            if (phoneMoving) return@run "phone-motion"

            if (yawReachedAtMs == 0L) {
                yawReachedAtMs = nowMs
                val riseMs = nowMs - yawOnsetAtMs
                // v5.28：近距离档同样要求"突然"（见 [NEAR_SUDDEN_TURN_RISE_MS]）。
                val window =
                    if (nearDistance) minOf(turnMotionWindowMs, NEAR_SUDDEN_TURN_RISE_MS) else turnMotionWindowMs
                yawArmed = riseMs <= window
                if (!yawArmed) {
                    Log.i(TAG, "ignored slow turn: rise ${riseMs}ms > ${window}ms")
                }
            }
            if (!yawArmed) return@run "slow-rise"

            // 与俯仰一致：够快立刻触发，否则才要求保持。
            if (!fast && nowMs - yawReachedAtMs < requiredTurnHoldMs()) return@run "hold-not-met"

            // 最低速度门限，理由同俯仰：噪声有幅度但没有速度。
            if (!fast && velocity < effectiveYawSpeedGate()) return@run "speed-gate"

            // v5.8：方向仲裁 —— 同一帧里俯仰信号明显更强时，这次扭头让位给点头/仰头。
            // 近距离俯视下扭头会同时带出一个俯仰分量，而俯仰阈值在近距离被压到 0.68 倍，
            // 于是「想扭头却变成上下滑」。反过来：真正的点头不会带出多少偏航。
            if (isPitchDominantOverYaw(nowMs)) return@run "pitch-dominant-arbitration"

            null
        }

        if (reject != null) {
            // 只在「看起来像一次动作」时才记录，避免每帧刷屏（与俯仰同一策略）。
            if (magnitude >= turnThreshold * 0.8f) {
                Log.i(
                    TAG,
                    "turn${if (sign < 0) "L" else "R"} candidate rejected: " +
                        "yaw=${"%.1f".format(magnitude)}° pitch=${"%.1f".format(lastSignedPitch)}° " +
                        "speed=${"%.4f".format(velocity)}°/ms gate=${"%.4f".format(effectiveYawSpeedGate())}°/ms " +
                        "pitchSpeed=${"%.4f".format(pitchVelocity(nowMs))}°/ms " +
                        "threshold=${"%.1f".format(turnThreshold)}° " +
                        "dist=${if (nearDistance) "near" else "far"} " +
                        "posture=${if (lookingDown) "down" else "flat"} " +
                        "reason=$reject",
                )
            }
            // 被锁或速度不足时保留计时段，免得用户动作做到一半就被重置掉。
            if (reject == "pitch-dominant-arbitration") clearTurn()
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
                "(${if (fast) "fast" else "held"}) yawSwing=${"%.1f".format(yawSwingDeg)} " +
                "pitchNow=${"%.1f".format(lastSignedPitch)} — " + contextDump(),
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

    // ------------------------------------------------------ v5.9：逐帧上下文 --

    /**
     * 本帧的头部读数是否落在「闭眼之后的不可信期」（v5.14）。
     *
     * 因果链见 [EYE_UNRELIABLE_MS]：闭眼（含一帧浅闭）会让 ML Kit 的姿态估计跳一下，
     * 实测那次误触就是浅闭后 107ms 的一帧 −3.5° 跳变。这里用它把那一帧挡掉。
     */
    private fun eyeUnreliable(nowMs: Long): Boolean {
        if (eyeDip) {
            eyeUnreliableUntilMs = nowMs + EYE_UNRELIABLE_MS
            return true
        }
        return nowMs < eyeUnreliableUntilMs
    }

    /** 记录一帧有符号俯仰，供晃动判定使用（v5.12）。 */
    private fun pushShakeSample(signedPitch: Float) {
        shakePitch[shakeIndex] = signedPitch
        shakeIndex = (shakeIndex + 1) % SHAKE_WINDOW_SAMPLES
        if (shakeCount < SHAKE_WINDOW_SAMPLES) shakeCount++
    }

    /**
     * 维护轻通道的"运动起点"（v5.17）：只在头部稳定时更新 [settledPitch]。
     *
     * 稳定的判据是"最近 [SETTLED_WINDOW_SAMPLES] 帧峰峰值 < [SETTLED_RANGE_DEG]"，
     * 于是动作一开始它就冻结在起点上，动作结束、头停下来之后又重新对齐 ——
     * 这正是"位移"该有的参考点。它不会像 45 帧中位数基线那样滞后好几度。
     */
    private fun updateSettled(signedPitch: Float) {
        settleRing[settleIndex] = signedPitch
        settleIndex = (settleIndex + 1) % SETTLED_WINDOW_SAMPLES
        if (settleCount < SETTLED_WINDOW_SAMPLES) {
            settleCount++
            if (settleCount < SETTLED_WINDOW_SAMPLES) return
        }
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (i in 0 until SETTLED_WINDOW_SAMPLES) {
            val v = settleRing[i]
            if (v < min) min = v
            if (v > max) max = v
        }
        if (max - min < SETTLED_RANGE_DEG) settledPitch = (min + max) / 2f
    }

    /**
     * 晃动判定（v5.12）：路径效率 = `|净位移| / Σ|相邻差值|`，见 [SHAKE_PATH_EFFICIENCY]。
     *
     * 有意动作单调推进 → 效率接近 1；来回抖 → 效率很低。总路程太短（纯噪声）时不做判定，
     * 那种情况由静止锁定与速度门限负责。
     *
     * 顺带把效率记进 [shakeEfficiency]，诊断行会显示，于是"地铁上到底算不算晃"
     * 可以直接读日志判断。
     */
    private fun isShaking(): Boolean {
        if (shakeCount < SHAKE_WINDOW_SAMPLES) {
            shakeEfficiency = 1f
            shakeReversals = 0
            return false
        }
        // 环形缓冲写满时，shakeIndex 指向**最旧**的一格。
        val oldest = shakePitch[shakeIndex]
        var previous = oldest
        var path = 0f
        var reversals = 0
        var lastSign = 0
        var i = 1
        while (i < SHAKE_WINDOW_SAMPLES) {
            val value = shakePitch[(shakeIndex + i) % SHAKE_WINDOW_SAMPLES]
            val delta = value - previous
            path += abs(delta)
            // 死区之内的变化不算一个方向，免得噪声把反转数刷高。
            if (abs(delta) >= SHAKE_DELTA_DEADBAND_DEG) {
                val sign = if (delta > 0f) 1 else -1
                if (lastSign != 0 && sign != lastSign) reversals++
                lastSign = sign
            }
            previous = value
            i++
        }
        val net = abs(previous - oldest)
        shakeEfficiency = if (path <= 0.001f) 1f else net / path
        shakeReversals = reversals
        // v5.16：判决改用**高频反转次数** —— 走路（约 2Hz）6 帧内 ≤1 次、点头 0~1 次，
        // 都放行；手抖/高频摇晃（3~5Hz）3~6 次，拦下。
        return reversals >= SHAKE_MIN_REVERSALS
    }

    /**
     * 记录一帧**原始**偏航，用于 [yawSwingDeg]（v5.28）。
     *
     * 存的是原始读数（不含基准线），这样"基准线刚被重学/重置"也不会让摆幅凭空消失 ——
     * v5.27 那次"扭头被判成上滑"的直接原因就是俯仰侧读到的有符号偏航只剩 1.5°。
     */
    private fun pushYawSwing(rawYaw: Float?, nowMs: Long) {
        if (rawYaw == null) return
        yawSwingRing[yawSwingIndex] = rawYaw
        yawSwingAtMs[yawSwingIndex] = nowMs
        yawSwingIndex = (yawSwingIndex + 1) % YAW_SWING_SAMPLES
        if (yawSwingCount < YAW_SWING_SAMPLES) yawSwingCount++

        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (i in 0 until yawSwingCount) {
            val idx = (yawSwingIndex - 1 - i + YAW_SWING_SAMPLES * 2) % YAW_SWING_SAMPLES
            if (nowMs - yawSwingAtMs[idx] > YAW_SWING_WINDOW_MS) continue
            val v = yawSwingRing[idx]
            if (v < min) min = v
            if (v > max) max = v
        }
        yawSwingDeg = if (min > max) 0f else max - min
    }

    /**
     * 记录一帧上下文（v5.9）。
     *
     * 同一帧内俯仰与偏航都会调用（俯仰先、偏航后），所以用时间戳做去重：
     * 同一毫秒的第二次调用只覆盖当前槽，不会多占一格。
     */
    private fun pushContext(signedPitch: Float, signedYaw: Float?, nowMs: Long) {
        if (ctxCount > 0 && ctxAtMs[(ctxIndex - 1 + CONTEXT_SAMPLES) % CONTEXT_SAMPLES] == nowMs) {
            val slot = (ctxIndex - 1 + CONTEXT_SAMPLES) % CONTEXT_SAMPLES
            ctxPitch[slot] = signedPitch
            ctxYaw[slot] = signedYaw ?: Float.NaN
            ctxFaceRatio[slot] = faceRatio ?: Float.NaN
            return
        }
        ctxPitch[ctxIndex] = signedPitch
        ctxYaw[ctxIndex] = signedYaw ?: Float.NaN
        ctxFaceRatio[ctxIndex] = faceRatio ?: Float.NaN
        ctxAtMs[ctxIndex] = nowMs
        ctxIndex = (ctxIndex + 1) % CONTEXT_SAMPLES
        if (ctxCount < CONTEXT_SAMPLES) ctxCount++
    }

    /**
     * 把上下文缓冲打成一行（v5.9）：`-1100ms|-3.2/1.0/0.50`。
     *
     * 每格三个数依次是 **有符号俯仰 / 有符号偏航 / 脸占比**，时间是相对触发帧的毫秒数。
     * 排成时间序，于是「单帧尖峰」是孤立的一个大值，而「平滑上升」是一串递增的值 ——
     * 这两种形状在日志里一眼可辨，不需要任何额外工具。
     */    private fun contextDump(): String {
        if (ctxCount == 0) return "ctx: (empty)"
        val newestIdx = (ctxIndex - 1 + CONTEXT_SAMPLES) % CONTEXT_SAMPLES
        val newestAt = ctxAtMs[newestIdx]
        val start = if (ctxCount < CONTEXT_SAMPLES) 0 else ctxIndex
        val sb = StringBuilder("ctx ${ctxCount}f:")
        for (i in 0 until ctxCount) {
            val idx = (start + i) % CONTEXT_SAMPLES
            val yawValue = ctxYaw[idx]
            val ratioValue = ctxFaceRatio[idx]
            sb.append(' ')
                .append(ctxAtMs[idx] - newestAt).append("ms|")
                .append("%.1f".format(ctxPitch[idx])).append('/')
                .append(if (yawValue.isNaN()) "-" else "%.1f".format(yawValue)).append('/')
                .append(if (ratioValue.isNaN()) "-" else "%.2f".format(ratioValue))
        }
        return sb.toString()
    }

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
