package com.example.gazescroll

/**
 * States of the two-zone scheme (the "Huawei Reading" style state switch, not a
 * continuous saccade tracker).
 *
 * IDLE      nothing armed; watching for the eye to settle in the bottom zone
 * READY     armed by a >= dwellMs stay in the bottom zone, waiting for the top zone
 * COOLDOWN  a swipe just fired; every gaze signal is ignored until it elapses
 */
enum class GazeState(val label: String) {
    IDLE("IDLE"),
    READY("READY"),
    COOLDOWN("COOLDOWN"),
}

/**
 * One camera frame worth of eye data.
 *
 * All values are normalised to 0f..1f where 0f is the top of the upright camera
 * frame and 1f is the bottom. Already smoothed and already Y-inverted when the
 * user asked for that.
 */
data class GazeSample(
    val leftEyeY: Float?,
    val rightEyeY: Float?,
    val rawMeanY: Float?,
) {
    val hasFace: Boolean
        get() = leftEyeY != null || rightEyeY != null

    val meanY: Float?
        get() {
            val l = leftEyeY
            val r = rightEyeY
            return when {
                l != null && r != null -> (l + r) / 2f
                l != null -> l
                r != null -> r
                else -> null
            }
        }

    companion object {
        val EMPTY = GazeSample(null, null, null)
    }
}

/**
 * 张嘴判定灵敏度。
 *
 * [fraction] 是「张嘴量占脸高的比例」阈值——张嘴量 = 当前（嘴到鼻底距离 / 脸高）减去
 * 本人自然闭嘴时的水平。用比例而不是像素，是为了不受离手机远近和机型分辨率影响。
 *
 * 三个档位的取值来自实机标定：自然闭嘴时读数基本恒定，正常张嘴会比闭嘴高出 10% 脸高
 * 以上，所以 5% / 8% / 12% 三档覆盖「容易触发」到「必须明显张大」。
 */
enum class MouthSensitivity(val label: String, val fraction: Float) {
    HIGH("高（5%，轻微张嘴即可）", 0.05f),
    MEDIUM("中（8%，默认）", 0.08f),
    LOW("低（12%，需要明显张大）", 0.12f),
}

/**
 * Every tunable of the state machine. Defaults are exactly the numbers in the
 * product spec, and the debug UI lets a tester move them at runtime.
 */
data class GazeConfig(
    /** Bottom trigger zone height as a fraction of the screen (spec: 0.25). */
    val bottomZoneRatio: Float = 0.25f,
    /** Top release zone height as a fraction of the screen (spec: 0.20). */
    val topZoneRatio: Float = 0.20f,
    /** How long the eye must stay in the bottom zone before arming (spec: 400 ms). */
    val dwellMs: Long = 400L,
    /** Grace period while READY but outside both zones (spec: 2000 ms). */
    val readyTimeoutMs: Long = 2000L,
    /** Lockout after a swipe (spec: 1500 ms). */
    val cooldownMs: Long = 1500L,
    /** EMA smoothing factor for eye Y; 1.0 disables smoothing. */
    val smoothingAlpha: Float = 0.35f,
    /** Flip the Y axis when head-down makes the landmark Y go the "wrong" way. */
    val invertY: Boolean = false,
    /** Duration of the injected swipe gesture (spec: 100 ms). */
    val swipeDurationMs: Long = 100L,

    // ---------------------------------------------------------- trigger mode --

    /** Primary trigger: blink. On by default. */
    val blinkTriggerEnabled: Boolean = true,
    /**
     * How many consecutive blinks are required: 1, 2 (default) or 3.
     * Each gap must fall inside [BlinkDetector.blinkGapMinMs]..[blinkGapMaxMs].
     */
    val blinkTriggerCount: Int = 2,

    // ---- blink sensitivity (tuned for glasses) ----

    /**
     * Below this, an eye counts as closed. Raised from 0.4 to 0.55: ML Kit reads
     * noticeably lower on people wearing glasses, and 0.4 made blinks hard to hit.
     */
    val blinkClosedBelow: Float = 0.55f,
    /** Above this an eye counts as open again — the gap is the hysteresis band. */
    val blinkOpenAbove: Float = 0.70f,
    /** Consecutive closed frames required before the closure is believed. */
    val blinkClosedFrames: Int = 2,

    // ---- head pose (nod down / tilt up) ----

    /** Off by default so a new user cannot trigger it by accident. */
    val headPoseEnabled: Boolean = false,
    /** Degrees away from the sliding baseline that counts as a nod / tilt. */
    val headPoseAngleThreshold: Float = 8f,

    /**
     * v5.60：**仰头**方向的独立角度阈值（点头用 [headPoseAngleThreshold]）。
     *
     * 用户点名要拆开：「抬头看别处被当成翻页」这个误触只在仰头方向，而点头方向的
     * 轻点头又要灵敏 —— 一个值管两个方向时无解。默认取 [headPoseAngleThreshold]，
     * 所以老配置（没有这个键）读出来仍是"两个方向同一个值"，行为一个字不变。
     */
    val headPoseUpThresholdDeg: Float = headPoseAngleThreshold,

    /** The baseline→peak rise must happen inside this window to count as a nod. */
    val headPoseMotionWindowMs: Long = 500L,
    /** …and the peak must then be held this long before it fires. */
    val headPoseHoldMs: Long = 150L,
    /** Flip the pitch sign if nodding and tilting come out swapped on a device. */
    val headPoseInvertPitch: Boolean = false,

    // ------------------------------------------------- v4.4：左右扭头滑动 --

    /** 启用「向左/向右扭头」触发水平滑动。默认关闭，不影响老用户。 */
    val horizontalSwipeEnabled: Boolean = false,

    /** 左右扭头灵敏度（角度档位）：28°/20°/14°。数值越小越灵敏。 */
    val horizontalSwipeAngleThreshold: Float = 20f,

    /** 左右反了的时候打开，反转偏航角符号。 */
    val horizontalSwipeInvertYaw: Boolean = false,

    // ------------------------------------------------- v4.6：张嘴点击屏幕中央 --

    /**
     * 张嘴一次 → 在屏幕中央注入一次单击。默认开启。
     *
     * 在抖音这类全屏播放器里，单击屏幕中央就是**暂停 / 播放**，所以这个动作交给目标
     * App 去响应，而不是冻结 GazeScroll 自己——v4.5 的「张嘴暂停 App」在实机上被证明
     * 没用（暂停后连同上下左右滑动一起失效了）。
     *
     * 这是一条**控制指令**，不走 [GlobalTriggerGate]，因此不会被冷却挡住。防连发靠
     * [MouthOpenDetector] 的锁存——必须闭嘴之后再次张嘴才算下一次。它也**不影响**
     * 眨眼 / 点头 / 扭头翻页：张嘴之后这些照常工作。
     */
    val mouthTapEnabled: Boolean = true,

    /** 张嘴判定灵敏度档位。 */
    val mouthSensitivity: MouthSensitivity = MouthSensitivity.MEDIUM,

    // ------------------------------------------------- v5.35：歪头控音量 --

    /**
     * 歪头（左右压耳朵，roll）→ 把媒体音量调一组档位。**默认开启**。
     *
     * 这是 v5.35 用户点名的改动：**删掉「单眼闭眼控音量」**（v5.30~v5.34 四轮都做不稳 ——
     * ML Kit 的睁眼概率在这台设备上分不开"有意单闭"和"低头眯眼"，见 [TiltDetector] 注释），
     * 改成**歪头**：
     *
     *  - 左歪头 = 调高、右歪头 = 调低（默认，两方向都能各自反转）；
     *  - 必须歪到一定角度（[tiltThresholdDeg]，默认 18°）并保持 [tiltHoldMs]（默认 0.5 秒）
     *    才触发 —— 用户原话「仰头得到一定的角度，才会触发」；
     *  - 一次歪头只调一组，必须回到中位才允许下一次。
     *
     * 它是一条**控制指令**，与「张嘴点击」同类：不走 [GlobalTriggerGate]，既不占用翻页冷却、
     * 也不会被冷却挡住。歪头期间翻页判定会被暂停（避免歪头顺带翻页）。
     */
    val tiltVolumeEnabled: Boolean = true,

    /**
     * 左歪头 → 调高音量；关闭则调低。
     *
     * 用户要求两个方向都能自己拨（与 v5.30 的眨眼方向开关同一套做法）。
     */
    val tiltLeftVolumeUp: Boolean = true,

    /**
     * 右歪头 → 调高音量；关闭则调低。
     *
     * 默认「右歪头 = 调低」（用户原话：左歪头上升、右歪头下降）。
     */
    val tiltRightVolumeUp: Boolean = false,

    /**
     * 触发角度（度）：相对本人头姿基准线的倾斜必须超过它。
     * 选项 [TiltDetector.THRESHOLD_OPTIONS]：10 / **13（默认）** / 16 / 20。
     * v5.36 按用户"再灵敏一点"的要求整体下调（原来默认 18°）。
     */
    val tiltThresholdDeg: Float = TiltDetector.DEFAULT_THRESHOLD_DEG,

    /**
     * 超过阈值后要再保持多久（毫秒）才触发。
     * 选项 [TiltDetector.HOLD_OPTIONS]：0.2 / **0.3（默认）** / 0.5 / 0.8 秒。
     * v5.36 同样调灵一档（原来默认 0.5 秒）。
     */
    val tiltHoldMs: Long = TiltDetector.DEFAULT_HOLD_MS,

    /**
     * 一次歪头调几档音量（1 档 = 按一次音量键，小米 13 上 = 音量索引 10）。
     *
     * 可选 1（默认）/ 2 / 3 / 5。
     */
    val tiltVolumeStep: Int = 1,

    // ------------------------------------------------- v4.5：自适应滑动 --

    /**
     * 根据前台应用自动调整上下滑动的幅度与时长。默认开启。
     *
     * 抖音需要整屏切换，微博/小红书这类连续列表则需要短距离柔性滚动，否则「一划就滚
     * 很多」。关闭后回到 v4.4 的固定参数（80% / 100ms）。
     */
    val adaptiveSwipeEnabled: Boolean = true,

    /**
     * 列表类应用（微博 / 小红书 / 知乎 / B站…）的滑动幅度，占屏幕高度的比例。
     *
     * 默认 [AdaptiveSwipe.DEFAULT_LIST_DISTANCE]（26%），与 v4.5 出厂手感完全一致；
     * 用户可以在设置里按自己的手感调。时长不跟着变（仍是 420ms），所以调小只是
     * 「滚得更少」，不会变得生硬。
     */
    val listSwipeDistance: Float = AdaptiveSwipe.DEFAULT_LIST_DISTANCE,

    // ------------------------------------------------- v4.7：全局使用翻页 --

    /**
     * 全局使用翻页：打开后桌面和所有应用都响应翻页手势，不再受目标应用列表限制。
     *
     * 默认关闭，保持 v4.6 的节能白名单行为。打开后摄像头会一直工作（费电，但随时可用），
     * 适合「想在桌面偶尔也能翻一下」的场景。
     */
    val globalPagingEnabled: Boolean = false,

    // ------------------------------------------------- v4.8：防误触增强 --

    /**
     * 静止锁定：连续半秒几乎没在动时，把判定阈值放大 [staticLockFactor] 倍。
     *
     * 用来压掉「一动不动也误触下滑」——静止时出现的「动作」几乎一定是 ML Kit 的读数
     * 抖动（1~3°）。真的一动，锁定立刻解除，所以不影响正常动作的响应速度。
     */
    val staticLockEnabled: Boolean = true,

    /**
     * 静止锁定的阈值放大倍数。
     *
     * 1.5 是权衡后的取值：静止时 ML Kit 的读数抖动一般在 1~3°，而一次有意动作会明显
     * 超过阈值本身，所以 1.5 足以把噪声压掉，又不会让正常动作变得迟钝。
     */
    val staticLockFactor: Float = 1.5f,

    // ------------------------------------------------------- 全局冷却（防误触） --

    /**
     * 全局冷却开关。**打开后点头和眨眼共用同一个冷却计时器**：任意一个动作
     * 触发翻页后，整个触发系统进入冷却，冷却期内所有信号一律忽略。
     *
     * 关闭时回到旧行为（各检测器只管自己），保持向后兼容。
     */
    val globalCooldownEnabled: Boolean = true,

    /**
     * 全局冷却时长，单位毫秒。默认 1500 ms。
     *
     * 摄像头逐帧检测，一次真实动作会连续命中好几帧；这个值就是"一次动作 = 一次
     * 翻页"的节流窗口。调大更防误触但连续翻页更慢，调小更跟手但容易重复触发。
     */
    val globalCooldownMs: Long = GlobalTriggerGate.DEFAULT_COOLDOWN_MS,

    /**
     * **v5.72：冷却期"偷懒档"**（省电）。
     *
     * 打开后，冷却期的前段把分析频率从约 15fps 降到约 5fps
     * （见 [FaceGazeAnalyzer.COOLDOWN_MIN_INTERVAL_MS]），
     * 冷却结束前 [FaceGazeAnalyzer.COOLDOWN_TAIL_MS] 恢复满帧率。
     *
     * **不改变任何判定**：冷却期本来就是"看到什么都不算"，
     * 降频只是少做那些注定被丢弃的推理；恢复尾段是为了让"连续 N 帧闭眼"
     * 这类数帧数的判据在冷却结束的瞬间与降频前完全一致。
     *
     * 默认开。设成 false 可以退回 v5.71 的行为，用于 A/B 实测对照。
     */
    val cooldownThrottleEnabled: Boolean = true,

    /**
     * **v5.75：无动作自动降档**（省电，**默认关**）。
     *
     * 打开后，只要**连续 [idleAfterMs] 没有翻过页**，分析频率就从约 15fps
     * 降到约 4fps（见 [FaceGazeAnalyzer.IDLE_MIN_INTERVAL_MS]）；
     * 一旦翻页立刻回到满帧率。覆盖的是"一直看、没操作"的那段时间，
     * 也就是刷视频时占比最大的部分。
     *
     * **默认关是刻意的**：降到 4fps 会直接让眨眼变难（眨眼判据要求连续几帧闭眼），
     * 属于"拿响应换电"，必须由用户自己权衡，不能替他决定。
     */
    val idleThrottleEnabled: Boolean = false,

    /** 无动作多久后进入低档（毫秒），范围 5000~10000。 */
    val idleAfterMs: Long = 5_000L,

    // ------------------------------------------- v5.39：注视数据采集（测试功能） --

    /**
     * 「注视数据采集」（v5.39）：**测试功能，默认关闭**。
     *
     * 打开后，摄像头每一帧都会额外算一组**原始几何量**（人脸框、十个关键点、画面亮度）
     * 并按帧写进 App 私有目录下的 CSV（`files/probe/probe-*.csv`），供离线分析
     * 「眼睛有没有盯着屏幕」到底能用哪几个量判定 —— ML Kit 的人脸检测**没有虹膜**，
     * 只能靠头姿 + 关键点几何（用户提的"鼻子辅助定位"就在这里）。
     *
     * 采集的起止由**盖住前置摄像头**控制（详见 [GazeProbeRecorder]）：
     * 盖 ≥3 秒 → 露脸开始录；录制中盖 1~3 秒 → 分段；盖 ≥3 秒 → 结束。
     *
     * 它**只记录、不判定**：不开这个开关时逐帧开销与 v5.36 一模一样，开了也不改变
     * 任何一条触发通道的行为。
     */
    val probeEnabled: Boolean = false,

    // ------------------------------------------- v5.43：注视门（眼睛得盯着屏幕） --

    /**
     * 「注视门」模式（v5.43，用户点名的新功能）：**眼睛盯着屏幕才允许触发**，用来压误触。
     *
     * 默认 [GateMode.OBSERVE]（**只观察、不拦**），原因写在这里，别改错：
     *  - v5.37 就是因为"加严过头"被用户整版退回的（见 CHANGELOG 的 [5.37]/[5.38] 一节）；
     *  - 这道门有一条判据（睁眼占比）**在 30cm 近距离下不可用**（实测：盯着屏幕时中位数
     *    也只有 0.21，与"眼睛看别处"的 0.03 大面积重叠），所以它天然挡不住"近距离时的误触"；
     *  - 因此先让它**只记录"本来会拦掉哪一次触发"**，拿真实使用量出代价，再决定要不要默认拦。
     *
     * 判据、阈值与实测依据全部写在 [GazeGate] 的注释里。
     */
    val gazeGateMode: GateMode = GateMode.OBSERVE,

    /**
     * Legacy "look at the bottom, then at the top" two-zone state machine.
     * Kept for debugging only — OFF by default, blink is the real trigger.
     */
    val gazeModeEnabled: Boolean = false,
) {
    /** Y at or below this value counts as "inside the top release zone". */
    val topZoneEndY: Float get() = topZoneRatio

    /** Y at or above this value counts as "inside the bottom trigger zone". */
    val bottomZoneStartY: Float get() = 1f - bottomZoneRatio

    /** Clamp everything into a range the state machine can actually run with. */
    fun sanitized(): GazeConfig {
        val b = bottomZoneRatio.coerceIn(0.05f, 0.60f)
        val t = topZoneRatio.coerceIn(0.05f, 0.60f)
        val sum = b + t
        // The zones must never overlap, otherwise READY could release instantly.
        val (nb, nt) = if (sum <= 0.90f) b to t else (b * (0.90f / sum)) to (t * (0.90f / sum))
        return copy(
            bottomZoneRatio = nb,
            topZoneRatio = nt,
            dwellMs = dwellMs.coerceIn(100L, 3000L),
            readyTimeoutMs = readyTimeoutMs.coerceIn(300L, 10_000L),
            cooldownMs = cooldownMs.coerceIn(0L, 10_000L),
            smoothingAlpha = smoothingAlpha.coerceIn(0.05f, 1f),
            swipeDurationMs = swipeDurationMs.coerceIn(60L, 1000L),
            blinkTriggerCount = blinkTriggerCount.coerceIn(1, 4),
            blinkClosedBelow = blinkClosedBelow.coerceIn(0.10f, 0.90f),
            blinkOpenAbove = blinkOpenAbove.coerceIn(0.20f, 0.99f),
            blinkClosedFrames = blinkClosedFrames.coerceIn(1, 6),
            headPoseAngleThreshold = headPoseAngleThreshold.coerceIn(3f, 45f),
            headPoseUpThresholdDeg = headPoseUpThresholdDeg.coerceIn(3f, 45f),
            headPoseMotionWindowMs = headPoseMotionWindowMs.coerceIn(150L, 2000L),
            headPoseHoldMs = headPoseHoldMs.coerceIn(60L, 1500L),
            horizontalSwipeAngleThreshold = horizontalSwipeAngleThreshold.coerceIn(8f, 45f),
            staticLockFactor = staticLockFactor.coerceIn(1f, 3f),
            listSwipeDistance = listSwipeDistance.coerceIn(
                AdaptiveSwipe.MIN_LIST_DISTANCE,
                AdaptiveSwipe.MAX_LIST_DISTANCE,
            ),
            globalCooldownMs = globalCooldownMs.coerceIn(MIN_GLOBAL_COOLDOWN_MS, MAX_GLOBAL_COOLDOWN_MS),
            // v5.75：无动作自动降档的等待时长。
            idleAfterMs = idleAfterMs.coerceIn(MIN_IDLE_AFTER_MS, MAX_IDLE_AFTER_MS),
            // v5.35：歪头控音量的角度 / 保持时长 / 档位。
            tiltThresholdDeg = tiltThresholdDeg.coerceIn(8f, 40f),
            tiltHoldMs = tiltHoldMs.coerceIn(200L, 2000L),
            tiltVolumeStep = tiltVolumeStep.coerceIn(1, 5),
        )
    }

    companion object {
        /** 全局冷却可调节下限：0.5 秒。 */
        const val MIN_GLOBAL_COOLDOWN_MS = 500L

        /** 全局冷却可调节上限：5 秒。 */
        const val MAX_GLOBAL_COOLDOWN_MS = 5000L

        // ============ v5.75：无动作自动降档（省电） ============

        /** 无动作多久后降档的下限：5 秒。 */
        const val MIN_IDLE_AFTER_MS = 5_000L

        /** 无动作多久后降档的上限：10 秒。 */
        const val MAX_IDLE_AFTER_MS = 10_000L

        /** 无动作降档滑块的步长：1 秒（6 档，够用且好对准）。 */
        const val IDLE_AFTER_STEP_MS = 1_000L

        /** 无动作降档滑块的档数。 */
        const val IDLE_AFTER_STEPS =
            ((MAX_IDLE_AFTER_MS - MIN_IDLE_AFTER_MS) / IDLE_AFTER_STEP_MS).toInt()

        /** 档位 -> 毫秒。 */
        fun idleAfterMsForStep(step: Int): Long =
            (MIN_IDLE_AFTER_MS + step.coerceIn(0, IDLE_AFTER_STEPS) * IDLE_AFTER_STEP_MS)
                .coerceIn(MIN_IDLE_AFTER_MS, MAX_IDLE_AFTER_MS)

        /** 毫秒 -> 最接近的档位。 */
        fun idleAfterStepForMs(ms: Long): Int {
            val clamped = ms.coerceIn(MIN_IDLE_AFTER_MS, MAX_IDLE_AFTER_MS)
            return ((clamped - MIN_IDLE_AFTER_MS) / IDLE_AFTER_STEP_MS).toInt()
                .coerceIn(0, IDLE_AFTER_STEPS)
        }

        /** 把任意毫秒值吸附到步长。 */
        fun snapIdleAfter(ms: Long): Long = idleAfterMsForStep(idleAfterStepForMs(ms))

        // ============ v5.60：交给用户自己调的四个参数（滑块范围与步长） ============

        /** 触发速度：头部动作要在这段时间内完成才算「突然」。越小越不容易误触。 */
        const val MIN_MOTION_WINDOW_MS = 150L
        const val MAX_MOTION_WINDOW_MS = 1500L
        const val STEP_MOTION_WINDOW_MS = 50L

        /** 点头 / 仰头角度。 */
        const val MIN_PITCH_DEG = 3f
        const val MAX_PITCH_DEG = 20f
        const val STEP_PITCH_DEG = 0.5f

        /** 扭头角度。 */
        const val MIN_YAW_DEG = 8f
        const val MAX_YAW_DEG = 40f
        const val STEP_YAW_DEG = 1f

        /** 歪头（调音量）角度。 */
        const val MIN_TILT_DEG = 8f
        const val MAX_TILT_DEG = 30f
        const val STEP_TILT_DEG = 0.5f

        /**
         * 「恢复默认」回到的值 —— **就是 v5.51 交付那天用户实际在用的那一套**
         * （用户原话：「把目前的，当成初始的默认模式」「防止用户调整坏了」）。
         *
         * 注意它和上面 GazeModel 的构造函数默认值（点头/仰头 8°、扭头 20°、歪头 13°）
         * **不是一回事**：那是"全新安装"的出厂值，这里是他亲手调好并认可的那一套。
         */
        const val RESET_PITCH_DOWN_DEG = 6f
        const val RESET_PITCH_UP_DEG = 6f
        const val RESET_YAW_DEG = 28f
        const val RESET_TILT_DEG = 13f
        const val RESET_MOTION_WINDOW_MS = 500L

        /**
         * 滑块步长：250 ms。0.5s~5s 正好切成 18 档，既够细也不会像 1ms 步进
         * 那样在小米 13 的窄条上难以对准。
         */
        const val GLOBAL_COOLDOWN_STEP_MS = 250L

        /** 滑块档数：[MIN_GLOBAL_COOLDOWN_MS] + 档位 × [GLOBAL_COOLDOWN_STEP_MS]。 */
        const val GLOBAL_COOLDOWN_STEPS =
            ((MAX_GLOBAL_COOLDOWN_MS - MIN_GLOBAL_COOLDOWN_MS) / GLOBAL_COOLDOWN_STEP_MS).toInt()

        /** 滑块档位（0 对应下限，[GLOBAL_COOLDOWN_STEPS] 对应上限）-> 毫秒。 */
        fun cooldownMsForStep(step: Int): Long =
            (MIN_GLOBAL_COOLDOWN_MS + step.coerceIn(0, GLOBAL_COOLDOWN_STEPS) * GLOBAL_COOLDOWN_STEP_MS)
                .coerceIn(MIN_GLOBAL_COOLDOWN_MS, MAX_GLOBAL_COOLDOWN_MS)

        /** 毫秒 -> 最接近的滑块档位。 */
        fun cooldownStepForMs(ms: Long): Int {
            val clamped = ms.coerceIn(MIN_GLOBAL_COOLDOWN_MS, MAX_GLOBAL_COOLDOWN_MS)
            return ((clamped - MIN_GLOBAL_COOLDOWN_MS) / GLOBAL_COOLDOWN_STEP_MS).toInt()
                .coerceIn(0, GLOBAL_COOLDOWN_STEPS)
        }

        /** 把任意毫秒值吸附到滑块步长，并夹到合法区间。 */
        fun snapGlobalCooldown(ms: Long): Long = cooldownMsForStep(cooldownStepForMs(ms))

        /** 冷却时长的显示文本，例如 `1.5 秒` / `2 秒` / `0.5 秒`。 */
        fun formatCooldown(ms: Long): String =
            if (ms % 1000L == 0L) {
                "${ms / 1000} 秒"
            } else {
                "%.1f 秒".format(java.util.Locale.US, ms / 1000.0)
            }
    }
}
