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
         * **近距离 + 俯视**时的点头增益（v5.12）：`6.0° × 0.50 = 3.0°`。
         *
         * ## 实机依据（v511-verify.log，用户明确在「轻轻点头」）
         *
         * 用户的原话是「我希望在俯视的状态下能轻轻地触发点头」。日志量到的"轻点头"幅度：
         *
         * ```
         * 03:20:02.275 nodDown rejected: pitch=-3.7° threshold=4.1° reason=below-threshold
         * 03:20:05.152 nodDown rejected: pitch=-3.9° threshold=4.1° reason=below-threshold
         * 03:21:20.565 nodDown rejected: pitch=-4.1° threshold=4.1° reason=below-threshold
         * ```
         *
         * 也就是**用户的轻点头正好落在 3.7~4.4°，而 v5.6 那一档的阈值是 4.1°** ——
         * 卡在门上，所以"有时候好使、有时候不好使"。压到 3.0° 之后，
         * 轻点头有 0.7~1.4° 的余量，而**远距离完全不受影响**（该档要求近距 + 俯视两条同时成立）。
         *
         * ⚠️ 阈值压低之后必须配 [SHAKE_PATH_EFFICIENCY] 的晃动过滤，
         * 否则地铁上近距离俯视时的晃动会直接顶穿这个阈值 —— 用户明确点名了这一点。
         */
        private const val NEAR_LOOKDOWN_NOD_BOOST = 0.50f

        /**
         * 晃动过滤的观察窗口（帧数，v5.12）。
         *
         * 实测帧间隔约 63~116ms，6 帧 ≈ 0.5~0.7 秒，正好覆盖一次轻点头（约 300ms）
         * 加上它前面的一段静止。
         */
        private const val SHAKE_WINDOW_SAMPLES = 6

        /**
         * 晃动判据：**路径效率**（v5.12）。
         *
         * `效率 = |最新值 − 最旧值| / Σ|相邻差值|`
         *
         *  - **有意动作**是单调推进：走过的路 ≈ 净位移 → 效率接近 1；
         *  - **晃动**（地铁、手抖）是来回抖：走过的路远大于净位移 → 效率很低。
         *
         * 实例（阈值 3.0°）：
         *
         * ```
         * 轻点头   0 → 1.0 → 2.5 → 3.5 → 4.2 → 4.2   路径 4.2  净位移 4.2  效率 1.00  ✅ 放行
         * 晃动   -1.5 → 1.2 → -1.0 → 2.8 → -1.2 → 3.2 路径 17.1 净位移 4.7  效率 0.27  ⛔ 拦下
         * ```
         *
         * 之所以不用"数方向反转次数"：效率把幅度和次数合成一个量，少一个要调的参数，
         * 而且对低频大幅晃动（反转不多但来回走得很远）同样有效。
         */
        private const val SHAKE_PATH_EFFICIENCY = 0.45f

        /** 路径太短时效率没有意义（纯噪声），低于这个总路程就不做晃动判定。 */
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
         * 这个门槛只作用于**近距离 + 俯视 + 低头**（与 ×0.50 增益完全同一条件），
         * 远距离与仰头方向一概不受影响。
         */
        private const val LIGHT_NOD_FAST_VELOCITY = 0.013f

        /**
         * 轻点头通道的确认时间（v5.13）：越过阈值后必须**再撑过这么久**才放行。
         *
         * 取 60ms 而不是更大，是因为实测帧间隔是 63~116ms —— 60ms 刚好保证"下一帧还活着就通过"，
         * 于是它等价于「**这个动作必须跨到下一帧**」：单帧跳变会在下一帧掉回 below-threshold
         * 并清掉计时段，永远过不来（这正是 v5.9 那次全局确认窗口想做的事，
         * 区别是现在只作用在这一条通道上，代价只有一帧，不会拖慢其他动作）。
         */
        private const val LIGHT_NOD_CONFIRM_MS = 60L

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
     * **手机本身**是否正在被顿挫（v5.13），由服务从 [PhoneMotionMonitor] 每帧同步。
     *
     * 为 true 时一律不接受俯仰/偏航候选。理由见 [PhoneMotionMonitor]：
     * 点头是头在转（手机不动），急停/急刹是整个人和手机一起顿 —— 摄像头分不出来，
     * 加速度计分得出来。判据是"手机在动"，所以**不影响任何正常坐着/躺着刷的场景**。
     */
    @Volatile
    var phoneMoving: Boolean = false

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

        // v5.13：轻点头通道的生效条件与 ×0.50 增益**完全一致**（近距离 + 俯视 + 低头），
        // 所以"阈值被压到 3.0°"和"速度门槛降到 0.013"永远同时生效，不会出现只松一半。
        val lightNodAllowed = nearDistance && lookingDown && signedPitch < 0f

        // ---- v5.9：逐帧上下文（必须在任何提前返回**之前**维护，否则轨迹会缺帧）----
        pushContext(signedPitch, signedYawNow, nowMs)
        // v5.12：晃动判定的缓冲同理，必须在提前返回之前维护。
        pushShakeSample(signedPitch)

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
            // v5.12 晃动过滤：地铁/手抖是**来回抖**（走过的路远大于净位移），
            // 即使顶穿了阈值也不算动作。必须在压低阈值（3.0°）之后有它兜底。
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
                pitchArmed = riseMs <= motionWindowMs
                if (!pitchArmed) Log.i(TAG, "ignored slow lean: rise ${riseMs}ms > ${motionWindowMs}ms")
            }
            if (!pitchArmed) return@run "slow-rise"
            val fast = velocity >= FAST_PITCH_VELOCITY
            // v5.13：近距离俯视的**轻点头通道** —— 见 LIGHT_NOD_FAST_VELOCITY 的实机数据。
            val light = !fast && lightNodAllowed && velocity >= LIGHT_NOD_FAST_VELOCITY
            if (!fast && !light && nowMs - pitchReachedAtMs < requiredHoldMs()) return@run "hold-not-met"
            // 轻通道必须多撑过一帧（单帧跳变会在下一帧掉回 below-threshold，永远过不来）。
            if (light && nowMs - pitchReachedAtMs < LIGHT_NOD_CONFIRM_MS) return@run "light-confirm"
            // 最低速度门限：噪声有幅度但没有速度，所以再加一道与幅度无关的门。
            if (!fast && !light && velocity < speedGate) return@run "speed-gate"
            // v5.8 方向仲裁：偏航正在明显转动 → 这次让位给扭头。
            // 近距离俯视扭头会同时带出一个俯仰分量（实测 ±3~±10°），而俯仰阈值被单向
            // 增益压到 0.68 倍（4.1°/5.4°），不让位的话"想扭头"永远先变成上下滑。
            // 真正的点头不带偏航角速度，所以对纯点头零影响。
            if (signedYawNow != null && isYawMovingNow(signedYawNow, nowMs, velocity)) {
                return@run "yaw-dominant-arbitration"
            }
            null
        }

        pitchYieldedToYaw = reject == "yaw-dominant-arbitration"

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
                        "posture=${if (lookingDown) "down" else "flat"} " +
                        "shake=${"%.2f".format(shakeEfficiency)} " +
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
        // v5.9：每次触发都把前约 1.2 秒的原始读数打出来（见 CONTEXT_SAMPLES 的说明）。
        val ctxNote = " — " + contextDump()
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
                    "latency=${latencyMs}ms ($how, v=${"%.3f".format(velocity)}°/ms)$staticNote$ctxNote",
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
                    "dist=${if (nearDistance) "near" else "mid/far"} " +
                    "boost=${"%.2f".format(nearNodDownBoost(signedPitch))} " +
                    "latency=${latencyMs}ms ($how)$staticNote$ctxNote",
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
            // v5.13：手机自己被顿了一下（急停/急刹）—— 扭头同样不成立。
            if (phoneMoving) return@run "phone-motion"

            if (yawReachedAtMs == 0L) {
                yawReachedAtMs = nowMs
                val riseMs = nowMs - yawOnsetAtMs
                yawArmed = riseMs <= turnMotionWindowMs
                if (!yawArmed) {
                    Log.i(TAG, "ignored slow turn: rise ${riseMs}ms > ${turnMotionWindowMs}ms")
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
                "(${if (fast) "fast" else "held"}) — " + contextDump(),
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

    /** 记录一帧有符号俯仰，供晃动判定使用（v5.12）。 */
    private fun pushShakeSample(signedPitch: Float) {
        shakePitch[shakeIndex] = signedPitch
        shakeIndex = (shakeIndex + 1) % SHAKE_WINDOW_SAMPLES
        if (shakeCount < SHAKE_WINDOW_SAMPLES) shakeCount++
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
            return false
        }
        // 环形缓冲写满时，shakeIndex 指向**最旧**的一格。
        val oldest = shakePitch[shakeIndex]
        var previous = oldest
        var path = 0f
        var i = 1
        while (i < SHAKE_WINDOW_SAMPLES) {
            val value = shakePitch[(shakeIndex + i) % SHAKE_WINDOW_SAMPLES]
            path += abs(value - previous)
            previous = value
            i++
        }
        val net = abs(previous - oldest)
        val efficiency = if (path <= 0.001f) 1f else net / path
        shakeEfficiency = efficiency
        if (path < SHAKE_MIN_PATH_DEG) return false
        return efficiency < SHAKE_PATH_EFFICIENCY
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
     */
    private fun contextDump(): String {
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
