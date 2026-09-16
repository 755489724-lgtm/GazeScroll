package com.example.gazescroll

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.CheckBox
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.gazescroll.databinding.ActivityMainBinding
import rikka.shizuku.Shizuku

/**
 * Settings screen, and nothing else.
 *
 * Behaviour once setup is complete: launching from the launcher icon requests no
 * permissions and shows no onboarding — it starts the foreground service and
 * immediately goes to the background, so the app never gets in the way.
 *
 * To actually reach this screen with everything already granted, tap the
 * persistent notification (it passes [EXTRA_OPEN_SETTINGS]), or tap the
 * launcher icon a second time.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        /** Make the activity stay visible instead of auto-backgrounding. */
        const val EXTRA_OPEN_SETTINGS = "com.example.gazescroll.OPEN_SETTINGS"
    }

    private lateinit var binding: ActivityMainBinding

    /** True when this launch is explicitly about changing settings. */
    private var showSettings = false

    /** Guards against backgrounding more than once per activity instance. */
    private var autoBackgrounded = false

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            render()
            if (granted) {
                requestNotificationPermissionIfNeeded()
                maybeStartService()
            }
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { render() }

    // Shizuku delivers all of these on the main thread, so render() may touch views.
    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, _ ->
            if (requestCode == ShizukuSwipeDispatcher.REQUEST_CODE) render()
        }

    private val shizukuBinderListener = Shizuku.OnBinderReceivedListener { render() }

    private val shizukuDeadListener = Shizuku.OnBinderDeadListener { render() }

    /** Drives the live eye / pitch readout on the settings screen. */
    private val liveListener: (GazeRuntime.Snapshot) -> Unit = { renderLive(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        GazeRuntime.config = AppPrefs.loadConfig(this)
        showSettings = shouldShowSettings(intent)

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        // Sticky: fires immediately when the binder is already available.
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderListener)
        Shizuku.addBinderDeadListener(shizukuDeadListener)

        setupSettingsUi()
        setupCooldownUi()
        setupHorizontalSwipeUi()
        setupMouthTapUi()
        setupWinkVolumeUi()
        setupAdaptiveSwipeUi()
        setupGlobalPagingUi()
        setupSensitivityUi()
        setupTargetApps()
        binding.tvHint.setOnClickListener { onHintClicked() }
        binding.btnRestartService.setOnClickListener {
            runCatching { GazeCameraService.restart(this) }
            toast(getString(R.string.restart_requested))
            binding.root.postDelayed({
                if (!isFinishing && !isDestroyed) {
                    render()
                    renderLive(GazeRuntime.snapshot)
                }
            }, 2500L)
        }

        render()
        requestCameraPermissionIfNeeded()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (shouldShowSettings(intent)) {
            showSettings = true
            autoBackgrounded = true
            render()
        }
    }

    override fun onResume() {
        super.onResume()
        // Fully automatic fallback: if Shizuku is not usable but the app holds
        // WRITE_SECURE_SETTINGS, turn the accessibility service on ourselves.
        SwipeInjector.bootstrap(this)
        render()

        val ready = permissionsReady()
        android.util.Log.i(
            "MainActivity",
            "onResume ready=$ready showSettings=$showSettings autoBackgrounded=$autoBackgrounded",
        )

        if (ready) {
            // Remember it, so we never prompt or show onboarding again.
            AppPrefs.setSetupComplete(this, true)
            maybeStartService()
            maybeAutoBackground()
        } else {
            autoBackgrounded = false
            requestMissingPermissions()
        }
    }

    override fun onStart() {
        super.onStart()
        // Keep the camera running while this screen is visible so the live eye /
        // pitch readout has data to show, even though this app is not a target.
        AppStateManager.forceActive = true
        GazeCameraService.instance?.refreshAnalysisState()
        GazeRuntime.addListener(liveListener)
    }

    override fun onStop() {
        GazeRuntime.removeListener(liveListener)
        AppStateManager.forceActive = false
        GazeCameraService.instance?.refreshAnalysisState()
        super.onStop()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        Shizuku.removeBinderReceivedListener(shizukuBinderListener)
        Shizuku.removeBinderDeadListener(shizukuDeadListener)
        super.onDestroy()
    }

    // ------------------------------------------------------------- sensitivity --

    /**
     * Sensitivity presets rather than raw numbers, because the right value
     * depends on the user's glasses and lighting. The live readout below shows
     * the actual probabilities so the choice can be made from data.
     */
    private fun setupSensitivityUi() {
        val cfg = GazeRuntime.config

        binding.rgBlinkSensitivity.check(
            when {
                cfg.blinkClosedBelow >= 0.575f -> R.id.rbBlinkHigh
                cfg.blinkClosedBelow <= 0.500f -> R.id.rbBlinkLow
                else -> R.id.rbBlinkMedium
            }
        )
        binding.rgBlinkSensitivity.setOnCheckedChangeListener { _, checkedId ->
            val (closed, open) = when (checkedId) {
                R.id.rbBlinkHigh -> 0.60f to 0.75f
                R.id.rbBlinkLow -> 0.45f to 0.65f
                else -> 0.55f to 0.70f
            }
            updateConfig { it.copy(blinkClosedBelow = closed, blinkOpenAbove = open) }
            renderLive(GazeRuntime.snapshot)
        }

        binding.rgHeadSensitivity.check(
            when {
                cfg.headPoseAngleThreshold <= 7f -> R.id.rbHeadHigh
                cfg.headPoseAngleThreshold >= 10f -> R.id.rbHeadLow
                else -> R.id.rbHeadMedium
            }
        )
        binding.rgHeadSensitivity.setOnCheckedChangeListener { _, checkedId ->
            val deg = when (checkedId) {
                R.id.rbHeadHigh -> 6f
                R.id.rbHeadLow -> 12f
                else -> 8f
            }
            updateConfig { it.copy(headPoseAngleThreshold = deg) }
            renderLive(GazeRuntime.snapshot)
        }
    }

    /**
     * Live eye-open probabilities and pitch. This is the "measure, don't guess"
     * tool: open/look at the camera and read the numbers, then pick the
     * sensitivity whose threshold sits between the open and closed values.
     */
    private fun renderLive(s: GazeRuntime.Snapshot) {
        val cfg = GazeRuntime.config
        val text = buildString {
            val left = s.leftEyeOpen
            val right = s.rightEyeOpen
            if (left == null && right == null) {
                append(getString(R.string.settings_live_empty))
                append('\n')
            } else {
                append("左眼睁开度 ").append(fmt(left)).append("    右眼睁开度 ").append(fmt(right)).append('\n')
                val closedNow = (left != null && left < cfg.blinkClosedBelow) ||
                    (right != null && right < cfg.blinkClosedBelow)
                append("逐帧判定：").append(if (closedNow) "闭眼" else "睁眼")
                append("   （闭眼阈值 ").append(fmt(cfg.blinkClosedBelow))
                append(" / 睁眼阈值 ").append(fmt(cfg.blinkOpenAbove)).append("）\n")
            }

            val pitch = s.headAngleDeg
            if (pitch != null) {
                append("俯仰角 ").append(fmt(pitch)).append("°")
                s.headBaselineDeg?.let { append("    基准线 ").append(fmt(it)).append("°") }
                append("    阈值 ±").append(cfg.headPoseAngleThreshold.toInt()).append("°\n")
            }

            val yaw = s.headYawDeg
            if (yaw != null && cfg.horizontalSwipeEnabled) {
                append("偏航角 ").append(fmt(yaw)).append("°")
                append("    扭头阈值 ±").append(cfg.horizontalSwipeAngleThreshold.toInt()).append("°")
                if (yaw < 0) append("（正在向左）") else if (yaw > 0) append("（正在向右）")
                append("    已扭头 ").append(s.turnCount).append(" 次\n")
            }

            // 张嘴读数：闭嘴基准 + 当前比例，两个数放一起才知道灵敏度该选哪档。
            if (cfg.mouthTapEnabled) {
                val now = s.mouthRatio
                val base = s.mouthBaseline
                if (now == null) {
                    append("张嘴：").append(getString(R.string.settings_live_empty)).append('\n')
                } else {
                    append("张嘴比例 ").append(fmt3(now))
                    base?.let { append("    本人闭嘴基准 ").append(fmt3(it)) }
                    append('\n')
                    // 张嘴量 = 当前 − 闭嘴基准，与检测器内部的判定量完全一致。
                    base?.let {
                        val open = now - it
                        val pct = "%.1f".format(java.util.Locale.US, open * 100)
                        append("张嘴量 ").append(pct).append("% 脸高")
                        append("    判定阈值 ")
                        append("%.0f".format(java.util.Locale.US, cfg.mouthSensitivity.fraction * 100))
                        append("%")
                        append("    当前判定：").append(if (s.mouthOpen) "张嘴" else "闭嘴")
                        append("    识别 ").append(s.mouthOpenCount).append(" 次")
                        append("    已点击 ").append(s.mouthTapCount).append(" 次\n")
                    }
                }
            }

            // v5.30：单眼闭眼控音量 —— 把「已经闭了多久」直接显示出来。用户能看着数字
            // 涨到 1000ms，也能立刻分辨到底是哪只眼被读成闭着（单闭不灵时第一个要看的）。
            if (cfg.winkVolumeEnabled) {
                append("单闭保持：左眼 ").append(s.winkHeldLeftMs).append("ms")
                append("    右眼 ").append(s.winkHeldRightMs).append("ms")
                append("    满 1000ms 调一档（左眼闭 = ")
                append(if (cfg.winkLeftVolumeUp) "调高" else "调低")
                append("，右眼闭 = ")
                append(if (cfg.winkRightVolumeUp) "调高" else "调低")
                append("）    已调音量 ").append(s.winkSteps).append(" 档\n")
            }

            append("眨眼累计 ").append(s.blinkCount)
            append("    已触发 #").append(s.triggers)
            append("    人脸=").append(if (s.faceDetected) "有" else "无")
            append("    分析").append(if (s.analyzing) "中" else "已暂停")
            if (s.standby) append("（省电 1fps）")
        }
        binding.tvLiveValues.text = text
        renderCooldownLive(s)
        renderAdaptiveSwipeUi(s)
        binding.tvGlobalPagingState.text =
            globalPagingStateText(GazeRuntime.config.globalPagingEnabled, this)
    }

    private fun fmt(v: Float?): String = v?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "--"

    /** 张嘴比例只在小数点后第三位才有区别，所以单独用一个三位格式。 */
    private fun fmt3(v: Float): String = String.format(java.util.Locale.US, "%.3f", v)

    // ----------------------------------------------------------------- settings --

    private fun setupSettingsUi() {
        val cfg = GazeRuntime.config

        binding.rgBlinkCount.check(
            when (cfg.blinkTriggerCount) {
                1 -> R.id.rbBlink1
                3 -> R.id.rbBlink3
                4 -> R.id.rbBlink4
                else -> R.id.rbBlink2
            }
        )
        binding.rgBlinkCount.setOnCheckedChangeListener { _, checkedId ->
            val count = when (checkedId) {
                R.id.rbBlink1 -> 1
                R.id.rbBlink3 -> 3
                // v5.19：新增 4 次档。实测用户"不由自主"的连续眨眼正好是 3 连眨
                // （间隔 350ms / 583ms），而门槛是 3 次 —— 静止时的上滑误触就是它。
                R.id.rbBlink4 -> 4
                else -> 2
            }
            updateConfig { it.copy(blinkTriggerCount = count) }
        }

        binding.switchHeadPose.isChecked = cfg.headPoseEnabled
        binding.switchHeadInvert.isChecked = cfg.headPoseInvertPitch
        binding.switchHeadInvert.isEnabled = cfg.headPoseEnabled
        binding.switchHeadPose.setOnCheckedChangeListener { _, checked ->
            updateConfig { it.copy(headPoseEnabled = checked) }
            binding.switchHeadInvert.isEnabled = checked
        }
        binding.switchHeadInvert.setOnCheckedChangeListener { _, checked ->
            updateConfig { it.copy(headPoseInvertPitch = checked) }
        }

        // v4.8：静止锁定（压掉「一动不动也误触」）。做成开关是因为它确实会略微抬高
        // 阈值，极少数手感敏感的机器上用户可以自己关掉。
        binding.switchStaticLock.isChecked = cfg.staticLockEnabled
        binding.switchStaticLock.setOnCheckedChangeListener { _, checked ->
            updateConfig { it.copy(staticLockEnabled = checked) }
        }
    }

    // ------------------------------------------------------- 防误触冷却设置 --

    /**
     * 「启用冷却时间」开关 + 时长滑块。
     *
     * 滑块按 [GazeConfig.GLOBAL_COOLDOWN_STEP_MS]（250 ms）等距取档：`progress * 步长`
     * 就是毫秒数，避免用浮点比例换算带来的取整误差。改动立刻写进配置和
     * SharedPreferences，服务端每帧从 [GazeRuntime.config] 同步，无需重启。
     */
    private fun setupCooldownUi() {
        val cfg = GazeRuntime.config
        binding.switchGlobalCooldown.isChecked = cfg.globalCooldownEnabled

        binding.switchGlobalCooldown.setOnCheckedChangeListener { _, checked ->
            updateConfig { it.copy(globalCooldownEnabled = checked) }
            renderCooldownUi()
        }

        binding.seekCooldown.setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    // 数值文字始终跟随，用户拖到哪里就看到哪里。
                    if (fromUser) {
                        updateConfig { it.copy(globalCooldownMs = progressToCooldownMs(progress)) }
                    }
                    renderCooldownUi()
                }

                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                    // 松手时再落一次盘，确保拖动过程中被合并掉的写入最终一致。
                    updateConfig { it.copy(globalCooldownMs = progressToCooldownMs(seekBar?.progress ?: 0)) }
                    renderCooldownUi()
                }
            },
        )

        renderCooldownUi()
    }

    /** 把滑块档位换算成毫秒（并夹进合法区间）。 */
    private fun progressToCooldownMs(progress: Int): Long = GazeConfig.cooldownMsForStep(progress)

    /** 把配置里的毫秒数换算回滑块档位。 */
    private fun cooldownMsToProgress(ms: Long): Int = GazeConfig.cooldownStepForMs(ms)

    /** 冷却是开关、滑块、数值文字三者的一致状态；改任意一个都从这里统一刷。 */
    private fun renderCooldownUi() {
        val cfg = GazeRuntime.config
        val enabled = cfg.globalCooldownEnabled
        val ms = GazeConfig.snapGlobalCooldown(cfg.globalCooldownMs)

        // 用户正在拖动时不要抢他的手指：只在位置确实不同的时候回写。
        val progress = cooldownMsToProgress(ms)
        if (binding.seekCooldown.progress != progress) binding.seekCooldown.progress = progress

        binding.tvCooldownValue.text = "冷却时间：${GazeConfig.formatCooldown(ms)}"
        binding.seekCooldown.isEnabled = enabled
        binding.tvCooldownRange.isEnabled = enabled
        binding.tvCooldownValue.setAlpha(if (enabled) 1f else 0.45f)
        binding.tvCooldownRange.setAlpha(if (enabled) 1f else 0.45f)
        renderCooldownLive(GazeRuntime.snapshot)
    }

    /** 冷却实时状态：正在倒计时就显示还剩多少秒。 */
    private fun renderCooldownLive(s: GazeRuntime.Snapshot) {
        val cfg = GazeRuntime.config
        binding.tvCooldownLive.text = when {
            !cfg.globalCooldownEnabled -> getString(R.string.settings_cooldown_live_off)
            s.cooldownRemainMs > 0L -> getString(
                R.string.settings_cooldown_live_active,
                GazeConfig.formatCooldown(s.cooldownRemainMs),
            )

            else -> getString(R.string.settings_cooldown_live_idle)
        }
    }

    // --------------------------------------------------- v4.4 左右扭头滑动 --

    /**
     * 「向左 / 向右扭头」开关 + 灵敏度 + 左右反转。
     *
     * 灵敏度沿用点头那套档位做法（高/中/低 = 14°/20°/28°），因为用户能理解的
     * 是「轻轻偏头还是要明显扭头」，而不是一个裸的角度值。
     */
    private fun setupHorizontalSwipeUi() {
        val cfg = GazeRuntime.config

        binding.switchHorizontalSwipe.isChecked = cfg.horizontalSwipeEnabled
        binding.switchHorizInvert.isChecked = cfg.horizontalSwipeInvertYaw

        binding.rgHorizSensitivity.check(
            when {
                cfg.horizontalSwipeAngleThreshold <= 17f -> R.id.rbHorizHigh
                cfg.horizontalSwipeAngleThreshold >= 24f -> R.id.rbHorizLow
                else -> R.id.rbHorizMedium
            }
        )

        binding.switchHorizontalSwipe.setOnCheckedChangeListener { _, checked ->
            updateConfig { it.copy(horizontalSwipeEnabled = checked) }
            renderHorizontalSwipeUi()
        }
        binding.switchHorizInvert.setOnCheckedChangeListener { _, checked ->
            updateConfig { it.copy(horizontalSwipeInvertYaw = checked) }
        }
        binding.rgHorizSensitivity.setOnCheckedChangeListener { _, checkedId ->
            val deg = when (checkedId) {
                R.id.rbHorizHigh -> 14f
                R.id.rbHorizLow -> 28f
                else -> 20f
            }
            updateConfig { it.copy(horizontalSwipeAngleThreshold = deg) }
        }

        renderHorizontalSwipeUi()
    }

    /** 关闭时把灵敏度相关控件置灰，避免看起来能调却不起作用。 */
    private fun renderHorizontalSwipeUi() {
        val enabled = GazeRuntime.config.horizontalSwipeEnabled
        binding.tvHorizThreshold.isEnabled = enabled
        binding.switchHorizInvert.isEnabled = enabled
        for (id in intArrayOf(R.id.rbHorizHigh, R.id.rbHorizMedium, R.id.rbHorizLow)) {
            binding.root.findViewById<android.view.View>(id)?.isEnabled = enabled
        }
        listOf<android.view.View>(binding.tvHorizThreshold, binding.switchHorizInvert).forEach {
            it.setAlpha(if (enabled) 1f else 0.45f)
        }
        for (id in intArrayOf(R.id.rbHorizHigh, R.id.rbHorizMedium, R.id.rbHorizLow)) {
            binding.root.findViewById<android.view.View>(id)?.setAlpha(if (enabled) 1f else 0.45f)
        }
    }

    // --------------------------------------------- v4.6 张嘴点击屏幕中央 --

    /**
     * 「张嘴点击屏幕中央」开关 + 灵敏度三档。
     *
     * 灵敏度按用户能理解的方式给档位（轻微张嘴 / 一般 / 明显张大），而不是丢一个比例
     * 数字让人猜。下方的实时数值会同时显示**原始比例**和**本人闭嘴基准**，所以灵敏度
     * 是照着真实读数选的，跟挑眨眼灵敏度是同一套做法。
     */
    private fun setupMouthTapUi() {
        val cfg = GazeRuntime.config
        binding.switchMouthTap.isChecked = cfg.mouthTapEnabled

        binding.rgMouthSensitivity.check(
            when (cfg.mouthSensitivity) {
                MouthSensitivity.HIGH -> R.id.rbMouthHigh
                MouthSensitivity.LOW -> R.id.rbMouthLow
                else -> R.id.rbMouthMedium
            },
        )

        binding.switchMouthTap.setOnCheckedChangeListener { _, checked ->
            updateConfig { it.copy(mouthTapEnabled = checked) }
            renderMouthTapUi()
        }
        binding.rgMouthSensitivity.setOnCheckedChangeListener { _, checkedId ->
            val level = when (checkedId) {
                R.id.rbMouthHigh -> MouthSensitivity.HIGH
                R.id.rbMouthLow -> MouthSensitivity.LOW
                else -> MouthSensitivity.MEDIUM
            }
            updateConfig { it.copy(mouthSensitivity = level) }
        }

        renderMouthTapUi()
    }

    private fun renderMouthTapUi() {
        val enabled = GazeRuntime.config.mouthTapEnabled
        binding.tvMouthThreshold.isEnabled = enabled
        binding.tvMouthThreshold.setAlpha(if (enabled) 1f else 0.45f)
        for (id in intArrayOf(R.id.rbMouthHigh, R.id.rbMouthMedium, R.id.rbMouthLow)) {
            binding.root.findViewById<android.view.View>(id)?.let {
                it.isEnabled = enabled
                it.setAlpha(if (enabled) 1f else 0.45f)
            }
        }
    }

    // ------------------------------------------- v5.30 单眼闭眼控音量 --

    /**
     * 「单眼闭眼 1 秒 → 音量加 / 减」的总开关 + 左右眼方向开关。
     *
     * 方向完全交给用户（这是 v5.30 用户点名的「反方向的开关」）：左右眼各一个开关，
     * 决定那只眼闭上是「调高」还是「调低」，两个开关互不影响。
     *
     * 判定阈值跟随眨眼灵敏度（同一对闭眼 / 睁眼阈值），所以这里不放灵敏度档位；
     * 下面的「实时数值」会显示两只眼**各自已经保持的单闭时长**，闭到 1000ms 就调一档。
     */
    private fun setupWinkVolumeUi() {
        val cfg = GazeRuntime.config
        binding.switchWinkVolume.isChecked = cfg.winkVolumeEnabled
        binding.switchWinkLeftUp.isChecked = cfg.winkLeftVolumeUp
        binding.switchWinkRightUp.isChecked = cfg.winkRightVolumeUp

        binding.switchWinkVolume.setOnCheckedChangeListener { _, checked ->
            updateConfig { it.copy(winkVolumeEnabled = checked) }
            renderWinkVolumeUi()
        }
        binding.switchWinkLeftUp.setOnCheckedChangeListener { _, checked ->
            updateConfig { it.copy(winkLeftVolumeUp = checked) }
            renderWinkVolumeUi()
        }
        binding.switchWinkRightUp.setOnCheckedChangeListener { _, checked ->
            updateConfig { it.copy(winkRightVolumeUp = checked) }
            renderWinkVolumeUi()
        }

        renderWinkVolumeUi()
    }

    /** 总开关关掉时两个方向开关置灰；值仍然保留，重新打开即恢复用户原来的选择。 */
    private fun renderWinkVolumeUi() {
        val enabled = GazeRuntime.config.winkVolumeEnabled
        for (v in listOf<android.view.View>(binding.switchWinkLeftUp, binding.switchWinkRightUp)) {
            v.isEnabled = enabled
            v.setAlpha(if (enabled) 1f else 0.45f)
        }
        // 方向一变，"左眼闭=调高/调低"那两行文字也跟着变，所以立刻重画实时区。
        renderLive(GazeRuntime.snapshot)
    }

    // --------------------------------------------------- v4.5 自适应滑动幅度 --

    /**
     * 「自适应滑动」开关 + 「自定义滑动柔度」滑块 + 恢复默认。
     *
     * 打开自适应后上下滑动的幅度与时长按当前前台应用自动切换：抖音整屏切换、
     * 微博/小红书短距离柔性滚动。滑块的改动立刻落到 [GazeRuntime.config] 并写盘，
     * 注入时每帧重新解析，所以拖完松手就已经生效、不需要重启。
     */
    private fun setupAdaptiveSwipeUi() {
        val cfg = GazeRuntime.config
        binding.switchAdaptiveSwipe.isChecked = cfg.adaptiveSwipeEnabled

        binding.switchAdaptiveSwipe.setOnCheckedChangeListener { _, checked ->
            updateConfig { it.copy(adaptiveSwipeEnabled = checked) }
            renderAdaptiveSwipeUi(GazeRuntime.snapshot)
        }

        binding.seekListDistance.setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        updateConfig {
                            it.copy(listSwipeDistance = AdaptiveSwipe.distanceForStep(progress))
                        }
                    }
                    renderAdaptiveSwipeUi(GazeRuntime.snapshot)
                }

                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit

                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                    updateConfig {
                        it.copy(
                            listSwipeDistance = AdaptiveSwipe.distanceForStep(seekBar?.progress ?: 0),
                        )
                    }
                    renderAdaptiveSwipeUi(GazeRuntime.snapshot)
                }
            },
        )

        binding.btnResetListDistance.setOnClickListener {
            updateConfig { it.copy(listSwipeDistance = AdaptiveSwipe.DEFAULT_LIST_DISTANCE) }
            renderAdaptiveSwipeUi(GazeRuntime.snapshot)
            toast(getString(R.string.settings_softness_reset_done))
        }

        renderAdaptiveSwipeUi(GazeRuntime.snapshot)
    }

    /** 把「当前前台应用 → 实际使用的滑动参数」显示出来，方便判断有没有生效。 */
    private fun renderAdaptiveSwipeUi(s: GazeRuntime.Snapshot) {
        val cfg = GazeRuntime.config
        val distance = AdaptiveSwipe.snapListDistance(cfg.listSwipeDistance)

        val step = AdaptiveSwipe.stepForDistance(distance)
        if (binding.seekListDistance.progress != step) binding.seekListDistance.progress = step

        binding.tvListDistanceValue.text =
            getString(R.string.settings_softness_value, AdaptiveSwipe.describeListDistance(distance))

        val profile = if (s.swipeProfile.isNotEmpty()) {
            s.swipeProfile
        } else {
            AdaptiveSwipe.describe(
                cfg.adaptiveSwipeEnabled,
                AppStateManager.foregroundPackage,
                distance,
            )
        }
        binding.tvSwipeProfile.text = getString(R.string.settings_adaptive_current, profile)
    }

    // ------------------------------------------------- v4.7 全局使用翻页 --

    /**
     * 「全局使用翻页」开关。
     *
     * 打开后白名单整个跳过：桌面、系统设置、任何 App 都会响应翻页手势（代价是摄像头
     * 一直工作）。改完要立刻通知前台检测重新判断——否则要等下一次窗口事件才生效。
     */
    private fun setupGlobalPagingUi() {
        binding.switchGlobalPaging.isChecked = GazeRuntime.config.globalPagingEnabled

        binding.switchGlobalPaging.setOnCheckedChangeListener { _, checked ->
            updateConfig { it.copy(globalPagingEnabled = checked) }
            // 让前台检测立刻按新开关重算「当前能不能翻页」。
            AppStateManager.refresh(this)
            binding.tvGlobalPagingState.text = globalPagingStateText(checked, this)
        }

        binding.tvGlobalPagingState.text =
            globalPagingStateText(GazeRuntime.config.globalPagingEnabled, this)
    }

    private fun globalPagingStateText(enabled: Boolean, ctx: android.content.Context): String {
        if (enabled) {
            return ctx.getString(R.string.settings_global_paging_state_on, currentAppLabel())
        }
        val count = AppPrefs.targetPackages(ctx).size
        return ctx.getString(R.string.settings_global_paging_state_off, count)
    }

    /** 当前前台应用的可读名字，用于「全局模式」状态行。 */
    private fun currentAppLabel(): String {
        val pkg = AppStateManager.foregroundPackage ?: return "未知"
        if (pkg == packageName) return "本设置页"
        return TargetApps.ALL.firstOrNull { it.packageName == pkg }?.label ?: pkg
    }


    private fun updateConfig(transform: (GazeConfig) -> GazeConfig) {
        val next = transform(GazeRuntime.config).sanitized()
        GazeRuntime.config = next
        AppPrefs.saveConfig(this, next)
    }

    // ------------------------------------------------------------ target apps --

    /**
     * Builds the "only page inside these apps" list. Entries are created in code
     * rather than XML so that apps which are not installed can be greyed out.
     */
    private fun setupTargetApps() {
        val selected = AppPrefs.targetPackages(this)
        binding.llTargetApps.removeAllViews()

        for (entry in TargetApps.ALL) {
            val installed = isInstalled(entry.packageName)
            val checkBox = CheckBox(this).apply {
                tag = entry.packageName
                isEnabled = installed
                isChecked = installed && entry.packageName in selected
                text = if (installed) {
                    entry.label
                } else {
                    getString(R.string.target_not_installed, entry.label)
                }
                setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (installed) R.color.on_surface else R.color.on_surface_dim,
                    )
                )
                setOnCheckedChangeListener { _, _ -> saveTargetApps() }
            }
            binding.llTargetApps.addView(checkBox)
        }
        updateTargetAppsWarning()
    }

    private fun saveTargetApps() {
        val selected = HashSet<String>()
        for (index in 0 until binding.llTargetApps.childCount) {
            val box = binding.llTargetApps.getChildAt(index) as? CheckBox ?: continue
            if (!box.isChecked) continue
            (box.tag as? String)?.let { selected.add(it) }
        }
        AppPrefs.setTargetPackages(this, selected)
        // Re-evaluate immediately: the user may already be sitting in a target app.
        AppStateManager.refresh(this)
        updateTargetAppsWarning()
        render()
    }

    private fun updateTargetAppsWarning() {
        binding.tvTargetsNone.visibility =
            if (AppPrefs.targetPackages(this).isEmpty()) android.view.View.VISIBLE
            else android.view.View.GONE
    }

    /** Human-readable form of "which app is in front, and are we active". */
    private fun foregroundLabel(): String {
        val pkg = AppStateManager.foregroundPackage ?: return "未知（按激活处理）"
        val label = TargetApps.ALL.firstOrNull { it.packageName == pkg }?.label ?: pkg
        return if (AppStateManager.targetActive) "$label · 运行中" else "$label · 待机"
    }

    private fun isInstalled(packageName: String): Boolean =
        runCatching {
            packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrDefault(false)

    // ------------------------------------------------------------ permissions --

    private fun requestCameraPermissionIfNeeded() {
        if (hasCameraPermission()) {
            requestNotificationPermissionIfNeeded()
            maybeStartService()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (hasNotificationPermission()) return
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Only asked from onResume, and only while something is genuinely missing. */
    private fun requestMissingPermissions() {
        if (!hasCameraPermission()) {
            cameraPermission.launch(Manifest.permission.CAMERA)
            return
        }
        requestNotificationPermissionIfNeeded()
    }

    private fun requestShizukuPermissionIfNeeded() {
        if (!ShizukuSwipeDispatcher.isBinderAvailable()) return
        if (ShizukuSwipeDispatcher.hasPermission()) return
        if (Shizuku.isPreV11()) return
        // "Deny and don't ask again": stop nagging automatically. The hint tap
        // still asks on demand.
        if (Shizuku.shouldShowRequestPermissionRationale()) return
        ShizukuSwipeDispatcher.requestPermission()
    }

    /** One tap does whatever is still missing, in order. */
    private fun onHintClicked() {
        when {
            !ShizukuSwipeDispatcher.isInstalled(this) -> {
                // No Shizuku on this device: make the accessibility fallback work.
                SwipeInjector.bootstrap(this)
                render()
                toast(getString(R.string.bootstrap_tried, SwipeInjector.activeBackend(this)))
            }

            !ShizukuSwipeDispatcher.isBinderAvailable() -> {
                if (ShizukuSwipeDispatcher.launchShizukuApp(this)) {
                    toast(getString(R.string.shizuku_opened))
                } else {
                    toast(getString(R.string.shizuku_launch_failed))
                }
            }

            !ShizukuSwipeDispatcher.hasPermission() ->
                ShizukuSwipeDispatcher.requestPermission()

            else -> toast(getString(R.string.shizuku_already_granted))
        }
    }

    // ----------------------------------------------------------------- service --

    /** Start the foreground service as soon as the camera permission is in. */
    private fun maybeStartService() {
        if (!hasCameraPermission()) return
        if (GazeCameraService.isRunning()) return
        runCatching { GazeCameraService.start(this) }
        // The service comes up asynchronously, and this screen does not observe
        // GazeRuntime, so refresh the status line once it has had time to start.
        binding.root.postDelayed({
            if (!isFinishing && !isDestroyed) render()
        }, 2500L)
    }

    /**
     * Setup is done: get out of the user's way. Skipped when the screen was
     * opened on purpose to change settings, and only ever done once per instance
     * so that a second launcher tap reveals the settings.
     */
    private fun maybeAutoBackground() {
        if (showSettings || autoBackgrounded) {
            android.util.Log.i(
                "MainActivity",
                "auto-background skipped (showSettings=$showSettings already=$autoBackgrounded)",
            )
            return
        }
        autoBackgrounded = true
        requestShizukuPermissionIfNeeded()
        val moved = moveTaskToBack(true)
        android.util.Log.i("MainActivity", "moveTaskToBack -> $moved")
    }

    // ------------------------------------------------------------------ render --

    private fun shouldShowSettings(intent: Intent?): Boolean =
        intent?.getBooleanExtra(EXTRA_OPEN_SETTINGS, false) == true

    private fun render() {
        val shizuku = ShizukuSwipeDispatcher.statusText(this)
        val backend = SwipeInjector.activeBackend(this)
        val service = if (GazeCameraService.isRunning()) "运行中" else "未启动"

        binding.tvHint.text = if (permissionsReady()) {
            getString(
                R.string.hint_ready,
                shizuku,
                backend,
                service,
                foregroundLabel(),
            )
        } else {
            getString(
                R.string.hint_first_run,
                yesNo(hasCameraPermission()),
                yesNo(hasNotificationPermission()),
                shizuku,
                backend,
                service,
            )
        }
    }

    private fun permissionsReady(): Boolean = hasCameraPermission() && hasNotificationPermission()

    private fun yesNo(granted: Boolean) = if (granted) "已授予" else "未授予"

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
