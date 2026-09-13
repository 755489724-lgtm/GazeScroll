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

        /** Give up after this many consecutive unproductive rebinds. */
        private const val MAX_RESTART_ATTEMPTS = 3

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
    }

    private val running = AtomicBoolean(false)

    private var analysisExecutor: ExecutorService? = null
    private var swipeExecutor: ExecutorService? = null
    private var analyzer: FaceGazeAnalyzer? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var stateMachine: GazeStateMachine? = null
    private var blinkDetector: BlinkDetector? = null
    private var headPoseDetector: HeadPoseDetector? = null

    /** Tracks the enable switch so the detector is only reset on a real transition. */
    private var headPoseActive = false

    /** Timestamp of the last GazeDiag line. */
    private var lastDiagnosticAtMs = 0L

    /** Timestamp of the newest analysed frame; drives the self-heal watchdog. */
    @Volatile
    private var lastFrameAtMs = 0L

    /** When the current analysis run began, used as the watchdog's first reference. */
    private var analysisStartedAtMs = 0L

    /** Consecutive unproductive rebinds, to stop a runaway retry loop. */
    private var restartAttempts = 0

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
     * Foreground-app changes. Fired on the main thread by [GazeAccessibilityService].
     */
    private val appStateListener: (Boolean) -> Unit = { active ->
        Log.i("GazeCameraService", "target foreground = $active")
        if (active) onTargetEntered() else onTargetLeft()
        updateCameraState()
    }

    // ------------------------------------------------------------- lifecycle --

    override fun onCreate() {
        super.onCreate()
        GazeRuntime.config = AppPrefs.loadConfig(this)

        analyzer = FaceGazeAnalyzer(::onFrame)

        blinkDetector = BlinkDetector { reason -> fireSwipe(reason, SwipeDirection.UP) }

        // Nod down -> previous video, tilt up -> next video.
        headPoseDetector = HeadPoseDetector { gesture, reason ->
            fireSwipe(reason, gesture.swipe)
        }

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
                Intent.ACTION_SCREEN_ON -> setScreenActive(true)
            }
        }
    }

    /**
     * Debug-only self-test hook, registered in debug builds only. Lets the
     * gesture path be exercised from adb without a real blink.
     */
    private val testSwipeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val down = intent?.getBooleanExtra(EXTRA_TEST_DOWN, false) ?: false
            val direction = if (down) SwipeDirection.DOWN else SwipeDirection.UP
            Log.i("GazeCameraService", "self-test swipe broadcast received ($direction)")
            fireSwipe("ADB 自测", direction)
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
        if (screenActive == active) return
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
            if (!cameraBound) rebind()
            GazeRuntime.publish {
                it.copy(analyzing = true, note = "目标应用在前台，检测已恢复")
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
        val cfg = GazeRuntime.config

        if (running.get()) {
            maybeLogDiagnostics(frame, cfg, now)
            if (cfg.blinkTriggerEnabled) {
                blinkDetector?.let { detector ->
                    detector.requiredBlinks = cfg.blinkTriggerCount
                    detector.cooldownMs = cfg.blinkCooldownMs
                    detector.closedBelow = cfg.blinkClosedBelow
                    detector.openAbove = cfg.blinkOpenAbove
                    detector.requiredClosedFrames = cfg.blinkClosedFrames
                    detector.onEyeProbabilities(
                        frame.leftEyeOpenProbability,
                        frame.rightEyeOpenProbability,
                        now,
                    )
                }
            }

            val head = headPoseDetector
            if (head != null) {
                if (cfg.headPoseEnabled) {
                    headPoseActive = true
                    head.thresholdDeg = cfg.headPoseAngleThreshold
                    head.motionWindowMs = cfg.headPoseMotionWindowMs
                    head.holdMs = cfg.headPoseHoldMs
                    head.cooldownMs = cfg.headPoseCooldownMs
                    head.invertPitch = cfg.headPoseInvertPitch
                    head.onHeadAngle(frame.headEulerAngleX, now)
                } else if (headPoseActive) {
                    // Switched off: drop the baseline so it re-learns on re-enable.
                    headPoseActive = false
                    head.reset()
                }
            }

            val machine = stateMachine
            if (cfg.gazeModeEnabled && machine != null) {
                machine.config = cfg
                machine.update(frame.gaze, now)
            } else if (machine != null && machine.state != GazeState.IDLE) {
                machine.reset()
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
            )
        }
    }

    /**
     * Blink or head pose fired: inject one swipe in [direction].
     *
     * [SwipeInjector.swipe] blocks for ~150 ms while the shell command runs, so
     * it must not be called from the main thread.
     */
    private fun fireSwipe(reason: String, direction: SwipeDirection) {
        if (!SwipeInjector.isReady(this)) {
            GazeRuntime.publish { it.copy(note = "没有可用的翻页方式（Shizuku / 无障碍都没就绪）") }
            return
        }
        val label = if (direction == SwipeDirection.UP) "上" else "下"
        ensureSwipeExecutor().execute {
            val ok = SwipeInjector.swipe(this, direction, GazeRuntime.config.swipeDurationMs)
            if (!ok) {
                // Backend may have been unbound (app force-stopped / killed).
                SwipeInjector.repair(this)
            }
            GazeRuntime.publish {
                val count = it.triggers + 1
                it.copy(
                    triggers = count,
                    note = if (ok) {
                        "已${label}滑 #$count · $reason"
                    } else {
                        "手势注入失败，正在尝试自动修复（${SwipeInjector.activeBackend(this)}）"
                    },
                )
            }
        }
    }

    private fun ensureSwipeExecutor(): ExecutorService {
        val current = swipeExecutor
        return if (current != null && !current.isShutdown) {
            current
        } else {
            Executors.newSingleThreadExecutor().also { swipeExecutor = it }
        }
    }

    /**
     * Throttled live view of the whole detection pipeline, so the behaviour can be
     * checked from a PC without the app being in the foreground:
     *
     *   adb logcat -s GazeDiag:V HeadPose:V GazeA11y:V
     *
     * `pitch` is ML Kit's raw `headEulerAngleX` — nod and watch which way it
     * moves to confirm the head-pose direction on this device.
     */
    private fun maybeLogDiagnostics(frame: AnalyzedFrame, cfg: GazeConfig, now: Long) {
        if (now - lastDiagnosticAtMs < DIAGNOSTIC_INTERVAL_MS) return
        lastDiagnosticAtMs = now
        Log.i(
            "GazeDiag",
            "face=${frame.faceDetected}" +
                " eyeL=${frame.leftEyeOpenProbability?.let { "%.2f".format(it) } ?: "-"}" +
                " eyeR=${frame.rightEyeOpenProbability?.let { "%.2f".format(it) } ?: "-"}" +
                " pitch=${frame.headEulerAngleX?.let { "%.1f".format(it) } ?: "-"}" +
                " base=${headPoseDetector?.baselineDeg?.let { "%.1f".format(it) } ?: "-"}" +
                " headPose=${cfg.headPoseEnabled}" +
                " needBlinks=${cfg.blinkTriggerCount}" +
                " pending=${blinkDetector?.pendingBlinks ?: 0}" +
                " standby=${frame.standby}",
        )
    }

    // ------------------------------------------------------- self-heal / entry --

    /**
     * Entering a target app.
     *
     * Everything that could make the first seconds feel dead is reset here: the
     * blink and gaze state machines drop their history, the head baseline is
     * seeded from the previous session so a nod works immediately, and the camera
     * is bound right away instead of waiting for the next poll.
     */
    private fun onTargetEntered() {
        blinkDetector?.reset()
        headPoseDetector?.let { head ->
            val previous = AppPrefs.headBaseline(this)
            if (previous != null) head.seedBaseline(previous) else head.recalibrate()
        }
        stateMachine?.reset()
        restartAttempts = 0
        analysisStartedAtMs = SystemClock.elapsedRealtime()
        lastFrameAtMs = 0L
        if (!cameraBound) rebind()
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
    private fun checkFrames() {
        if (!running.get() || !shouldAnalyze()) return
        val now = SystemClock.elapsedRealtime()
        val reference = if (lastFrameAtMs != 0L) lastFrameAtMs else analysisStartedAtMs
        if (reference == 0L || now - reference <= FRAME_TIMEOUT_MS) return

        if (restartAttempts >= MAX_RESTART_ATTEMPTS) {
            GazeRuntime.publish { it.copy(note = "相机一直无画面，已停止重试（可点通知里的「重启」）") }
            return
        }
        restartAttempts++
        Log.w(
            "GazeCameraService",
            "no frame for ${now - reference}ms — rebuilding camera (attempt $restartAttempts)",
        )
        GazeRuntime.publish { it.copy(note = "相机无画面，正在自动重建（第 $restartAttempts 次）") }

        analyzer?.resetSmoothing()
        blinkDetector?.reset()
        headPoseDetector?.recalibrate()
        runCatching { cameraProvider?.unbindAll() }
        cameraBound = false
        rebind()
        analysisStartedAtMs = now
        lastFrameAtMs = 0L
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
