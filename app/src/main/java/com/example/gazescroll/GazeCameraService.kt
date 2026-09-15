package com.example.gazescroll

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service (type `camera`) that owns the whole pipeline:
 *
 *   CameraX front camera -> [FaceGazeAnalyzer] -> [BlinkDetector] -> swipe
 *
 * The blink detector is the real trigger; [GazeStateMachine] is kept as an
 * optional debug path and is off unless `GazeConfig.gazeModeEnabled` is set.
 *
 * Everything runs here rather than in the activity, so blinking keeps working
 * after the user goes back to the home screen and opens Douyin.
 *
 * Power: the camera is bound once. Throttling is done by dropping frames in the
 * analyzer (1 fps while no face is present), and the pipeline is only unbound
 * on screen-off, which is a rare event rather than a per-frame decision.
 */
class GazeCameraService : LifecycleService() {

    companion object {
        const val ACTION_START = "com.example.gazescroll.action.START"
        const val ACTION_STOP = "com.example.gazescroll.action.STOP"

        /**
         * Debug-only: fire one swipe without blinking, so the gesture path can be
         * verified from a PC.
         *
         *   adb shell am broadcast -a com.example.gazescroll.action.TEST_SWIPE -p com.example.gazescroll
         */
        const val ACTION_TEST_SWIPE = "com.example.gazescroll.action.TEST_SWIPE"

        /** Optional boolean extra for ACTION_TEST_SWIPE: true swipes down instead of up. */
        const val EXTRA_TEST_DOWN = "down"

        /**
         * Optional float / long extras for ACTION_TEST_SWIPE (v5.0).
         *
         * 用来**逐组试出某个 App 到底认什么样的滑动**：`from` / `to` 是屏幕高度比例，
         * `duration` 是毫秒。不传就用当前自适应参数。
         *
         *   adb shell am broadcast -a com.example.gazescroll.action.TEST_SWIPE \
         *     -p com.example.gazescroll --ef from 0.80 --ef to 0.20 --el duration 400
         */
        const val EXTRA_TEST_FROM = "from"
        const val EXTRA_TEST_TO = "to"
        const val EXTRA_TEST_DURATION = "duration"

        /** Force a full camera-pipeline rebuild (notification action / settings button). */
        const val ACTION_RESTART = "com.example.gazescroll.action.RESTART"

        private const val CHANNEL_ID = "blink_scroll"
        private const val NOTIF_ID = 1001

        /** Analysis resolution (spec: low, to keep the sensor and ML Kit cheap). */
        private val ANALYSIS_SIZE = Size(480, 360)

        /** How often the GazeDiag pipeline snapshot is logged. */
        private const val DIAGNOSTIC_INTERVAL_MS = 3000L

        /** How often the frame watchdog checks that the pipeline is alive. */
        private const val FRAME_WATCHDOG_INTERVAL_MS = 1000L

        /** No frame for this long while analysing means the binding is wedged. */
        private const val FRAME_TIMEOUT_MS = 3000L

        /**
         * 刚绑定 / 刚恢复流水线后，给相机这么久的宽限期。
         *
         * 重新打开前置摄像头本身就要几百毫秒，v4.7 又在每次进入允许翻页的界面时主动
         * 校准一次绑定状态，所以必须把「正在打开」和「卡死」区分开，否则看门狗会把
         * 正常启动误判成故障、反复重建。
         */
        private const val REBIND_GRACE_MS = 2500L

        /**
         * 检测到遮挡后，抑制触发多久（v4.9）。
         *
         * 500ms 覆盖「手从脸旁移开、画面重新稳定」；脸整体丢失超过 150ms 时用更长的
         * [OCCLUSION_SUPPRESS_LONG_MS]，因为整只手拿开比手指移开需要更久才重新稳定。
         */
        private const val OCCLUSION_SUPPRESS_MS = 500L

        /** 脸被整只手 / 拳头盖住后的抑制时长。 */
        private const val OCCLUSION_SUPPRESS_LONG_MS = 800L

        /** 反复遮挡时抑制时长的上限：足够覆盖「手放下再拿开」的连击，又不至于太久。 */
        private const val OCCLUSION_SUPPRESS_MAX_MS = 1600L

        /** 两次遮挡相隔多久以内算「反复遮挡」（手在脸前晃 / 放下又抬起）。 */
        private const val OCCLUSION_REPEAT_WINDOW_MS = 2500L

        /**
         * 人脸消失多久以内回来算「被挡住」。
         *
         * 超过这个时间更可能是用户真的离开了画面（去拿东西、转头看别处），那时重新学习
         * 基准线就够了，不需要抑制——否则用户回来还要白等一秒才恢复操作。
         */
        private const val FACELOST_OCCLUSION_MAX_MS = 1200L

        /** Give up after this many consecutive unproductive rebinds. */
        private const val MAX_RESTART_ATTEMPTS = 3

        /** 活跃探针连续失败几次后升级为「丢弃 provider 重取」。 */
        private const val MAX_LIVENESS_ESCALATIONS = 3

        /** 相机获取失败的退避重试间隔（v5.4）。 */
        private val WAKE_RETRY_DELAYS_MS = longArrayOf(500L, 1000L, 2000L)

        /** 全链路自检日志的间隔（v5.5）。 */
        private const val SELF_CHECK_INTERVAL_MS = 30_000L

        /** 连续注入失败达到这个次数就强制重连无障碍服务（v5.5）。 */
        private const val MAX_INJECTION_FAILURES = 2

        /**
         * 无障碍「设置里已启用、却迟迟拿不到实例」持续这么久后，直接强制重绑（v5.7）。
         *
         * 10 秒是刻意选的：比一次正常的 rebind 往返（600ms 延迟 + 系统绑定）长出两个
         * 数量级，绝不会误伤正在恢复中的情况；又远短于用户会去手动下拉状态栏的时间。
         * 每次强制重绑后计时重置，所以最多每 10 秒一次，不会形成抖动。
         */
        private const val A11Y_FORCE_REBIND_AFTER_MS = 10_000L

        /**
         * 持续这么久没有画面就丢掉 CameraX provider 重新获取（硬重建，v5.7）。
         *
         * [FRAME_TIMEOUT_MS] 是 3 秒、[REBIND_GRACE_MS] 是 2.5 秒，所以普通的
         * "重绑一次就好"根本到不了这里；能持续 12 秒没帧的，只可能是 provider 那层坏了，
         * 而那种情况重绑一万次也没用——必须重新 `getInstance`。
         */
        private const val FRAME_HARD_RESYNC_AFTER_MS = 12_000L

        /** 硬锁定只在这么近的距离启用（脸高占画面比例）。 */
        private const val HARD_LOCK_NEAR_RATIO = 0.55f

        /** 连续静止这么久进入硬锁定。 */
        private const val HARD_LOCK_STILL_MS = 1200L

        /** 一帧内超过这些变化就认为「人在动」，硬锁定解除。 */
        private const val HARD_LOCK_PITCH_JUMP_DEG = 0.8f
        private const val HARD_LOCK_YAW_JUMP_DEG = 1.2f
        private const val HARD_LOCK_EYE_JUMP = 0.35f

        /**
         * How long the camera stays bound (but idle) after leaving a target app.
         *
         * Reopening a camera costs hundreds of milliseconds; keeping it open is
         * what makes the next entry instant. After this window the use case is
         * released for real.
         */
        private const val WARM_WINDOW_MS = 30_000L

        @Volatile
        var instance: GazeCameraService? = null
            private set

        fun isRunning(): Boolean = instance != null

        fun start(ctx: Context) {
            val intent = Intent(ctx, GazeCameraService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(ctx, intent)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, GazeCameraService::class.java))
        }

        /** Rebuild the camera pipeline without stopping the service. */
        fun restart(ctx: Context) {
            val intent = Intent(ctx, GazeCameraService::class.java).setAction(ACTION_RESTART)
            ContextCompat.startForegroundService(ctx, intent)
        }

        /**
         * Start the foreground service if it is not already alive.
         *
         * Called by the accessibility service the instant a target app reaches the
         * front, and by the foreground watchdog on every poll. This is what makes
         * "just tap the Douyin icon" work: an accessibility service is bound by the
         * system, so the app is not treated as a plain background app and a
         * camera-type foreground service can be started from here.
         */
        fun ensureRunning(ctx: Context) {
            if (isRunning()) return
            runCatching { start(ctx) }
                .onFailure { Log.w("GazeCameraService", "ensureRunning failed: ${it.message}") }
        }

        /**
         * 活跃探针：确保「允许翻页」时整条流水线**真的在跑**（v5.3）。
         *
         * 这是「隔一会重开抖音必须下拉状态栏才生效」的根因修复。以前恢复只在**前台包名
         * 变化**时触发，于是状态里记的前台已经是抖音时（或这次切换没被观察到）就出现死角：
         * 包名没变 → 不通知服务 → 相机不重绑 → 静默失效。下拉状态栏之所以"有效"，是因为
         * 它人为制造了两次包名变化（systemui → 抖音）。
         *
         * 现在由 [AppStateManager] 每 800ms 的轮询主动探测实际状态，不再依赖事件。
         */
        fun ensurePipelineAlive(ctx: Context) {
            val svc = instance
            if (svc == null) {
                Log.w("GazeCameraService", "liveness probe: service missing — starting it")
                runCatching { start(ctx) }
                    .onFailure { Log.w("GazeCameraService", "liveness probe: start failed: ${it.message}") }
                return
            }
            svc.onLivenessProbe()
        }    }
    private val running = AtomicBoolean(false)

    private var analysisExecutor: ExecutorService? = null
    private var swipeExecutor: ExecutorService? = null
    private var analyzer: FaceGazeAnalyzer? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var stateMachine: GazeStateMachine? = null
    private var blinkDetector: BlinkDetector? = null
    private var headPoseDetector: HeadPoseDetector? = null

    /** 手机自身运动监测（v5.13）：区分「点头」与「急停/急刹」。 */
    private var phoneMotion: PhoneMotionMonitor? = null

    /**
     * 全局触发冷却闸门——点头和眨眼**共用**这一个计时器。
     *
     * 它是所有触发路径的必经出口（见 [fireSwipe]）。逐帧检测下一次动作原本会命中
     * 3~5 帧，加上点头时眼睛被遮挡会连带触发眨眼，才会出现「一次动作翻好几页」
     * 和「一会儿上滑一会儿下拉」。放在这里统一节流，两个检测器就无法互相绕过。
     */
    private val globalGate = GlobalTriggerGate()

    /**
     * 张嘴一次 → 在屏幕中央注入一次单击（v4.6）。
     *
     * 抖音这类全屏播放器里，单击屏幕中央就是暂停/播放，所以这个动作**交给目标 App 去
     * 响应**，而不是冻结 GazeScroll 自己——v4.5 的「张嘴暂停 App」在实机上被证明没用：
     * 暂停之后连同上下左右滑动一起失效了。
     *
     * 防连发完全由检测器负责：**必须闭嘴之后再次张嘴**才会产生下一个事件，所以一次
     * 持续的张嘴只会点一下；同时它也不走 [globalGate]（控制指令，被冷却挡住说不过去），
     * 更不会影响眨眼 / 点头 / 扭头这些翻页动作。
     */
    /** 累计注入的「张嘴点击屏幕中央」次数。 */
    @Volatile
    private var mouthTapCount = 0

    private var mouthDetector: MouthOpenDetector? = null

    /**
     * 遮挡抑制的截止时刻（v4.9）。
     *
     * 手 / 拳头 / 杯子挡到脸上时必须停掉所有翻页判定：遮挡前后 ML Kit 读到的姿态完全
     * 不是一回事，中间那一跳会被算成一次「快速动作」，于是手一拿开就自动滑一下。
     *
     * v4.8 只用「有人脸但读不出嘴关键点」判遮挡，**整只手盖住脸**这条路径漏掉了——
     * 那时人脸是直接消失的。v4.9 补上人脸丢失状态机与尺寸骤变两个判据。
     */
    private var occlusionUntilMs = 0L

    /** 累计识别到的遮挡次数，仅用于诊断。 */
    private var occlusionEvents = 0

    /** 人脸开始消失的时刻；0 表示脸还在。 */
    private var faceLostAtMs = 0L

    /** 上一次遮挡判定的时刻，用来识别「反复遮挡」并延长抑制。 */
    private var lastOcclusionAtMs = 0L

    /** Tracks the enable switch so the detector is only reset on a real transition. */
    private var headPoseActive = false

    /** Timestamp of the last GazeDiag line. */
    private var lastDiagnosticAtMs = 0L

    /** Timestamp of the newest analysed frame; drives the self-heal watchdog. */
    @Volatile
    private var lastFrameAtMs = 0L

    /** When the current analysis run began, used as the watchdog's first reference. */
    private var analysisStartedAtMs = 0L

    /**
     * 看门狗在这个时刻之前不判「无画面」。刚重新绑定相机时用，见 [REBIND_GRACE_MS]。
     */
    private var analysisGraceUntilMs = 0L

    /** Consecutive unproductive rebinds, to stop a runaway retry loop. */
    private var restartAttempts = 0

    /** 活跃探针连续失败次数（v5.3），用于递进升级恢复手段。 */
    private var probeFailures = 0

    /** 亮屏恢复时相机获取的重试计数（v5.4）。 */
    private var wakeRetryAttempt = 0

    /** 是否已经排了一次重试（v5.5），避免成功/失败/超时三条路径重复排程。 */
    private var retryScheduled = false

    /** 上次打全链路自检日志的时刻（v5.5）。 */
    private var lastSelfCheckAtMs = 0L

    /** 连续注入失败次数（v5.5），用于触发无障碍强制重连。 */
    private var injectionFailures = 0

    private var frameWatchdog: Runnable? = null

    /** Schedules the release of the warm camera. */
    private val warmHandler = Handler(Looper.getMainLooper())

    /** False while the screen is off, in which case the camera is unbound. */
    @Volatile
    private var screenActive: Boolean = true

    private val mainHandler = Handler(Looper.getMainLooper())

    /** True while the CameraX use case is actually bound. */
    private var cameraBound = false

    /**
     * Foreground-app changes. Fired on the main thread by [GazeStateManager].
     *
     * v4.7：**只要「允许翻页」变成真，就做一次强恢复**，而不是依赖某个布尔值刚好从
     * false 翻到 true。用户反馈的「从桌面切回抖音却要手动滑一下才生效」正是漏掉这个
     * 时机造成的：当时 targetActive 已经是 true（或状态没变），于是什么都没重新武装，
     * 而相机 / 检测器还停在待机的姿态里。
     */
    /**
     * 上一次看到的前台包名（v5.7），只用来把「从哪切到哪」打进日志。
     *
     * 用户明确要求日志里有 `foreground change: com.miui.home -> com.ss.android.ugc.aweme`
     * 这样的标记：排查「重开抖音不触发」时，第一件事就是确认这次切换**到底有没有被观察到**。
     */
    @Volatile
    private var lastSeenForeground: String? = null

    private val appStateListener: (Boolean, String) -> Unit = { active, reason ->
        val pkg = AppStateManager.foregroundPackage
        val from = lastSeenForeground
        lastSeenForeground = pkg
        Log.i(TAG, "app state: active=$active reason=$reason pkg=$pkg")
        if (from != pkg) {
            // v5.7：显式的切换标记 —— 这是"这次切换有没有被看到"的唯一判据。
            Log.i("GazeDiag", "foreground change: $from -> $pkg (target=$active reason=$reason)")
        }
        when {
            active && reason != "package-change" -> onTargetEntered(reason)
            // 允许翻页期间只是换了应用：不用重学基准线，但必须保证相机真的在出帧。
            // v5.6：ensurePipelineForActive 现在会检查"是否已经很久没有帧"，
            // 只要没画面就强制重绑，所以切应用后功能不会悄悄掉线。
            active -> {
                Log.i(
                    "GazeDiag",
                    "window changed to $pkg -> pipeline resynced (kept baselines)",
                )
                ensurePipelineForActive("app-switch")
            }

            else -> onTargetLeft()
        }
        updateCameraState()
    }

    // ------------------------------------------------------------- lifecycle --

    override fun onCreate() {
        super.onCreate()
        GazeRuntime.config = AppPrefs.loadConfig(this)

        // 先把落盘的冷却设置灌进闸门，免得服务刚起来的那一瞬间是无冷却状态。
        GazeRuntime.config.let {
            globalGate.syncConfig(
                it.globalCooldownEnabled,
                it.globalCooldownMs,
                SystemClock.elapsedRealtime(),
            )
        }

        analyzer = FaceGazeAnalyzer(::onFrame)

        blinkDetector = BlinkDetector { reason -> fireSwipe("blink:$reason", SwipeDirection.UP) }

        // v5.13：手机自身运动监测（加速度计）。用来区分「头在转」（点头）与
        // 「整个人和手机一起顿」（急停/急刹/被撞）—— 摄像头分不出来，加速度计能（见类注释）。
        // 传感器不可用时它失败开放，isMoving() 恒为 false，不会误伤任何手势。
        phoneMotion = PhoneMotionMonitor(this).also { it.start() }

        // Nod down -> previous video, tilt up -> next video,
        // turn left/right -> horizontal swipe. 具体动作由 handleHeadEvent 派发。
        headPoseDetector = HeadPoseDetector { event ->
            handleHeadEvent(event)
        }

        // 张嘴一次 = 点击屏幕中央（v4.6）。不再暂停 App：翻页动作照常工作。
        mouthDetector = MouthOpenDetector { onMouthOpen() }

        // Legacy debug path: the two-zone "look down then up" state machine.
        stateMachine = GazeStateMachine(
            onStateChanged = { _, to -> GazeRuntime.publish { it.copy(state = to) } },
            onTrigger = { fireSwipe("视线下看上扫（调试模式）", SwipeDirection.UP) },
        )

        analysisExecutor = Executors.newSingleThreadExecutor()

        val power = getSystemService(PowerManager::class.java)
        screenActive = power?.isInteractive ?: true
        analyzer?.paused = !screenActive

        registerScreenReceiver()
        registerTestSwipeReceiver()
        createChannel()
        AppStateManager.addListener(appStateListener)
        AppStateManager.startPolling(this)
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            runCatching { shutdown() }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RESTART) {
            runCatching { if (running.get()) restartPipeline() else startDetection() }
            return START_STICKY
        }
        runCatching { startDetection() }
        return START_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        AppStateManager.removeListener(appStateListener)
        AppStateManager.stopPolling()
        runCatching { unregisterReceiver(screenReceiver) }
        unregisterTestSwipeReceiver()
        runCatching { cameraProvider?.unbindAll() }
        cameraBound = false
        runCatching { analysisExecutor?.shutdown() }
        analysisExecutor = null
        stopFrameWatchdog()
        warmHandler.removeCallbacks(deepSleep)
        phoneMotion?.stop()
        phoneMotion = null
        runCatching { swipeExecutor?.shutdown() }
        swipeExecutor = null
        analyzer?.close()
        if (instance === this) instance = null
        GazeRuntime.publish {
            GazeRuntime.Snapshot(serviceRunning = false, enabled = false, note = "服务已停止")
        }
        super.onDestroy()
    }

    // ----------------------------------------------------- screen on / off ----

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> setScreenActive(false)
                Intent.ACTION_SCREEN_ON -> {
                    setScreenActive(true)
                    onScreenAwake("ACTION_SCREEN_ON")
                }
                // 解锁。亮屏但没解锁时相机拿到的是锁屏画面，毫无意义；
                // 真正"可以用"的时刻是这里，所以恢复动作放在这个事件上最准。
                Intent.ACTION_USER_PRESENT -> {
                    setScreenActive(true)
                    onScreenAwake("ACTION_USER_PRESENT")
                }
            }
        }
    }

    /**
     * 亮屏 / 解锁后的主动恢复（v5.4）。
     *
     * 这是「息屏久了再打开抖音偶尔仍需下拉状态栏」的修复点。之前有两个死角：
     *
     *  1. [setScreenActive] 里有 `if (screenActive == active) return`——亮屏时如果标志
     *     已经是 true（息屏事件没收到、或被别处改回），整个更新流程直接被跳过；
     *  2. [updateCameraState] 只在 `!cameraBound` 时才重绑。而息屏释放相机走的路径与
     *     实际状态可能不一致（相机栈已经坏了但标志还是 true），于是永远不再重绑。
     *
     * 所以这里不再"根据标志推断该不该动"，而是**无条件做一次完整重建**：作废全部检测器
     * 与抑制状态、丢弃 provider 重新获取、失败则按 500/1000/2000ms 退避重试。
     */
    private fun onScreenAwake(trigger: String) {
        Log.i(
            "GazeCameraService",
            "$trigger -> proactive reactivation requested " +
                "(screenActive=$screenActive running=${running.get()} " +
                "cameraBound=$cameraBound provider=${cameraProvider != null} " +
                "foreground=${AppStateManager.foregroundPackage})",
        )
        wakeRetryAttempt = 0
        retryScheduled = false
        if (!running.get()) {
            // 服务不在（被系统回收）：交给活跃探针去拉起来。
            Log.i("GazeCameraService", "$trigger: service not running — will be restarted by probe")
            return
        }
        dumpSelfCheck("$trigger")
        ensureAccessibilityBound(trigger)
        // v5.7：日志里明确写出"这是一次完整重启"，并按用户要求带上 reason=user-present。
        val wakeReason = when (trigger) {
            "ACTION_USER_PRESENT" -> "user-present"
            "ACTION_SCREEN_ON" -> "screen-on"
            "power-state correction" -> "power-correction"
            else -> trigger
        }
        Log.i(
            "GazeDiag",
            "rebind reason=$wakeReason -> $trigger full restart",
        )
        rebuildPipelineNow("$trigger full pipeline restart")
    }

    /**
     * 立即重建整条相机流水线：作废检测器状态 + 丢弃 provider 重新获取。
     *
     * 与 [ensurePipelineForActive] 的区别：那个是"检查后补齐"（轻，可能什么都不做），
     * 这个是"推倒重来"（重，但能治好标志与实际不符的情况），并带退避重试。
     */
    private fun rebuildPipelineNow(reason: String) {
        resetDetectorStateForFreshStart()
        releaseCamera("$reason: dropping provider")
        cameraProvider = null
        analysisStartedAtMs = SystemClock.elapsedRealtime()
        analysisGraceUntilMs = analysisStartedAtMs + REBIND_GRACE_MS
        lastFrameAtMs = 0L
        Log.i("GazeCameraService", "rebuild pipeline: $reason")
        bindCameraWithRetry(reason)
    }

    /**
     * 获取 CameraX provider，失败按 500 / 1000 / 2000ms 退避重试（v5.5 重写）。
     *
     * 相机在息屏后可能需要一段时间才真正可用（HAL 重新打开、被别的进程占着），
     * 一次失败就放弃正是"偶发失效"的来源之一。
     *
     * 重写要点：`addListener` 的两条路径（成功/异常）与超时兜底**都会**走到这里，
     * 所以用一个 `retryScheduled` 标志保证一次失败只排一次重试，不会因为两条路径都触发
     * 而少吃一次尝试。首次是立即尝试，之后才按 [WAKE_RETRY_DELAYS_MS] 退避。
     */
    private fun bindCameraWithRetry(reason: String) {
        if (!running.get()) return
        if (cameraProvider != null) {
            // 已经有 provider 了（重试期间被别的路径补上），直接绑定即可。
            retryScheduled = false
            rebind()
            return
        }
        if (wakeRetryAttempt >= WAKE_RETRY_DELAYS_MS.size) {
            Log.w(
                "GazeCameraService",
                "camera bind gave up after ${wakeRetryAttempt} retries ($reason)",
            )
            return
        }

        val attempt = wakeRetryAttempt + 1
        val delay = if (wakeRetryAttempt == 0) 0L else WAKE_RETRY_DELAYS_MS[wakeRetryAttempt - 1]
        wakeRetryAttempt++
        retryScheduled = false
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = runCatching { future.get() }.getOrNull()
            if (provider != null) {
                cameraProvider = provider
                wakeRetryAttempt = 0
                retryScheduled = false
                rebind()
                Log.i(
                    "GazeCameraService",
                    "camera rebind attempt #$attempt succeeded, bound=$cameraBound ($reason)",
                )
            } else {
                scheduleBindRetry(reason, attempt)
            }
        }, ContextCompat.getMainExecutor(this))
        // addListener 只在 future 完成时回调；失败或迟迟不回调时靠这里的超时兜底。
        mainHandler.postDelayed({
            if (cameraProvider == null) scheduleBindRetry(reason, attempt)
        }, 1500L)
    }

    private fun scheduleBindRetry(reason: String, attempt: Int) {
        if (!running.get() || cameraProvider != null) return
        if (retryScheduled) return
        retryScheduled = true
        val delay = WAKE_RETRY_DELAYS_MS[
            (attempt - 1).coerceIn(0, WAKE_RETRY_DELAYS_MS.size - 1)
        ]
        Log.w(
            "GazeCameraService",
            "camera rebind attempt #$attempt failed, retrying in ${delay}ms ($reason)",
        )
        mainHandler.postDelayed({
            retryScheduled = false
            bindCameraWithRetry(reason)
        }, delay)
    }

    /**
     * 把所有检测器与抑制状态恢复到"刚从零开始"。
     *
     * 亮屏后必须做这件事：息屏期间人脸消失、画面变化，旧基准线、旧冷却、旧抑制窗口
     * 全都失去意义，留着只会制造误判。
     */
    private fun resetDetectorStateForFreshStart() {
        headPoseDetector?.recalibrate()
        blinkDetector?.reset()
        mouthDetector?.reset()
        globalGate.reset()
        occlusionUntilMs = 0L
        faceLostAtMs = 0L
        lastOcclusionAtMs = 0L
        hardStillSinceMs = 0L
        staticHardLock = false
        lastPitchForLock = null
        lastYawForLock = null
        lastEyeForLock = null
        restartAttempts = 0
        probeFailures = 0
    }

    /**
     * Debug-only self-test hook, registered in debug builds only. Lets the
     * gesture path be exercised from adb without a real blink.
     */
    private val testSwipeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val down = intent?.getBooleanExtra(EXTRA_TEST_DOWN, false) ?: false
            val direction = if (down) SwipeDirection.DOWN else SwipeDirection.UP

            // 可选的手动参数：用来逐组试出目标 App 认哪种滑动（v5.0）。
            val from = intent?.getFloatExtra(EXTRA_TEST_FROM, Float.NaN) ?: Float.NaN
            val to = intent?.getFloatExtra(EXTRA_TEST_TO, Float.NaN) ?: Float.NaN
            val duration = intent?.getLongExtra(EXTRA_TEST_DURATION, -1L) ?: -1L
            val manual = !from.isNaN() && !to.isNaN()

            Log.i(
                "GazeCameraService",
                "self-test swipe broadcast: $direction from=$from to=$to duration=$duration " +
                    "foreground=${AppStateManager.foregroundPackage}",
            )
            if (manual) {
                // 绕开自适应表，直接注入指定参数，这样「哪一组抖音认」可以被逐个验证。
                injectManualSwipe(direction, from, to, duration)
            } else {
                fireSwipe("ADB 自测", direction)
            }
        }
    }

    /**
     * 用显式参数注入一次纵向滑动（仅调试广播使用）。
     *
     * 不经过 [globalGate]：自测的目的就是「让这一下立刻发生」，被冷却挡掉会让逐组试验
     * 变得没法进行。
     */
    private fun injectManualSwipe(
        direction: SwipeDirection,
        fromRatio: Float,
        toRatio: Float,
        durationMs: Long,
    ) {
        if (!SwipeInjector.isReady(this)) {
            GazeRuntime.publish { it.copy(note = "自测失败：没有可用的手势方式") }
            return
        }
        val profile = VerticalSwipeProfile(
            name = "手动自测",
            fromRatio = fromRatio.coerceIn(0.02f, 0.98f),
            toRatio = toRatio.coerceIn(0.02f, 0.98f),
            durationMs = if (durationMs > 0L) durationMs else 300L,
        )
        ensureSwipeExecutor().execute {
            val ok = SwipeInjector.swipe(this, direction, profile.durationMs, profile)
            Log.i(
                "GazeA11y",
                "manual swipe $direction ${(profile.distance * 100).toInt()}% " +
                    "${profile.fromRatio}->${profile.toRatio} ${profile.durationMs}ms ok=$ok",
            )
            GazeRuntime.publish {
                it.copy(note = "自测滑动（${(profile.distance * 100).toInt()}% / ${profile.durationMs}ms）ok=$ok")
            }
        }
    }

    private fun registerTestSwipeReceiver() {
        if (!BuildConfig.DEBUG) return
        runCatching {
            ContextCompat.registerReceiver(
                this,
                testSwipeReceiver,
                IntentFilter(ACTION_TEST_SWIPE),
                ContextCompat.RECEIVER_EXPORTED,
            )
        }
    }

    private fun unregisterTestSwipeReceiver() {
        if (!BuildConfig.DEBUG) return
        runCatching { unregisterReceiver(testSwipeReceiver) }
    }

    private fun registerScreenReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            // v5.4：解锁事件。亮屏但锁屏时相机拍到的是锁屏界面，恢复动作放在解锁后更准。
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /**
     * Analysis runs only when BOTH the screen is on AND one of the user's chosen
     * target apps is in the foreground. Anything else puts the pipeline to sleep.
     */
    private fun shouldAnalyze(): Boolean =
        screenActive && (AppStateManager.targetActive || AppStateManager.forceActive)

    /** Called by the settings screen when it starts / stops forcing analysis. */
    fun refreshAnalysisState() {
        updateCameraState()
    }

    private fun setScreenActive(active: Boolean) {
        if (screenActive == active) {
            // v5.4：这里不能再静默返回。亮屏时若标志已经是 true（息屏事件没收到、
            // 或被别处改回），直接 return 会让整条更新流程被跳过——这正是"偶尔失效"
            // 的来源之一。屏幕**变亮**时无论如何都要重新校准一次。
            if (active) {
                Log.i("GazeCameraService", "screen already marked active — re-syncing pipeline anyway")
                refreshAnalysisState()
            }
            return
        }
        screenActive = active
        updateCameraState()
    }

    /**
     * The single place that reconciles the pipeline with [shouldAnalyze].
     *
     * Three tiers rather than two, because "instant" and "frugal" pull in
     * opposite directions:
     *
     *  - **running**  — analysing at full rate;
     *  - **warm**     — the camera stays bound with every frame dropped. Reopening
     *    a camera costs hundreds of milliseconds, so leaving it open is what makes
     *    the next entry instant, while ML Kit never runs so the CPU cost is ~0;
     *  - **released** — the use case is unbound, reached after [WARM_WINDOW_MS] of
     *    standby or immediately on screen-off, where power matters more.
     *
     * Note this beats the obvious "preheat at 160x120" trick: changing resolution
     * means unbinding and reopening the camera, which is exactly the latency we
     * are trying to remove. Same use case, frames dropped, is strictly faster.
     */
    private fun updateCameraState() {
        val want = shouldAnalyze()
        analyzer?.paused = !want
        warmHandler.removeCallbacks(deepSleep)

        if (!running.get()) return

        if (want) {
            // v4.7：这里不再只依赖 cameraBound 标志，而是走一次完整的「强恢复」，
            // 保证从待机切回来时相机、分析器、看门狗预算都真的就位。
            if (!cameraBound) {
                ensurePipelineForActive("camera-state-wants-active")
            }
            GazeRuntime.publish {
                it.copy(
                    analyzing = true,
                    note = if (AppStateManager.globalPaging) {
                        "全局使用翻页已开启，检测持续运行"
                    } else {
                        "目标应用在前台，检测已恢复"
                    },
                )
            }
        } else if (!screenActive) {
            releaseCamera("screen off")
            GazeRuntime.publish { it.copy(analyzing = false, note = "屏幕关闭，已释放相机") }
        } else {
            warmHandler.postDelayed(deepSleep, WARM_WINDOW_MS)
            GazeRuntime.publish {
                it.copy(analyzing = false, note = "已待机（相机预热中，可秒回）")
            }
        }
        updateNotification()
    }

    /** Runs once the warm window has elapsed with no target app in front. */
    private val deepSleep = Runnable {
        if (running.get() && !shouldAnalyze() && screenActive) {
            releaseCamera("warm window elapsed")
            GazeRuntime.publish { it.copy(note = "已待机（相机已释放）") }
        }
    }

    private fun releaseCamera(reason: String) {
        if (!cameraBound) return
        runCatching { cameraProvider?.unbindAll() }
        cameraBound = false
        Log.i("GazeCameraService", "camera released: $reason")
    }

    // ------------------------------------------------------------- detection --

    private fun startDetection() {
        if (running.get()) return
        running.set(true)

        // Notification reflects the live state: running vs waiting for a target app.
        goForeground()

        GazeRuntime.publish {
            it.copy(serviceRunning = true, enabled = true, analyzing = shouldAnalyze(), note = "启动中…")
        }

        // 首帧到达前给相机一个宽限期，别让看门狗把「正在启动」当成「卡死」。
        analysisStartedAtMs = SystemClock.elapsedRealtime()
        analysisGraceUntilMs = analysisStartedAtMs + REBIND_GRACE_MS

        // Acquire the provider unconditionally. Whether a use case is actually
        // attached is decided by updateCameraState(); if we skipped this while in
        // standby, cameraProvider would stay null and a later wake-up could never
        // bind anything.
        bindCamera()
        startFrameWatchdog()
    }

    private fun ensureExecutor(): ExecutorService {
        val current = analysisExecutor
        return if (current != null && !current.isShutdown) {
            current
        } else {
            Executors.newSingleThreadExecutor().also { analysisExecutor = it }
        }
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = runCatching { future.get() }.getOrNull()
            if (provider == null) {
                GazeRuntime.publish { it.copy(note = "CameraX 初始化失败") }
            } else {
                cameraProvider = provider
                rebind()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Bind the analysis use case. Deliberately does NOT restart per frame. */
    private fun rebind() {
        val provider = cameraProvider ?: return
        val faceAnalyzer = analyzer ?: return
        if (!running.get() || !shouldAnalyze()) return

        try {
            provider.unbindAll()

            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(ANALYSIS_SIZE)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(ensureExecutor(), faceAnalyzer)

            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                analysis,
            )
            cameraBound = true
            GazeRuntime.publish { it.copy(note = "摄像头已就绪") }
        } catch (t: Throwable) {
            cameraBound = false
            GazeRuntime.publish { it.copy(note = "相机绑定失败：${t.message}") }
        }
    }

    /**
     * Called for every frame the analyzer actually processed (ML Kit delivers
     * its task callbacks on the main thread).
     */
    private fun onFrame(frame: AnalyzedFrame) {
        val now = SystemClock.elapsedRealtime()
        lastFrameAtMs = now
        // v5.7：真的收到帧就把"持续无画面"的计时清零。这是硬重建升级判据的唯一出口，
        // 所以必须在这里做——放在重绑路径里会让它永远归零、失去意义。
        staleBeganAtMs = 0L
        val cfg = GazeRuntime.config

        if (running.get()) {
            // 先同步闸门，再打诊断日志，日志里的 cooling 才是本帧的真实状态。
            globalGate.syncConfig(cfg.globalCooldownEnabled, cfg.globalCooldownMs, now)
            // 遮挡优先于一切：挡住脸的时候不判定任何动作。
            handleOcclusion(frame, now)
            maybeLogDiagnostics(frame, cfg, now)

            // 遮挡 / 抑制窗口内：所有检测器都不喂新数据，进行中的动作也被清空，
            // 所以手拿开的瞬间不会凭遮挡前后的姿态差凑出一次滑动。
            // （注意不能 return：后面还要发布 snapshot，否则设置页会卡在旧数据上。）
            //
            // 这里**不**给 headPoseDetector 喂 null：抑制期往往超过它内部
            // 「丢脸 3 秒就整个重学」的门限，喂 null 会让它连着做两次重置。抑制开始时
            // 已经 recalibrate 过一次，保持冻结即可。
            val suppressed = now < occlusionUntilMs
            if (suppressed) {
                blinkDetector?.reset()
                mouthDetector?.onFrame(null, now)
            }
            // 近距离静止硬锁定（v5.3）：与遮挡抑制并列的第二道闸门。
            val hardLocked = updateStaticHardLock(frame, now)
            if (hardLocked) {
                blinkDetector?.reset()
            }
            val gate = suppressed || hardLocked

            // v5.11：眨眼收紧必须和头部**用同一个距离档**。v5.3 起眨眼检测器一直用自己
            // 的 `faceRatio >= 0.55` 判断近距离，而用户 30cm 实测只有 0.49~0.51 ——
            // 于是收紧从未生效（诊断行 `blinkBelow=0.41 blinkFrames=2`），
            // 而实测静止 90 秒里的误触**全部**是 `lastTrigger=blink`。
            // 头部两个轴都关掉时传 null，让眨眼检测器退回自己的瞬时比值规则。
            val headAxisAvailable = cfg.headPoseEnabled || cfg.horizontalSwipeEnabled
            if (!gate && cfg.blinkTriggerEnabled) {
                blinkDetector?.let { detector ->
                    detector.requiredBlinks = cfg.blinkTriggerCount
                    // 内部锁存跟随用户设定的全局冷却：只用于冷却期内不再累加眨眼计数，
                    // 真正的「拦不拦这一次翻页」由 globalGate 决定。两边保持一致，
                    // 免得用户把冷却调到 1 秒、实际却被旧的 2 秒挡住。
                    detector.cooldownMs =
                        if (cfg.globalCooldownEnabled) cfg.globalCooldownMs else 0L
                    detector.closedBelow = cfg.blinkClosedBelow
                    detector.openAbove = cfg.blinkOpenAbove
                    detector.requiredClosedFrames = cfg.blinkClosedFrames
                    // 距离自适应（v5.3）：近距离下收紧眨眼判定，这是 30cm 误触的主因。
                    detector.faceRatio = frame.faceRatio
                    // 距离档来自头部检测器（同一帧内头部稍后才更新，所以这里用的是上一帧的
                    // 判定 —— 档位带滞回，晚一帧无影响）。
                    detector.nearTier =
                        if (headAxisAvailable) headPoseDetector?.nearDistance else null
                    detector.onEyeProbabilities(
                        frame.leftEyeOpenProbability,
                        frame.rightEyeOpenProbability,
                        now,
                    )
                }
            }

            val head = headPoseDetector
            if (head != null) {
                // 左右扭头既要在「点头仰头」开启时可用，也允许单独开启：
                // 两条轴各自有独立开关，任一条开着就要跑检测器。
                val headAxisNeeded = cfg.headPoseEnabled || cfg.horizontalSwipeEnabled
                if (headAxisNeeded) {
                    headPoseActive = true
                    head.thresholdDeg = cfg.headPoseAngleThreshold
                    head.motionWindowMs = cfg.headPoseMotionWindowMs
                    head.holdMs = cfg.headPoseHoldMs
                    head.turnEnabled = cfg.horizontalSwipeEnabled
                    head.turnThresholdDeg = cfg.horizontalSwipeAngleThreshold
                    head.invertYaw = cfg.horizontalSwipeInvertYaw
                    head.cooldownMs =
                        if (cfg.globalCooldownEnabled) cfg.globalCooldownMs else 0L
                    head.invertPitch = cfg.headPoseInvertPitch
                    head.staticLockEnabled = cfg.staticLockEnabled
                    head.staticLockFactor = cfg.staticLockFactor
                    // v5.13：手机自身是否正在被顿挫（急停/急刹）—— 由加速度计给出，
                    // 用来把"整个人在动"和"头在转"分开。传感器不可用时恒为 false。
                    head.phoneMoving = phoneMotion?.isMoving() ?: false
                    // 距离自适应：脸越大说明凑得越近，静止门限随之抬高。
                    head.faceRatio = frame.faceRatio
                    // v5.6：绝对几何的姿态判据（下巴占比），用于近距离俯视时提升点头灵敏度。
                    head.chinRatio = frame.chinRatio
                    if (!gate) {
                        head.onHeadPose(frame.headEulerAngleX, frame.headEulerAngleY, now)
                    }
                } else if (headPoseActive) {
                    // Switched off: drop the baseline so it re-learns on re-enable.
                    headPoseActive = false
                    head.reset()
                }
            }

            val machine = stateMachine
            if (!gate && cfg.gazeModeEnabled && machine != null) {
                machine.config = cfg
                machine.update(frame.gaze, now)
            } else if (machine != null && machine.state != GazeState.IDLE) {
                machine.reset()
            }

            // 张嘴检测（v4.5）。开关关闭时把检测器复位，免得下次打开时带着旧基准。
            // 遮挡期间传 null，让检测器知道「这一帧没有有效数据」而不是「嘴闭着」。
            if (!gate) {
                mouthDetector?.let { mouth ->
                    if (cfg.mouthTapEnabled) {
                        mouth.sensitivity = cfg.mouthSensitivity.fraction
                        mouth.onFrame(frame.mouthOpenRatio, now)
                    } else if (mouth.calibrated || mouth.smoothedRatio != null) {
                        mouth.reset()
                    }
                }
            }
        }

        val detector = blinkDetector
        val machine = stateMachine
        GazeRuntime.publish { s ->
            s.copy(
                faceDetected = frame.faceDetected,
                eyeY = frame.gaze.meanY,
                rawEyeY = frame.gaze.rawMeanY,
                state = if (cfg.gazeModeEnabled) (machine?.state ?: GazeState.IDLE) else GazeState.IDLE,
                blinkCount = detector?.blinkCount ?: 0,
                standby = frame.standby,
                analyzing = screenActive,
                leftEyeOpen = frame.leftEyeOpenProbability,
                rightEyeOpen = frame.rightEyeOpenProbability,
                headAngleDeg = frame.headEulerAngleX,
                headBaselineDeg = headPoseDetector?.takeIf { it.calibrated }?.baselineDeg,
                headYawDeg = frame.headEulerAngleY,
                turnCount = headPoseDetector?.turnCount ?: 0,
                mouthRatio = mouthDetector?.smoothedRatio,
                mouthBaseline = mouthDetector?.baselineRatio,
                mouthOpen = mouthDetector?.mouthOpen ?: false,
                mouthOpenCount = mouthDetector?.openCount ?: 0,
                mouthTapCount = mouthTapCount,
                swipeProfile = AdaptiveSwipe.describe(
                    cfg.adaptiveSwipeEnabled,
                    AppStateManager.foregroundPackage,
                    cfg.listSwipeDistance,
                ),
                // 冷却倒计时，设置页用来直观展示「防误触冷却」正在生效。
                cooldownRemainMs = globalGate.remainingMs(now),
            )
        }
    }

    /**
     * Blink or head pose fired: inject one swipe in [direction].
     *
     * 这里是**所有**触发路径的唯一汇合点——眨眼、点头、仰头、左右扭头、调试用的视线
     * 模式、adb 自检广播都从这里过。所以全局冷却闸门就卡在这一步：摄像头逐帧检测会让
     * 一次真实动作连续命中好几帧（点头时眼睛还会被连带判成眨眼），闸门放行一次后
     * 立刻进入冷却，冷却期内后来的信号一律被丢弃，从而保证「一次动作 = 一次翻页」，
     * 也不会再出现上下滑交替触发。
     *
     * 纵向滑动的**幅度与时长在注入那一刻才决定**（[AdaptiveSwipe]），所以用户在冷却
     * 期间切换了前台应用也不会用错参数。
     *
     * [SwipeInjector.swipe] blocks for ~150 ms while the shell command runs, so
     * it must not be called from the main thread.
     */
    private fun fireSwipe(reason: String, direction: SwipeDirection) {
        // 闸门同时负责「判断是否在冷却」和「开始新一轮冷却」，两者是原子的：
        // 同一帧里第二个检测器再来问，必然得到 false。
        val now = SystemClock.elapsedRealtime()
        if (!globalGate.allow(now)) {
            val remaining = globalGate.remainingMs(now)
            GazeRuntime.publish {
                it.copy(note = "冷却中，已忽略「$reason」（还剩 ${"%.1f".format(remaining / 1000.0)} 秒）")
            }
            return
        }
        if (!SwipeInjector.isReady(this)) {
            GazeRuntime.publish { it.copy(note = "没有可用的翻页方式（Shizuku / 无障碍都没就绪）") }
            return
        }
        val label = direction.label
        // 触发来源（v5.3）：诊断行里直接显示「上一次翻页是谁触发的」。
        // 用户反馈「30cm 静止疯狂误触」时，光看触发次数无法区分是眨眼还是点头，
        // 这一条让问题当场可定位。
        lastTriggerSource = reason.substringBefore(':')
        lastTriggerReason = reason
        lastTriggerAtMs = SystemClock.elapsedRealtime()
        ensureSwipeExecutor().execute {
            val cfg = GazeRuntime.config
            // 纵向：按当前前台应用挑参数（抖音整屏切换、微博/小红书柔性小幅滚动）。
            // 横向：不参与自适应，沿用固定的一套（用户要求保持现状）。
            val profile = if (direction.isHorizontal) {
                null
            } else {
                AdaptiveSwipe.profileFor(
                    cfg.adaptiveSwipeEnabled,
                    AppStateManager.foregroundPackage,
                    cfg.listSwipeDistance,
                )
            }
            val ok = SwipeInjector.swipe(
                this,
                direction,
                cfg.swipeDurationMs,
                profile,
            )
            if (!ok) {
                // Backend may have been unbound (app force-stopped / killed).
                SwipeInjector.repair(this)
                onInjectionFailure("swipe $direction")
            }
            GazeRuntime.publish {
                val count = it.triggers + 1
                val how = profile?.let { p ->
                    "（${p.name} ${(p.distance * 100).toInt()}% / ${p.durationMs}ms）"
                } ?: ""
                it.copy(
                    triggers = count,
                    note = if (ok) {
                        "已${label}滑 #$count$how · $reason"
                    } else {
                        "手势注入失败，正在尝试自动修复（${SwipeInjector.activeBackend(this)}）"
                    },
                )
            }
            // 注入成功就把失败计数清零，这样"偶发一次"不会累积成误判。
            if (ok) injectionFailures = 0
        }
    }

    /**
     * 手部 / 异物遮挡识别（v4.9）。
     *
     * 三条判据，命中任意一条就进入抑制，覆盖「手放到脸上」的全部典型形态：
     *
     *  1. **脸突然消失又回来**（[OcclusionReason.FACE_LOST]）：手掌、拳头整个盖住脸时
     *     人脸检测直接丢失。这恰恰是 v4.8 漏掉的那条路径——它只判「有人脸但没嘴」。
     *  2. **有人脸但读不出嘴的关键点**（[OcclusionReason.MOUTH_MISSING]）：手挡下半脸。
     *  3. **人脸框尺寸一帧内骤变**（[OcclusionReason.FACE_SIZE_JUMP]）：有东西贴上镜头。
     *
     * 为什么要抑制而不是「重新学基准线」就够：遮挡前后姿态差可能很大，如果只是重学
     * 基准线，学习期间那几帧照样会拿旧基准去比、照样会凑出一次假动作。所以在画面重新
     * 稳定之前（[OCCLUSION_SUPPRESS_MS]）直接不接受任何触发。
     */
    private fun handleOcclusion(frame: AnalyzedFrame, now: Long) {
        if (!frame.faceDetected) {
            // 脸的消失要计时：短暂消失 = 被挡住，长时间消失 = 用户离开了画面。
            if (faceLostAtMs == 0L) faceLostAtMs = now
            return
        }

        val lostFor = if (faceLostAtMs != 0L) now - faceLostAtMs else 0L
        faceLostAtMs = 0L
        val brieflyLost = lostFor in 1..FACELOST_OCCLUSION_MAX_MS
        val reason = when {
            brieflyLost -> OcclusionReason.FACE_LOST
            else -> frame.occlusionReason
        }
        if (reason == null) return
        // 已经在抑制窗口里就不用反复重置了。
        if (now < occlusionUntilMs) return

        // 脸丢得越久，画面重建需要的稳定时间越长（整只手拿开比手指移开更"翻天覆地"）。
        // 反复遮挡（手在脸前晃动）会叠加：如果上一次抑制刚结束不久又来了，就翻倍，
        // 这样"手拿开又放回去"的连击不会在两次抑制的空隙里漏出一次假动作。
        val baseSuppress = if (brieflyLost && lostFor > 150L) {
            OCCLUSION_SUPPRESS_LONG_MS
        } else {
            OCCLUSION_SUPPRESS_MS
        }
        val repeated = now - lastOcclusionAtMs < OCCLUSION_REPEAT_WINDOW_MS
        val suppressMs = if (repeated) {
            (baseSuppress * 2).coerceAtMost(OCCLUSION_SUPPRESS_MAX_MS)
        } else {
            baseSuppress
        }
        lastOcclusionAtMs = now
        occlusionUntilMs = now + suppressMs
        occlusionEvents++
        headPoseDetector?.recalibrate()
        blinkDetector?.reset()
        // 张嘴检测也要重学：手挡脸时读出的嘴部数据完全不可信，v5.0 实测它会读出
        // 0.114 这种极小值并把基准永久带偏，于是「一直以为用户在张嘴」。
        mouthDetector?.reset()
        globalGate.reset()
        Log.i(
            "GazeDiag",
            "occlusion detected (reason=${reason.label}, faceLostFor=${lostFor}ms) " +
                "-> suppress ${suppressMs}ms (#${occlusionEvents})",
        )
    }

    private fun ensureSwipeExecutor(): ExecutorService {
        val current = swipeExecutor
        return if (current != null && !current.isShutdown) {
            current
        } else {
            Executors.newSingleThreadExecutor().also { swipeExecutor = it }
        }
    }

    // --------------------------------------------------------- 头部动作分发 --

    /**
     * 头部动作的统一入口。
     *
     * - 低头 → 手指向下滑（上一个）
     * - 仰头 → 手指向上滑（下一个）
     * - 左扭头 / 右扭头 → 左右滑动
     *
     * 三种都过 [globalGate]，所以不可能出现上下和左右同时滑动。
     */
    private fun handleHeadEvent(event: HeadEvent) {
        when (event) {
            is HeadEvent.NodDown -> fireSwipe("nod:${event.reason}", SwipeDirection.DOWN)
            is HeadEvent.TiltUp -> fireSwipe("tilt:${event.reason}", SwipeDirection.UP)
            is HeadEvent.TurnLeft -> fireSwipe("turnL:${event.reason}", SwipeDirection.LEFT)
            is HeadEvent.TurnRight -> fireSwipe("turnR:${event.reason}", SwipeDirection.RIGHT)
        }
    }

    /**
     * 张嘴 → 在屏幕中央点一下。
     *
     * **有意不过 [globalGate]**：这是控制指令不是翻页动作，想暂停视频却要先等冷却结束
     * 是说不过去的。防连发由 [MouthOpenDetector] 保证——必须闭嘴之后再次张嘴才算下一次，
     * 所以一直张着嘴也只会点一下。
     *
     * 也**不设置任何暂停状态**：眨眼、点头、左右扭头在张嘴之后照常工作。
     */
    private fun onMouthOpen() {
        if (!SwipeInjector.isReady(this)) {
            mouthTapCount++
            GazeRuntime.publish {
                it.copy(
                    mouthTapCount = mouthTapCount,
                    note = "张嘴已识别，但没有可用的手势方式（Shizuku / 无障碍都没就绪）",
                )
            }
            return
        }
        ensureSwipeExecutor().execute {
            val ok = SwipeInjector.tapCenter(this)
            val backend = SwipeInjector.activeBackend(this)
            if (!ok) {
                // 后端可能刚被解绑（App 被强停 / 被杀），给它一次自愈机会。
                SwipeInjector.repair(this)
                onInjectionFailure("tap center")
            }
            GazeRuntime.publish {
                val count = it.mouthTapCount + 1
                it.copy(
                    // 成功和失败都要计数：否则界面上「已点击 0 次」既可能表示没识别到张嘴，
                    // 也可能表示识别到了但注入失败，而这两种情况的排查方向完全不同。
                    mouthTapCount = count,
                    note = if (ok) {
                        "张嘴：已点击屏幕中央 #$count（抖音等可暂停/播放）"
                    } else {
                        "张嘴已识别，但点击注入失败（后端：$backend，正在尝试自动修复）"
                    },
                )
            }
            Log.i(
                "GazeDiag",
                "mouth tap: ok=$ok backend=$backend a11yConnected=${GazeAccessibilityService.isConnected()}",
            )
        }
    }

    /**
     * Throttled live view of the whole detection pipeline, so the behaviour can be
     * checked from a PC without the app being in the foreground:
     *
     *   adb logcat -s GazeDiag:V HeadPose:V GazeA11y:V
     *
     * `pitch` / `yaw` are ML Kit's raw `headEulerAngleX` / `headEulerAngleY` —
     * nod and turn your head and watch which way they move to confirm the signs on
     * this device. `mouth` is the raw mouth ratio and `mouthBase` the learned
     * closed-mouth baseline, so the sensitivity can be chosen from real numbers.
     */
    private fun maybeLogDiagnostics(frame: AnalyzedFrame, cfg: GazeConfig, now: Long) {
        if (now - lastDiagnosticAtMs < DIAGNOSTIC_INTERVAL_MS) return
        lastDiagnosticAtMs = now
        val mouth = mouthDetector
        Log.i(
            "GazeDiag",
            "face=${frame.faceDetected}" +
                " eyeL=${frame.leftEyeOpenProbability?.let { "%.2f".format(it) } ?: "-"}" +
                " eyeR=${frame.rightEyeOpenProbability?.let { "%.2f".format(it) } ?: "-"}" +
                " pitch=${frame.headEulerAngleX?.let { "%.1f".format(it) } ?: "-"}" +
                " base=${headPoseDetector?.baselineDeg?.let { "%.1f".format(it) } ?: "-"}" +
                " yaw=${frame.headEulerAngleY?.let { "%.1f".format(it) } ?: "-"}" +
                " yawBase=${headPoseDetector?.baselineYawDeg?.let { "%.1f".format(it) } ?: "-"}" +
                " mouth=${frame.mouthOpenRatio?.let { "%.3f".format(it) } ?: "-"}" +
                " mouthPx=${frame.mouthNoseGapPx?.let { "%.0f".format(it) } ?: "-"}" +
                " mouthBase=${mouth?.baselineRatio?.let { "%.3f".format(it) } ?: "-"}" +
                " mouthOpen=${mouth?.mouthOpen ?: false}" +
                " headPose=${cfg.headPoseEnabled}" +
                " hSwipe=${if (cfg.horizontalSwipeEnabled) "${cfg.horizontalSwipeAngleThreshold.toInt()}°" else "off"}" +
                " mouthTap=${if (cfg.mouthTapEnabled) cfg.mouthSensitivity.name else "off"}" +
                " listSwipe=${Math.round(cfg.listSwipeDistance * 100)}%" +
                " swipe=${AdaptiveSwipe.profileFor(cfg.adaptiveSwipeEnabled, AppStateManager.foregroundPackage, cfg.listSwipeDistance).name}" +
                " mouthTaps=$mouthTapCount" +
                " globalPaging=${AppStateManager.globalPaging}" +
                // v4.9 距离与防误触状态：脸占比 / 距离档 / 动态静止门限 / 遮挡抑制。
                " faceRatio=${frame.faceRatio?.let { "%.2f".format(it) } ?: "-"}" +
                " dist=${frame.distanceLabel}" +
                " staticLock=${headPoseDetector?.staticLocked ?: false}" +
                " staticRange=${"%.1f".format(headPoseDetector?.staticRangeDeg ?: 0f)}°" +
                // v5.3：近距离静止硬锁定状态。
                " staticHardLock=$staticHardLock" +
                // v5.0：回中锁定 / 张嘴基准健康度。
                " recenterLock=${headPoseDetector?.recenterLocked ?: false}" +
                // v5.4：俯视姿态补偿与一般姿势校正的次数。
                " downGaze=${headPoseDetector?.downGazeCount ?: 0}" +
                " biasRecenter=${headPoseDetector?.biasRecenterCount ?: 0}" +
                // v5.5：俯仰/偏航阈值的解耦验证 —— 近距离时前者不变、后者翻倍即为正确。
                // v5.7：pitchTh 是**当前方向**的阈值，pitchThUp 是仰头方向的阈值。
                //       近距离下 nodTh < upTh 就说明单向增益生效（点头更灵、仰头照旧）。
                " pitchTh=${"%.1f".format(headPoseDetector?.pitchThresholdDeg ?: 0f)}°" +
                " pitchThUp=${"%.1f".format(headPoseDetector?.pitchThresholdUpDeg ?: 0f)}°" +
                " yawTh=${"%.1f".format(headPoseDetector?.yawThresholdDeg ?: 0f)}°" +
                // v5.8：yaw 与 pitch 的实时读数一起打出来，扭头是"没到阈值"还是"带出了俯仰"
                // 一眼可见；pitchYielded 表示俯仰因为扭头信号更强而让位。
                " yawNow=${"%.1f".format(headPoseDetector?.lastSignedYaw ?: 0f)}°" +
                " pitchNow=${"%.1f".format(headPoseDetector?.lastSignedPitch ?: 0f)}°" +
                " pitchYielded=${headPoseDetector?.pitchYieldedToYaw ?: false}" +
                " downGazeActive=${headPoseDetector?.downGazeActive ?: false}" +
                // v5.6：绝对几何的距离/姿态判定 —— chinRatio 是俯视判据的原始读数。
                " chinRatio=${headPoseDetector?.smoothedChinRatio?.let { "%.3f".format(it) } ?: "-"}" +
                " chinMed=${headPoseDetector?.chinRatioMedian?.let { "%.3f".format(it) } ?: "-"}" +
                // 注意字段名别叫 dist：前面已有 dist=近/中/远（握持距离档），重名会看错。
                " distMode=${if (headPoseDetector?.nearDistance == true) "near" else "far"}" +
                // v5.9：距离档的唯一真值（EMA 平滑后的脸占比）。之前这里只有瞬时
                // faceRatio，档位在 0.50/0.55 两条线之间逐帧乱跳完全看不出来；现在
                // 拿 frSm 与 distMode 一对照，就能确认档位是稳的。
                " frSm=${headPoseDetector?.smoothedFaceRatio?.let { "%.2f".format(it) } ?: "-"}" +
                // v5.12：晃动判定的路径效率（|净位移|/Σ|相邻差值|）。接近 1 = 单调推进（有意动作），
                // 越低越像来回晃（地铁/手抖）。它同时是「地铁上到底算不算晃」的判读依据。
                " shake=${"%.2f".format(headPoseDetector?.shakeEfficiency ?: 1f)}" +
                // v5.13：手机自身运动的峰值（m/s²）与"是否正在被顿挫"。
                // 坐着刷 ≈0~0.5，走路 ≈1.5~3，急刹 ≈4~15 —— 门限 3.5 就是照这个定的，
                // 所以地铁上/走路时到底读到多少可以直接看这一行，下一版调门限有实测数字。
                " accel=${"%.1f".format(phoneMotion?.recentPeak(now) ?: 0f)}" +
                " phoneMotion=${phoneMotion?.isMoving() ?: false}" +
                " accelReady=${phoneMotion?.available ?: false}" +
                " posture=${headPoseDetector?.postureLabel ?: "-"}" +
                // 打实际生效的增益，而不是常量，这样与 pitchTh 永远自洽。
                " nodBoost=${"%.2f".format(headPoseDetector?.lastAppliedNodBoost ?: 1f)}" +
                " nodBoostActive=${(headPoseDetector?.lastAppliedNodBoost ?: 1f) < 1f}" +
                " speedGate=${"%.4f".format(headPoseDetector?.pitchSpeedGate ?: 0f)}°/ms" +
                " mouthForced=${mouthDetector?.forcedReloads ?: 0}" +
                " mouthRejected=${mouthDetector?.rejectedSamples ?: 0}" +
                " occl=${frame.occlusionReason?.label ?: "none"}" +
                " suppressMs=${(occlusionUntilMs - now).coerceAtLeast(0L)}" +
                " occlEvents=$occlusionEvents" +
                // v5.3：上一次翻页是谁触发的，以及眨眼判定当前实际用的阈值。
                " lastTrigger=$lastTriggerSource" +
                " lastTriggerAgeMs=${if (lastTriggerAtMs == 0L) -1L else now - lastTriggerAtMs}" +
                " blinkBelow=${"%.2f".format(blinkDetector?.effectiveClosedBelow ?: 0f)}" +
                " blinkFrames=${blinkDetector?.effectiveRequiredClosedFrames ?: 0}" +
                " blinks=${blinkDetector?.blinkCount ?: 0}" +
                " triggers=${GazeRuntime.snapshot.triggers}" +
                " needBlinks=${cfg.blinkTriggerCount}" +
                " pending=${blinkDetector?.pendingBlinks ?: 0}" +
                // 冷却是否在拦：跑 adb logcat -s GazeDiag:V 时能直接看到还剩多少毫秒。
                " cooldown=${if (cfg.globalCooldownEnabled) "${cfg.globalCooldownMs}ms" else "off"}" +
                " cooling=${globalGate.remainingMs(now)}" +
                " standby=${frame.standby}",
        )
    }

    // ------------------------------------------------------- self-heal / entry --

    /** 服务自身日志用的 tag，[TAG] 同时用于「窗口切换 → 检测重新武装」这类关键行。 */
    private val TAG = "GazeCameraService"

    /**
     * **强恢复**：允许翻页期间保证整条流水线真的在跑。
     *
     * 这是 v4.7 针对「切回目标应用要手动滑一下才生效」的核心修复。以前进入目标应用
     * 只依赖 `if (!cameraBound) rebind()`，只要 `cameraBound` 因为任何原因停在 true
     * （绑定其实已经失效、重建过程中把标志写回、或 provider 还没就绪），就再也不会
     * 重新绑定，于是「服务在跑、通知正常、摄像头没画面」——正好表现为必须手动操作一下
     * 才恢复。这里改成：进入允许翻页的界面时，检查**实际状态**并补齐，同时把
     * 看门狗的预算重置，绝不放弃自愈。
     */
    private fun ensurePipelineForActive(reason: String) {
        if (!running.get()) return
        val now = SystemClock.elapsedRealtime()
        // 先判断"之前到底有没有画面"，再重置计时 —— 顺序反了就永远看不出卡死。
        val previous = if (lastFrameAtMs != 0L) lastFrameAtMs else analysisStartedAtMs
        val wasStale = previous == 0L || now - previous > FRAME_TIMEOUT_MS

        restartAttempts = 0
        probeFailures = 0
        analysisStartedAtMs = now
        // 给新绑定的相机一段宽限期，避免看门狗把「正在绑定」误判成「卡死」。
        analysisGraceUntilMs = now + REBIND_GRACE_MS
        lastFrameAtMs = 0L
        analyzer?.resetSmoothing()

        if (cameraProvider == null) {
            // provider 还没就绪（服务刚起来）：重新走一次获取流程，成功后会自动绑定。
            Log.i(TAG, "pipeline resync ($reason): camera provider not ready, re-acquiring")
            bindCameraWithRetry("$reason: provider missing")
            return
        }

        // v5.6：**cameraBound 为 true 但已经很久没有帧**时，以前这里什么都不做
        // （"camera already bound"），于是卡死状态会被反复"确认无事"而永远不自愈——
        // 这就是"屏幕常亮时偶尔失效"的成因之一。现在只要没有画面就强制重绑，
        // 不再信任这个标志位。
        if (!cameraBound || wasStale) {
            Log.i(
                TAG,
                "pipeline resync ($reason): forcing rebind " +
                    "(cameraBound=$cameraBound wasStale=$wasStale)",
            )
            releaseCamera("$reason: forced rebind")
            rebind()
        } else {
            Log.i(TAG, "pipeline resync ($reason): camera already bound and frames recent")
        }
    }

    /**
     * Entering an allowed (target or global) app.
     *
     * Everything that could make the first seconds feel dead is reset here: the
     * blink and gaze state machines drop their history, the head baseline is
     * seeded from the previous session so a nod works immediately, and the camera
     * is bound right away instead of waiting for the next poll.
     */
    private fun onTargetEntered(reason: String = "enter") {
        Log.i(
            "GazeDiag",
            "window changed to ${AppStateManager.foregroundPackage} -> detector reactivated (reason=$reason)",
        )
        blinkDetector?.reset()
        // 切回目标应用时丢掉上一次的半截头部动作状态：否则在别的界面动了一下头、
        // 进来又动一下，会被当成同一次动作的延续。
        headPoseDetector?.let { head ->
            val previous = AppPrefs.headBaseline(this)
            if (previous != null) head.seedBaseline(previous) else head.recalibrate()
        }
        stateMachine?.reset()
        mouthDetector?.reset()
        globalGate.reset()
        ensurePipelineForActive(reason)
    }

    /** Leaving a target app: remember the baseline so the next entry is instant. */
    private fun onTargetLeft() {
        headPoseDetector?.takeIf { it.calibrated }?.let {
            AppPrefs.setHeadBaseline(this, it.baselineDeg)
        }
    }

    /**
     * Self-heal for a wedged pipeline.
     *
     * If we should be analysing but not a single frame has arrived within
     * [FRAME_TIMEOUT_MS], the CameraX binding is dead — which is exactly what a
     * force-stopped-then-revived process used to end up as: the service alive, the
     * notification showing "running", and no picture at all. Rebind instead of
     * sitting there silently broken, with a small retry budget.
     */
    /** 上一次翻页的触发来源 / 原因 / 时刻（v5.3），诊断用。 */
    @Volatile
    private var lastTriggerSource: String = "none"

    @Volatile
    private var lastTriggerReason: String = ""

    @Volatile
    private var lastTriggerAtMs: Long = 0L

    /**
     * 近距离静止硬锁定（v5.3）。
     *
     * 第三道防线，专治用户反馈的「30cm 静止疯狂误触」。前两道是：眨眼判定按距离收紧
     * （阈值 0.55→0.30、连续帧 3→6），以及头部的静止锁定 + 姿势偏置校正。
     *
     * 这一道单独成立，因为它的判据不依赖任何"幅度阈值"：**近距离 + 连续 1.2 秒内俯仰、
     * 偏航、眼睛开合度全都没有实质变化** → 判定为"人根本没在动，画面在抖"，直接关掉
     * 所有翻页判定，直到出现一次明显快速的动作（角速度 ≥0.05°/ms）或眼睛开合度骤变。
     *
     * 为什么用"稳定时长"而不是"方差"：稳定时长语义更直观、不易被单个离群点影响，而且
     * 用户能理解与验证（"静止 1.2 秒后就不会误触了"）。
     */
    private var hardStillSinceMs = 0L

    @Volatile
    private var staticHardLock = false

    /** 无障碍实例缺失的起始时刻，以及累计强制重绑次数（v5.7），仅用于升级与诊断。 */
    private var a11yMissingSinceMs = 0L

    @Volatile
    private var a11yForceRebinds = 0

    /**
     * 「持续没有画面」的起始时刻（v5.7）。
     *
     * 刻意与 [analysisStartedAtMs] / [lastFrameAtMs] 分开：那两个会被每一次重绑清零，
     * 于是"重绑→宽限→重判→再重绑"可以无限循环而永远不升级。这个只在**真的收到帧**时清零，
     * 所以它如实反映"到底多久没画面了"。
     */
    private var staleBeganAtMs = 0L

    @Volatile
    private var hardResyncCount = 0

    private var lastPitchForLock: Float? = null
    private var lastYawForLock: Float? = null
    private var lastEyeForLock: Float? = null

    /** 返回 true 表示这一帧应当被硬锁定吞掉。 */
    private fun updateStaticHardLock(frame: AnalyzedFrame, now: Long): Boolean {
        // 只在近距离启用：50cm 以上本来就正常，不要平白增加限制。
        val ratio = frame.faceRatio
        if (ratio == null || ratio < HARD_LOCK_NEAR_RATIO) {
            if (staticHardLock) {
                Log.i("GazeDiag", "staticHardLock released: face no longer near (ratio=$ratio)")
            }
            hardStillSinceMs = 0L
            staticHardLock = false
            lastPitchForLock = null
            lastYawForLock = null
            lastEyeForLock = null
            return false
        }

        val pitch = frame.headEulerAngleX
        val yaw = frame.headEulerAngleY
        val eye = frame.leftEyeOpenProbability ?: frame.rightEyeOpenProbability
        val prevPitch = lastPitchForLock
        val prevYaw = lastYawForLock
        val prevEye = lastEyeForLock
        lastPitchForLock = pitch
        lastYawForLock = yaw
        lastEyeForLock = eye

        if (pitch == null || yaw == null || eye == null ||
            prevPitch == null || prevYaw == null || prevEye == null
        ) {
            hardStillSinceMs = 0L
            staticHardLock = false
            return false
        }

        val pitchJump = kotlin.math.abs(pitch - prevPitch)
        val yawJump = kotlin.math.abs(yaw - prevYaw)
        val eyeJump = kotlin.math.abs(eye - prevEye)

        if (pitchJump > HARD_LOCK_PITCH_JUMP_DEG ||
            yawJump > HARD_LOCK_YAW_JUMP_DEG ||
            eyeJump > HARD_LOCK_EYE_JUMP
        ) {
            // 出现了明显变化：解锁并重新计时。
            hardStillSinceMs = 0L
            staticHardLock = false
            return false
        }

        if (hardStillSinceMs == 0L) {
            hardStillSinceMs = now
            return false
        }
        if (now - hardStillSinceMs < HARD_LOCK_STILL_MS) return false

        if (!staticHardLock) {
            staticHardLock = true
            Log.i(
                "GazeDiag",
                "staticHardLock=true, reason=close-distance stillness, " +
                    "faceRatio=${"%.2f".format(ratio)} still for ${now - hardStillSinceMs}ms " +
                    "-> all paging triggers suppressed",
            )
        }
        return true
    }

    /**
     * 活跃检查（v5.3 引入，v5.4 加入电源状态自校正）。
     *
     * 由 [AppStateManager] 的 800ms 轮询驱动，所以**不依赖任何窗口事件**——这正是
     * 「重开抖音必须下拉状态栏才生效」的修复点。
     *
     * 三级升级，覆盖从轻到重的全部故障：
     *  1. 一次强恢复（重绑相机）；
     *  2. 连续多次仍无帧 → 丢弃 provider 重新获取（相机被别的进程占着时必需）；
     *  3. 再不行 → 重建整个服务管线。
     */
    private fun onLivenessProbe() {
        if (!running.get()) return

        // 先自校正屏幕状态，再判断该不该分析。
        // 顺序很重要：screenActive 错了会让 shouldAnalyze() 永远返回 false，
        // 后面所有恢复逻辑都会被这一个条件挡住。
        syncPowerState()

        // 定期把全链路状态打一行（每 30 秒），这样"又失效了"时可以直接回看当时卡在哪一环。
        val nowForDiag = SystemClock.elapsedRealtime()
        if (nowForDiag - lastSelfCheckAtMs >= SELF_CHECK_INTERVAL_MS) {
            lastSelfCheckAtMs = nowForDiag
            dumpSelfCheck("periodic")
        }
        // 无障碍绑定兜底：长时间待机后系统可能把它解绑，那样所有注入都会失败。
        ensureAccessibilityBound("periodic")

        if (!shouldAnalyze()) return
        val now = SystemClock.elapsedRealtime()
        // 刚恢复 / 刚绑定：正在打开相机，不算故障。
        if (now < analysisGraceUntilMs) return

        val reference = if (lastFrameAtMs != 0L) lastFrameAtMs else analysisStartedAtMs
        val stale = reference == 0L || now - reference > FRAME_TIMEOUT_MS
        if (!stale) {
            probeFailures = 0
            return
        }

        probeFailures++
        val fg = AppStateManager.foregroundPackage
        Log.w(
            "GazeCameraService",
            "Periodic check: foreground=$fg, cameraRunning=${cameraBound && !stale}, " +
                "rebind requested (no frames for ${now - reference}ms, " +
                "failures=$probeFailures cameraBound=$cameraBound " +
                "provider=${cameraProvider != null})",
        )
        // 先做帧级升级判定：它量的是"与重绑无关的持续时长"，所以必须在下面
        // 的分级处理**之前**跑，否则重绑会把证据清掉。
        escalateStaleFrames(now, reference)
        when {
            probeFailures == 1 -> {
                GazeRuntime.publish { it.copy(note = "检测无画面，正在自动恢复…") }
                // v5.6：第一次就强制重绑。以前这里调 ensurePipelineForActive，
                // 而它在 cameraBound==true 时会"确认无事"直接返回 —— 卡死状态因此
                // 要多等一轮才升级处理，用户感受到的就是"偶尔失效好几秒"。
                ensurePipelineForActive("no-frame")
            }

            probeFailures <= MAX_LIVENESS_ESCALATIONS -> {
                // 相机可能被别的进程占着：彻底丢开 provider 重新获取。
                releaseCamera("app-switch/no-frame escalation")
                cameraProvider = null
                bindCamera()
                analysisStartedAtMs = now
                analysisGraceUntilMs = now + REBIND_GRACE_MS
                lastFrameAtMs = 0L
            }

            else -> {
                // 最后手段：重建整条流水线（进程还在，但相机栈可能已经坏了）。
                Log.w("GazeCameraService", "liveness probe: rebuilding the whole pipeline")
                probeFailures = 0
                restartPipeline()
            }
        }
    }

    /**
     * 帧级升级：`shouldAnalyze()` 为真、相机也绑着，帧却**持续**不来（v5.7）。
     *
     * ## 为什么单靠 [checkFrames] 不够
     *
     * [checkFrames] 每 1 秒重绑一次，但它每次都把 [lastFrameAtMs] 和计时清了零，
     * 于是"重绑 → 宽限期 → 重判 → 再重绑"可以无限循环，**永远不会升级**。
     * 如果坏的是 CameraX 那一层（而不是绑定关系），重绑多少次都没用 —— 因为
     * `cameraProvider` 本身已经不可用了，只有丢掉它重新 `getInstance` 才能治好。
     *
     * 这里用一条**独立于重绑**的计时（[staleBeganAtMs]）来量"到底多久没画面"，
     * 所以重绑再多次也不会把它清零。超过 [FRAME_HARD_RESYNC_AFTER_MS] 就丢开
     * provider 重新获取，与人工点通知里的「重启」等价。
     */
    private fun escalateStaleFrames(now: Long, reference: Long) {
        if (staleBeganAtMs == 0L) staleBeganAtMs = reference
        val staleFor = now - staleBeganAtMs
        if (staleFor < FRAME_HARD_RESYNC_AFTER_MS) return

        hardResyncCount++
        Log.w(
            "GazeCameraService",
            "no frames for ${staleFor}ms despite rebinds (cameraBound=$cameraBound " +
                "restartAttempts=$restartAttempts) — dropping the camera provider and " +
                "re-acquiring (hard resync #$hardResyncCount)",
        )
        GazeRuntime.publish { it.copy(note = "相机长时间无画面，已彻底重建（第 $hardResyncCount 次）") }

        // 重新计时，免得同一个卡死状态每轮都触发一次硬重建。
        staleBeganAtMs = now
        retryScheduled = false
        wakeRetryAttempt = 0
        // 注意这里**不能**把 staleBeganAtMs 交给 resetDetectorStateForFreshStart 去清：
        // 那个函数是给"正常重绑"用的，会在每次重绑后调用，清掉就等于永远不升级。
        resetDetectorStateForFreshStart()
        releaseCamera("hard resync: no frames for ${staleFor}ms")
        cameraProvider = null
        analysisStartedAtMs = now
        analysisGraceUntilMs = now + REBIND_GRACE_MS
        lastFrameAtMs = 0L
        bindCameraWithRetry("hard resync: no frames for ${staleFor}ms")
    }

    /**
     * 用系统的真实电源状态校正 [screenActive]（v5.4）。
     *
     * ## 为什么必须有这一步
     *
     * [screenActive] 只在收到 `ACTION_SCREEN_ON` / `ACTION_SCREEN_OFF` 广播时才更新。而
     * HyperOS 的省电策略下这些广播**可能被丢掉**——一旦丢了，这个标志就永久停在错误值上：
     *
     *  - 广播丢了 → `screenActive` 停在 `false`；
     *  - `shouldAnalyze()` 永远返回 false；
     *  - 所以**什么都不分析**，而且 liveness 探针、看门狗、强恢复全都被这一个条件挡住；
     *  - 用户看到的就是"功能死了，下拉一下状态栏又活了"。
     *
     * 系统自己的 `PowerManager.isInteractive` 是真相来源，不受广播影响，所以定期比对一次
     * 就能把这类死角堵死。这个方法每 800ms 被调用一次，实现必须极轻。
     */
    private fun syncPowerState() {
        val power = getSystemService(PowerManager::class.java) ?: return
        val reallyActive = power.isInteractive
        if (reallyActive == screenActive) return

        Log.w(
            "GazeCameraService",
            "power state correction: screenActive=$screenActive but isInteractive=$reallyActive " +
                "— missed broadcast, re-syncing",
        )
        screenActive = reallyActive
        if (reallyActive) {
            // 屏幕其实是亮的：当作一次亮屏恢复处理。
            onScreenAwake("power-state correction")
        } else {
            updateCameraState()
        }
    }

    /**
     * 记录一次注入失败，连续失败就强制重连无障碍服务（v5.5）。
     *
     * ## 为什么要有这条反馈回路
     *
     * 之前注入失败只打印一行日志就结束了。但**"实例存在"不等于"连接可用"**：
     * 长时间息屏后无障碍连接可能失效而 `instance` 仍是旧引用，此时
     * `repairIfNeeded()` 因为 `isConnected()==true` 直接返回、什么都不做，
     * 于是失败会一直失败下去——用户只能靠下拉状态栏去"手动激活"。
     *
     * 现在连续失败 [MAX_INJECTION_FAILURES] 次就主动把无障碍条目关掉再打开，
     * 强制系统重新绑定，不等用户动手。
     */
    private fun onInjectionFailure(what: String) {
        injectionFailures++
        Log.w(
            "GazeCameraService",
            "injection failed ($what), consecutive=$injectionFailures " +
                "(a11yConnected=${GazeAccessibilityService.isConnected()} " +
                "backend=${SwipeInjector.activeBackend(this)})",
        )
        if (injectionFailures < MAX_INJECTION_FAILURES) return
        injectionFailures = 0
        Log.w("GazeCameraService", "too many injection failures — forcing an accessibility rebind")
        GazeRuntime.publish { it.copy(note = "手势注入反复失败，正在重连无障碍服务…") }
        runCatching { AccessibilityBootstrap.forceRebind(this, "injection failures") }
    }

    /**
     * 全链路自检（v5.5）。
     *
     * 用**一行**把每个可能卡住的环节都打出来，便于在被报告"又失效了"时立刻定位是哪一环：
     *
     * ```
     * SelfCheck powerInteractive=true screenActive=true running=true shouldAnalyze=true
     *   analyzing=true targetActive=true forceActive=false foreground=com.ss.android.ugc.aweme
     *   cameraProvider=true cameraBound=true framesAgoMs=66 stale=false graceMs=0
     *   rebinding=false probeFailures=0 lastFrame=66ms
     *   a11yEnabled=true a11yConnected=true a11yInstanceNull=false swipeReady=true
     *   occlRemainMs=0 hardLock=false detectorArmed=true
     * ```
     *
     * 判读方法：
     *  - `powerInteractive=false` 却 `screenActive=true` → 电源状态错了（自校正没跑或没生效）
     *  - `a11yConnected=false` → 无障碍服务断开，注入必定失败
     *  - `swipeReady=false` → 注入后端不可用（Shizuku 没装 + 无障碍没连上）
     *  - `stale=true` 且 `cameraProvider=true` → 相机句柄还在但没画面，需要重绑
     *  - `detectorArmed=false` → 摄像头在跑但检测器没被喂数据（流水线断在中段）
     */
    private fun dumpSelfCheck(trigger: String) {
        val power = getSystemService(PowerManager::class.java)
        val now = SystemClock.elapsedRealtime()
        val framesAgo = if (lastFrameAtMs == 0L) -1L else now - lastFrameAtMs
        val stale = framesAgo < 0 || framesAgo > FRAME_TIMEOUT_MS
        Log.i(
            "GazeSelfCheck",
            "$trigger powerInteractive=${power?.isInteractive} screenActive=$screenActive " +
                "running=${running.get()} shouldAnalyze=${shouldAnalyze()} " +
                "analyzing=${!analyzer!!.paused} targetActive=${AppStateManager.targetActive} " +
                "forceActive=${AppStateManager.forceActive} " +
                "foreground=${AppStateManager.foregroundPackage} " +
                "globalPaging=${AppStateManager.globalPaging} " +
                "cameraProvider=${cameraProvider != null} cameraBound=$cameraBound " +
                "framesAgoMs=$framesAgo stale=$stale " +
                "graceMs=${(analysisGraceUntilMs - now).coerceAtLeast(0L)} " +
                "restartAttempts=$restartAttempts probeFailures=$probeFailures " +
                "rebinding=${wakeRetryAttempt > 0} " +
                "hardResyncs=$hardResyncCount " +
                "noFrameForMs=${if (staleBeganAtMs == 0L) 0L else now - staleBeganAtMs} " +
                "a11yEnabled=${AccessibilityBootstrap.isServiceEnabled(this)} " +
                "a11yConnected=${GazeAccessibilityService.isConnected()} " +
                // v5.7：区分「系统没发事件」与「事件到了我们判错了」。这两个值在排查
                // 「重开抖音不触发」时是第一分叉：a11yEvents 停涨说明只能靠轮询兜底。
                "a11yEvents=${GazeAccessibilityService.windowEventCount} " +
                "a11yEventAgoMs=${GazeAccessibilityService.lastWindowEventAtMs.let { if (it == 0L) -1L else now - it }}" +
                "a11yForceRebinds=$a11yForceRebinds " +
                "swipeReady=${SwipeInjector.isReady(this)} " +
                "backend=${SwipeInjector.activeBackend(this)} " +
                "injFailures=$injectionFailures " +
                "occlRemainMs=${(occlusionUntilMs - now).coerceAtLeast(0L)} " +
                "hardLock=$staticHardLock detectorArmed=$headPoseActive",
        )
    }

    /**
     * 无障碍服务重连兜底（v5.5）。
     *
     * 长时间待机后系统可能把无障碍服务解绑（`instance` 变 null），而设置里的开关仍然是
     * "已启用"。此时**所有手势注入都会失败**——这正好是"下拉状态栏就好了"的现象：
     * 下拉动作会引发一批窗口/无障碍事件，把绑定重新激活。
     *
     * 这里提前把它做掉：只要开关是开的、实例却是空的，就主动请求系统重新绑定。
     */
    private fun ensureAccessibilityBound(trigger: String) {
        if (GazeAccessibilityService.isConnected()) {
            a11yMissingSinceMs = 0L
            return
        }
        if (!AccessibilityBootstrap.isServiceEnabled(this)) {
            // 开关本身是关的：重绑也无从谈起，用户需要重新授权。明确说出来，
            // 免得这一条被当成"已经处理过了"。
            Log.w(
                "GazeCameraService",
                "$trigger: accessibility service NOT enabled in settings — " +
                    "cannot rebind (the ADB authorisation is gone)",
            )
            return
        }

        // v5.7：单次 rebind 失败过就持续升级，别让"一直在重试"看起来像"已经好了"。
        // 判据用「持续缺失时长」而不是循环次数，因为探针的调用频率会变（探针 + 轮询）。
        val now = SystemClock.elapsedRealtime()
        if (a11yMissingSinceMs == 0L) a11yMissingSinceMs = now
        val missingFor = now - a11yMissingSinceMs

        Log.w(
            "GazeCameraService",
            "$trigger: accessibility service enabled in settings but NOT connected " +
                "(missing ${missingFor}ms) — requesting rebind",
        )
        if (missingFor >= A11Y_FORCE_REBIND_AFTER_MS) {
            // 开关是开的、系统却迟迟不给我们实例：典型的"连接失效/被省电回收"。
            // repairIfNeeded 在这里会因为"设置里已启用"而只做一次 toggle，
            // 试过一轮仍拿不回实例就直接强绑（关掉再打开），这正是用户手动
            // 下拉状态栏能达到的效果。
            a11yMissingSinceMs = now
            a11yForceRebinds++
            Log.w(
                "GazeCameraService",
                "a11y still missing after ${missingFor}ms — forcing a hard rebind " +
                    "(#${a11yForceRebinds}, no manual status-bar pull needed)",
            )
            runCatching { AccessibilityBootstrap.forceRebind(this, "$trigger: missing for ${missingFor}ms") }
                .onFailure { Log.w("GazeCameraService", "a11y force rebind failed: ${it.message}") }
            return
        }

        runCatching { AccessibilityBootstrap.repairIfNeeded(this) }
            .onFailure { Log.w("GazeCameraService", "a11y rebind failed: ${it.message}") }
    }

    private fun checkFrames() {
        if (!running.get() || !shouldAnalyze()) return
        val now = SystemClock.elapsedRealtime()

        // 刚恢复流水线 / 刚绑定相机：这是「正在打开」而不是「卡死」，别误判。
        if (now < analysisGraceUntilMs) return

        val reference = if (lastFrameAtMs != 0L) lastFrameAtMs else analysisStartedAtMs
        if (reference == 0L || now - reference <= FRAME_TIMEOUT_MS) return

        restartAttempts++
        Log.w(
            TAG,
            "no frame for ${now - reference}ms — rebuilding camera (attempt $restartAttempts)",
        )
        GazeRuntime.publish { it.copy(note = "相机无画面，正在自动重建（第 $restartAttempts 次）") }

        analyzer?.resetSmoothing()
        blinkDetector?.reset()
        headPoseDetector?.recalibrate()
        runCatching { cameraProvider?.unbindAll() }
        cameraBound = false
        if (cameraProvider == null) bindCamera() else rebind()
        analysisStartedAtMs = now
        // 下一次判定留出重建时间，避免刚重建完又立刻报「无画面」。
        analysisGraceUntilMs = now + REBIND_GRACE_MS
        lastFrameAtMs = 0L

        // 注意：这里**不再有重试上限**。以前连续失败 3 次就永久放弃自愈，
        // 用户看到的就是「功能没了，只能手动点重启」。反复重建最多费点电，
        // 比静默失效好得多；而且每次进入允许翻页的界面都会把预算清零。
        if (restartAttempts == MAX_RESTART_ATTEMPTS) {
            GazeRuntime.publish { it.copy(note = "相机多次无画面，仍在持续重试（可点通知里的「重启」）") }
        }
    }

    private fun startFrameWatchdog() {
        if (frameWatchdog != null) return
        val runnable = object : Runnable {
            override fun run() {
                runCatching { checkFrames() }
                mainHandler.postDelayed(this, FRAME_WATCHDOG_INTERVAL_MS)
            }
        }
        frameWatchdog = runnable
        mainHandler.postDelayed(runnable, FRAME_WATCHDOG_INTERVAL_MS)
    }

    private fun stopFrameWatchdog() {
        frameWatchdog?.let { mainHandler.removeCallbacks(it) }
        frameWatchdog = null
    }

    /** Full camera-pipeline rebuild, triggered by the notification or the settings button. */
    private fun restartPipeline() {
        Log.i("GazeCameraService", "manual restart requested")

        // Re-arm the gesture backend as well. A "force stop" clears the
        // accessibility setting entirely, and rebuilding only the camera would
        // leave paging dead with a healthy-looking notification.
        AccessibilityBootstrap.repairIfNeeded(this)

        releaseCamera("manual restart")
        cameraProvider = null
        analyzer?.resetSmoothing()
        blinkDetector?.reset()
        headPoseDetector?.recalibrate()
        // 重建后不该还带着上一轮的冷却，否则按「重启服务」后头 1.5 秒是哑的。
        globalGate.reset()
        mouthDetector?.reset()
        restartAttempts = 0
        analysisStartedAtMs = SystemClock.elapsedRealtime()
        lastFrameAtMs = 0L

        // Re-evaluate the foreground right now instead of on the next tick.
        AppStateManager.pollNow(this)

        GazeRuntime.publish { it.copy(note = "正在重建相机…") }
        if (shouldAnalyze()) bindCamera() else updateNotification()
    }

    // ---------------------------------------------------------- housekeeping --

    private fun shutdown() {
        if (!running.getAndSet(false)) {
            stopSelf()
            return
        }
        runCatching { cameraProvider?.unbindAll() }
        cameraBound = false
        AppStateManager.removeListener(appStateListener)
        AppStateManager.stopPolling()
        runCatching { analysisExecutor?.shutdown() }
        analysisExecutor = null
        stopFrameWatchdog()
        warmHandler.removeCallbacks(deepSleep)
        runCatching { swipeExecutor?.shutdown() }
        swipeExecutor = null
        runCatching { unregisterReceiver(screenReceiver) }
        unregisterTestSwipeReceiver()
        GazeRuntime.publish {
            GazeRuntime.Snapshot(serviceRunning = false, enabled = false, note = "服务已停止")
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    /**
     * Status notification. Only two states exist, and it is rewritten on
     * transitions rather than continuously, so it never flickers or spams.
     */
    private fun buildNotification(): Notification {
        val active = shouldAnalyze()
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val restartIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, GazeCameraService::class.java).setAction(ACTION_RESTART),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, GazeCameraService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_gaze)
            .setContentTitle(
                getString(
                    if (active) R.string.notif_title_active else R.string.notif_title_standby,
                )
            )
            .setContentText(
                getString(
                    if (active) R.string.notif_text_active else R.string.notif_text_standby,
                )
            )
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.notif_action_restart), restartIntent)
            .addAction(0, getString(R.string.notif_action_stop), stopIntent)
            .build()
    }

    /** Re-post the notification with the current state. */
    private fun updateNotification() {
        if (!running.get()) return
        runCatching {
            getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotification())
        }
    }

    private fun goForeground() {
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
        )
    }
}
