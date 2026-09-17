package com.example.gazescroll

import android.Manifest
import android.app.AppOpsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.View
import android.widget.CheckBox
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
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

        /** 主题切换会让 Activity 重建，用这个把「设置页开着」这件事带过去。 */
        private const val STATE_DRAWER_OPEN = "drawerOpen"
    }

    private lateinit var binding: ActivityMainBinding

    /** True when this launch is explicitly about changing settings. */
    private var showSettings = false

    /** Shizuku 授权只自动问一次（和旧版「每次启动问一次」的行为一致）。 */
    private var shizukuPermissionAsked = false

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
        setupTiltVolumeUi()
        setupProbeUi()
        setupGazeGateUi()
        setupAdaptiveSwipeUi()
        setupGlobalPagingUi()
        setupSensitivityUi()
        setupTargetApps()
        setupThemeUi()
        setupFeatureCards()
        setupSettingsPanel()
        setupParamSliders()

        binding.tvHint.setOnClickListener { onHintClicked() }
        binding.btnRestartService.setOnClickListener { restartDetectionService() }
        binding.btnOpenSettings.setOnClickListener { openSettingsPanel() }
        binding.btnCloseSettings.setOnClickListener { closeSettingsPanel() }
        // v5.61：给别人手机用时的分步引导 —— 点按钮直接跳系统无障碍页，首页那条提示打开设置页。
        binding.btnOpenA11ySettings.setOnClickListener { openAccessibilitySettings() }
        binding.homeSetupBanner.setOnClickListener { openSettingsPanel() }
        // 「首页任意位置向左滑一下」呼出设置（DrawerLayout 自带的边缘手势只认最右边那条边）。
        binding.homeRoot.onSwipeLeft = { openSettingsPanel() }
        binding.tvVersion.text = getString(R.string.about_version, BuildConfig.VERSION_NAME)

        // 从通知点进来的（或主题切换重建前的）直接展开设置页。
        if (showSettings || savedInstanceState?.getBoolean(STATE_DRAWER_OPEN) == true) {
            openSettingsPanel()
        }

        render()
        requestCameraPermissionIfNeeded()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(
            STATE_DRAWER_OPEN,
            binding.drawerLayout.isDrawerOpen(binding.settingsPanel),
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (shouldShowSettings(intent)) {
            showSettings = true
            openSettingsPanel()
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
            "onResume ready=$ready showSettings=$showSettings",
        )

        if (ready) {
            // Remember it, so we never prompt or show onboarding again.
            AppPrefs.setSetupComplete(this, true)
            maybeStartService()
            // 旧版是在「自动退到后台」之前问这一次；现在不退后台了，但这一问保留。
            if (!shizukuPermissionAsked) {
                shizukuPermissionAsked = true
                requestShizukuPermissionIfNeeded()
            }
        } else {
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

        // v5.60：点头/仰头角度原来是这里的 3 档单选，现在改成卡片里的两个滑块
        // （见 setupParamSliders）—— 且仰头有了自己独立的一个值。
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

            // v5.35：歪头控音量 —— 把「当前歪了多少、已经保持多久」直接显示出来。
            // 用户能看着数字涨到设定时长、也能核对哪边是"正在歪"，判定不灵时第一个要看这里。
            if (cfg.tiltVolumeEnabled) {
                val tilt = s.tiltDeg
                append("歪头：当前 ")
                append(tilt?.let { String.format(java.util.Locale.US, "%+.1f", it) } ?: "--")
                append("°    已保持 ").append(s.tiltHeldMs).append("ms")
                append("    需要 ").append(cfg.tiltThresholdDeg.toInt()).append("° 并保持 ")
                append(cfg.tiltHoldMs).append("ms")
                append("（左歪头 = ").append(if (cfg.tiltLeftVolumeUp) "调高" else "调低")
                append("，右歪头 = ").append(if (cfg.tiltRightVolumeUp) "调高" else "调低")
                append("）    已调音量 ").append(s.tiltVolumeSteps).append(" 档\n")
            }

            append("眨眼累计 ").append(s.blinkCount)
            append("    已触发 #").append(s.triggers)
            append("    人脸=").append(if (s.faceDetected) "有" else "无")
            append("    分析").append(if (s.analyzing) "中" else "已暂停")
            if (s.standby) append("（省电 1fps）")
        }
        binding.tvLiveValues.text = text
        // v5.47：每张功能卡展开后只显示跟自己有关的那几行读数（一级界面保持干净）。
        binding.tvLiveBlink.text = blinkLiveText(s, cfg)
        binding.tvLiveHead.text = headLiveText(s, cfg)
        binding.tvLiveYaw.text = yawLiveText(s, cfg)
        binding.tvLiveMouth.text = mouthLiveText(s, cfg)
        binding.tvLiveTilt.text = tiltLiveText(s, cfg)
        renderCooldownLive(s)
        renderAdaptiveSwipeUi(s)
        renderProbeUi(s)
        renderGazeGateUi(s)
        binding.tvGlobalPagingState.text =
            globalPagingStateText(GazeRuntime.config.globalPagingEnabled, this)
    }

    // ------------------------------------------- v5.47 卡片里的分项实时读数 --

    /** 眨眼卡：左右眼睁开度 + 逐帧判定。 */
    private fun blinkLiveText(s: GazeRuntime.Snapshot, cfg: GazeConfig): String {
        val left = s.leftEyeOpen
        val right = s.rightEyeOpen
        if (left == null && right == null) return getString(R.string.settings_live_empty)
        val closedNow = (left != null && left < cfg.blinkClosedBelow) ||
            (right != null && right < cfg.blinkClosedBelow)
        return "左眼 ${fmt(left)}    右眼 ${fmt(right)}    " +
            (if (closedNow) "闭眼" else "睁眼") +
            "    闭眼阈值 ${fmt(cfg.blinkClosedBelow)}"
    }

    /** 点头卡：俯仰角 / 基准线 / 阈值。 */
    private fun headLiveText(s: GazeRuntime.Snapshot, cfg: GazeConfig): String {
        val pitch = s.headAngleDeg ?: return getString(R.string.settings_live_empty)
        val base = s.headBaselineDeg?.let { "    基准 ${fmt(it)}°" }.orEmpty()
        return "俯仰 ${fmt(pitch)}°$base    阈值 ±${cfg.headPoseAngleThreshold.toInt()}°"
    }

    /** 扭头卡：偏航角 / 方向 / 阈值。 */
    private fun yawLiveText(s: GazeRuntime.Snapshot, cfg: GazeConfig): String {
        val yaw = s.headYawDeg ?: return getString(R.string.settings_live_empty)
        val dir = when {
            yaw < 0f -> "正在向左"
            yaw > 0f -> "正在向右"
            else -> "在中间"
        }
        return "偏航 ${fmt(yaw)}°    $dir    阈值 ±${cfg.horizontalSwipeAngleThreshold.toInt()}°" +
            "    已扭头 ${s.turnCount} 次"
    }

    /** 张嘴卡：张嘴量 / 本人闭嘴基准 / 阈值。 */
    private fun mouthLiveText(s: GazeRuntime.Snapshot, cfg: GazeConfig): String {
        val now = s.mouthRatio ?: return getString(R.string.settings_live_empty)
        val base = s.mouthBaseline
            ?: return "张嘴比例 ${fmt3(now)}（正在学你的闭嘴基准…）"
        val open = now - base
        val pct = "%.1f".format(java.util.Locale.US, open * 100)
        val thr = "%.0f".format(java.util.Locale.US, cfg.mouthSensitivity.fraction * 100)
        return "张嘴量 $pct% 脸高    阈值 $thr%    " +
            (if (s.mouthOpen) "张嘴" else "闭嘴") +
            "    已点击 ${s.mouthTapCount} 次"
    }

    /** 歪头卡：当前倾斜角 / 已保持多久 / 目标时长。 */
    private fun tiltLiveText(s: GazeRuntime.Snapshot, cfg: GazeConfig): String {
        val tilt = s.tiltDeg?.let { String.format(java.util.Locale.US, "%+.1f", it) } ?: "--"
        return "当前 $tilt°    已保持 ${s.tiltHeldMs}ms / 需要 ${cfg.tiltThresholdDeg.toInt()}° 且保持 " +
            "${cfg.tiltHoldMs}ms    已调 ${s.tiltVolumeSteps} 档"
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

        binding.switchHorizontalSwipe.setOnCheckedChangeListener { _, checked ->
            updateConfig { it.copy(horizontalSwipeEnabled = checked) }
            renderHorizontalSwipeUi()
        }
        binding.switchHorizInvert.setOnCheckedChangeListener { _, checked ->
            updateConfig { it.copy(horizontalSwipeInvertYaw = checked) }
        }
        // v5.60：扭头灵敏度的 3 档单选改成滑块（见 setupParamSliders）。

        renderHorizontalSwipeUi()
    }

    /** 关闭时把灵敏度相关控件置灰，避免看起来能调却不起作用。 */
    private fun renderHorizontalSwipeUi() {
        val enabled = GazeRuntime.config.horizontalSwipeEnabled
        binding.switchHorizInvert.isEnabled = enabled
        for (v in listOf<android.view.View>(
            binding.tvHorizThreshold,
            binding.switchHorizInvert,
            binding.tvYawValue,
            binding.btnYawReset,
            binding.seekYaw,
        )) {
            v.isEnabled = enabled
            v.setAlpha(if (enabled) 1f else 0.45f)
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

    // ------------------------------------------- v5.35 歪头控音量 --

    /**
     * 「歪头 → 音量加 / 减」的总开关 + 左右方向开关 + 触发角度 + 保持时长 + 档位。
     *
     * 这是 v5.35 用户点名的改动：**删掉「单眼闭眼控音量」**（v5.30~v5.34 四轮都做不稳），
     * 改成歪头。用户原话：「左歪头上升，右歪头下降……也给用户自己选择左歪头降低还是增加，
     * 还是右歪头降低还是增加，不过这个判断方式可以久一点，意思是仰头得到一定的角度，
     * 才会触发」。
     *
     * 所以：方向两边各自可反转；**必须歪到一定角度（默认 18°）并保持住（默认 0.5 秒）**
     * 才触发。下面的「实时数值」会实时显示当前倾斜角与已保持时长。
     */
    private fun setupTiltVolumeUi() {
        val cfg = GazeRuntime.config
        binding.switchTiltVolume.isChecked = cfg.tiltVolumeEnabled
        binding.switchTiltLeftUp.isChecked = cfg.tiltLeftVolumeUp
        binding.switchTiltRightUp.isChecked = cfg.tiltRightVolumeUp

        binding.switchTiltVolume.setOnCheckedChangeListener { _, checked ->
            updateConfig { it.copy(tiltVolumeEnabled = checked) }
            renderTiltVolumeUi()
        }
        binding.switchTiltLeftUp.setOnCheckedChangeListener { _, checked ->
            updateConfig { it.copy(tiltLeftVolumeUp = checked) }
            renderTiltVolumeUi()
        }
        binding.switchTiltRightUp.setOnCheckedChangeListener { _, checked ->
            updateConfig { it.copy(tiltRightVolumeUp = checked) }
            renderTiltVolumeUi()
        }

        // 触发角度（用户要求"得到一定的角度才触发"；v5.36 按"再灵敏一点"整体下调一档）。
        // v5.60：原来的 4 档单选改成滑块（见 setupParamSliders），这里只剩保持时长与档位。

        // 保持时长（v5.36 同样调灵一档，默认 0.3 秒）。
        binding.rgTiltHold.check(
            when {
                cfg.tiltHoldMs <= 200L -> R.id.rbTiltHold200
                cfg.tiltHoldMs >= 800L -> R.id.rbTiltHold800
                cfg.tiltHoldMs >= 500L -> R.id.rbTiltHold500
                else -> R.id.rbTiltHold300
            },
        )
        binding.rgTiltHold.setOnCheckedChangeListener { _, checkedId ->
            val hold = when (checkedId) {
                R.id.rbTiltHold200 -> 200L
                R.id.rbTiltHold500 -> 500L
                R.id.rbTiltHold800 -> 800L
                else -> 300L
            }
            updateConfig { it.copy(tiltHoldMs = hold) }
            renderTiltVolumeUi()
        }

        // 每次调整多少档。
        binding.rgTiltStep.check(
            when (cfg.tiltVolumeStep) {
                1 -> R.id.rbTiltStep1
                3 -> R.id.rbTiltStep3
                5 -> R.id.rbTiltStep5
                else -> R.id.rbTiltStep2
            },
        )
        binding.rgTiltStep.setOnCheckedChangeListener { _, checkedId ->
            val step = when (checkedId) {
                R.id.rbTiltStep1 -> 1
                R.id.rbTiltStep3 -> 3
                R.id.rbTiltStep5 -> 5
                else -> 2
            }
            updateConfig { it.copy(tiltVolumeStep = step) }
            renderTiltVolumeUi()
        }

        renderTiltVolumeUi()
    }

    /** 总开关关掉时，方向 / 角度 / 时长 / 档位几组设置一起置灰；值仍保留，重新打开即恢复。 */
    private fun renderTiltVolumeUi() {
        val enabled = GazeRuntime.config.tiltVolumeEnabled
        val views = mutableListOf<android.view.View>(
            binding.switchTiltLeftUp,
            binding.switchTiltRightUp,
            binding.tvTiltThreshold,
            binding.tvTiltValue,
            binding.btnTiltReset,
            binding.seekTilt,
            binding.tvTiltHold,
            binding.tvTiltStep,
        )
        for (id in intArrayOf(
            R.id.rbTiltHold200, R.id.rbTiltHold300, R.id.rbTiltHold500, R.id.rbTiltHold800,
            R.id.rbTiltStep1, R.id.rbTiltStep2, R.id.rbTiltStep3, R.id.rbTiltStep5,
        )) {
            binding.root.findViewById<android.view.View>(id)?.let { views.add(it) }
        }
        for (v in views) {
            v.isEnabled = enabled
            v.setAlpha(if (enabled) 1f else 0.45f)
        }
        // 方向 / 角度 / 时长一变，实时区那行文字也跟着变，所以立刻重画。
        renderLive(GazeRuntime.snapshot)
    }

    // ------------------------------------ v5.39：注视数据采集（测试功能） --

    /**
     * 「注视数据采集」开关 + 实时状态。
     *
     * 这是给「眼睛必须盯着屏幕才触发翻页」做数据准备用的测试功能：打开后每帧多算一组
     * 原始几何量并写进 `files/probe/probe-*.csv`，**不做任何判定、不改任何现有行为**。
     * 起止全部由"盖住前置摄像头"控制（盖 3 秒 = 开始 / 结束，盖 1 秒 = 分段），
     * 所以录制过程中用户全程不需要碰手机屏幕 —— 这也是为什么状态要显示得这么直白。
     */
    private fun setupProbeUi() {
        binding.switchGazeProbe.isChecked = GazeRuntime.config.probeEnabled
        binding.switchGazeProbe.setOnCheckedChangeListener { _, checked ->
            updateConfig { it.copy(probeEnabled = checked) }
            renderProbeUi(GazeRuntime.snapshot)
        }
        renderProbeUi(GazeRuntime.snapshot)
    }

    /** 采集状态一行字：关 / 待机 / 已就绪 / 录制中（第几段、多少帧、几秒、文件名）。 */
    private fun renderProbeUi(s: GazeRuntime.Snapshot) {
        val on = GazeRuntime.config.probeEnabled
        binding.tvProbeState.text = when {
            !on -> getString(R.string.settings_probe_state_off)

            s.probeState == ProbeState.RECORDING.label -> {
                val seconds = String.format(java.util.Locale.US, "%.1f", s.probeDurationMs / 1000f)
                getString(R.string.settings_probe_state_rec, s.probePhase, s.probeRows, seconds) +
                    "\n文件：" + s.probeFile
            }

            s.probeState == ProbeState.ARMED.label -> getString(R.string.settings_probe_state_armed)

            s.faceDetected -> getString(R.string.settings_probe_state_idle)

            else -> getString(R.string.settings_probe_state_idle_noface)
        }
        binding.tvProbeLast.text =
            if (s.probeLast.isEmpty()) "" else getString(R.string.settings_probe_last, s.probeLast)
    }

    // ------------------------------------- v5.43：注视门（眼睛得盯着屏幕） --

    /**
     * 「注视门」三档模式 + 实时状态。
     *
     * 默认「观察」：只把"本来会拦掉哪一次触发"写进日志，一次都不拦 —— 这是有意的
     * （v5.37 就是加严过头被整版退回的），先量代价再决定要不要默认拦截。
     * 判据与实测依据见 [GazeGate] 的注释。
     */
    private fun setupGazeGateUi() {
        val mode = GazeRuntime.config.gazeGateMode
        binding.rgGateMode.check(
            when (mode) {
                GateMode.OFF -> R.id.rbGateOff
                GateMode.ENFORCE -> R.id.rbGateEnforce
                else -> R.id.rbGateObserve
            },
        )
        binding.rgGateMode.setOnCheckedChangeListener { _, checkedId ->
            val next = when (checkedId) {
                R.id.rbGateOff -> GateMode.OFF
                R.id.rbGateEnforce -> GateMode.ENFORCE
                else -> GateMode.OBSERVE
            }
            updateConfig { it.copy(gazeGateMode = next) }
            renderGazeGateUi(GazeRuntime.snapshot)
        }
        renderGazeGateUi(GazeRuntime.snapshot)
    }

    /** 实时状态一行字：现在算不算"盯着屏幕"、卡在哪条判据、睁眼占比多少。 */
    private fun renderGazeGateUi(s: GazeRuntime.Snapshot) {
        if (GazeRuntime.config.gazeGateMode == GateMode.OFF) {
            binding.tvGateState.text = getString(R.string.settings_gate_state_off)
            return
        }
        val duty = s.gazeGateDuty?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "--"
        val state = when {
            s.gazeGateText == "OK" -> "看着屏幕（门是开的）"
            s.gazeGateText.startsWith("BLOCK") -> "没在看着屏幕 → " + s.gazeGateText
            else -> s.gazeGateText
        }
        binding.tvGateState.text = getString(R.string.settings_gate_state, state, duty)
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

    // -------------------------------------------- v5.60 可调参数（滑块 + 默认） --

    /**
     * 一个「标签 + 当前值 + 「默认」按钮 + 滑块」的参数行。
     *
     * 用户要求：把触发速度、仰头角度、扭头角度、歪头角度**全部交给用户自己调**，
     * 旁边加「恢复默认」防止调坏 —— 所以每个参数都带自己的默认值（= v5.51 交付时他那套）。
     */
    private data class ParamSpec(
        val seekId: Int,
        val valueId: Int,
        val resetId: Int,
        val min: Float,
        val max: Float,
        val step: Float,
        val defaultValue: Float,
        val unit: String,
        val decimals: Int,
        val read: () -> Float,
        val write: (Float) -> Unit,
    )

    private fun setupParamSliders() {
        val specs = listOf(
            ParamSpec(
                R.id.seekPitchDown, R.id.tvPitchDownValue, R.id.btnPitchDownReset,
                GazeConfig.MIN_PITCH_DEG, GazeConfig.MAX_PITCH_DEG, GazeConfig.STEP_PITCH_DEG,
                GazeConfig.RESET_PITCH_DOWN_DEG, "°", 1,
                read = { GazeRuntime.config.headPoseAngleThreshold },
                write = { v -> updateConfig { it.copy(headPoseAngleThreshold = v) } },
            ),
            ParamSpec(
                R.id.seekPitchUp, R.id.tvPitchUpValue, R.id.btnPitchUpReset,
                GazeConfig.MIN_PITCH_DEG, GazeConfig.MAX_PITCH_DEG, GazeConfig.STEP_PITCH_DEG,
                GazeConfig.RESET_PITCH_UP_DEG, "°", 1,
                read = { GazeRuntime.config.headPoseUpThresholdDeg },
                write = { v -> updateConfig { it.copy(headPoseUpThresholdDeg = v) } },
            ),
            ParamSpec(
                R.id.seekSpeed, R.id.tvSpeedValue, R.id.btnSpeedReset,
                GazeConfig.MIN_MOTION_WINDOW_MS.toFloat(), GazeConfig.MAX_MOTION_WINDOW_MS.toFloat(),
                GazeConfig.STEP_MOTION_WINDOW_MS.toFloat(),
                GazeConfig.RESET_MOTION_WINDOW_MS.toFloat(), "ms", 0,
                read = { GazeRuntime.config.headPoseMotionWindowMs.toFloat() },
                write = { v -> updateConfig { it.copy(headPoseMotionWindowMs = v.toLong()) } },
            ),
            ParamSpec(
                R.id.seekYaw, R.id.tvYawValue, R.id.btnYawReset,
                GazeConfig.MIN_YAW_DEG, GazeConfig.MAX_YAW_DEG, GazeConfig.STEP_YAW_DEG,
                GazeConfig.RESET_YAW_DEG, "°", 1,
                read = { GazeRuntime.config.horizontalSwipeAngleThreshold },
                write = { v -> updateConfig { it.copy(horizontalSwipeAngleThreshold = v) } },
            ),
            ParamSpec(
                R.id.seekTilt, R.id.tvTiltValue, R.id.btnTiltReset,
                GazeConfig.MIN_TILT_DEG, GazeConfig.MAX_TILT_DEG, GazeConfig.STEP_TILT_DEG,
                GazeConfig.RESET_TILT_DEG, "°", 1,
                read = { GazeRuntime.config.tiltThresholdDeg },
                write = { v -> updateConfig { it.copy(tiltThresholdDeg = v) } },
            ),
        )
        for (spec in specs) bindParam(spec)
    }

    private fun bindParam(spec: ParamSpec) {
        val seek = binding.root.findViewById<android.widget.SeekBar>(spec.seekId) ?: return
        val value = binding.root.findViewById<android.widget.TextView>(spec.valueId) ?: return
        seek.max = Math.round((spec.max - spec.min) / spec.step)
        seek.progress = stepFor(spec, spec.read())
        value.text = formatParam(spec, spec.read())

        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(
                seekBar: android.widget.SeekBar?,
                progress: Int,
                fromUser: Boolean,
            ) {
                if (fromUser) spec.write(valueFor(spec, progress))
                value.text = formatParam(spec, valueFor(spec, progress))
                // 实时读数里也印着这些阈值，跟着一起刷。
                renderLive(GazeRuntime.snapshot)
            }

            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                val progress = seekBar?.progress ?: 0
                spec.write(valueFor(spec, progress))
                value.text = formatParam(spec, valueFor(spec, progress))
                render()
            }
        })

        binding.root.findViewById<View>(spec.resetId)?.setOnClickListener {
            spec.write(spec.defaultValue)
            seek.progress = stepFor(spec, spec.defaultValue)
            value.text = formatParam(spec, spec.defaultValue)
            render()
            renderLive(GazeRuntime.snapshot)
            toast(getString(R.string.param_reset_done, formatParam(spec, spec.defaultValue)))
        }
    }

    private fun valueFor(spec: ParamSpec, step: Int): Float = spec.min + step * spec.step

    private fun stepFor(spec: ParamSpec, value: Float): Int =
        Math.round((value.coerceIn(spec.min, spec.max) - spec.min) / spec.step)

    private fun formatParam(spec: ParamSpec, value: Float): String =
        if (spec.decimals == 0) {
            "${value.toInt()}${spec.unit}"
        } else {
            String.format(java.util.Locale.US, "%.1f%s", value, spec.unit)
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
     * v5.47：**不再「打开就自动退到后台」**。
     *
     * 旧行为是权限一齐就 `moveTaskToBack`，好处是点图标不挡着抖音；但新首页做出来以后，
     * 那等于每次打开都看不到界面。现在打开就停在首页，用户按返回键自己退到后台 ——
     * 前台服务和翻页完全不受影响（用户 v5.47 明确选的就是这个行为）。
     *
     * 重启相机那段逻辑抽出来，是因为设置页里的「前台检测服务」那一行也要用它。
     */
    private fun restartDetectionService() {
        runCatching { GazeCameraService.restart(this) }
        toast(getString(R.string.restart_requested))
        binding.root.postDelayed({
            if (!isFinishing && !isDestroyed) {
                render()
                renderLive(GazeRuntime.snapshot)
            }
        }, 2500L)
    }

    // ---------------------------------------------------------- v5.47 主题 --

    /**
     * 主题三档：白色（默认）/ 黑夜 / 跟随系统。
     *
     * 存进 [UiPrefs] 自己的文件（`ui_prefs.xml`），**与用户的检测设置完全隔离** ——
     * v5.46 那次「手改 prefs 把设置清空」的事故在结构上不会再发生（HANDOVER §7.1）。
     * 真正换肤交给 AppCompatDelegate：它会在需要时重建 Activity，所以这里只是声明。
     */
    private fun setupThemeUi() {
        binding.rgTheme.check(
            when (UiPrefs.themeMode(this)) {
                UiPrefs.THEME_DARK -> R.id.rbThemeDark
                UiPrefs.THEME_SYSTEM -> R.id.rbThemeSystem
                else -> R.id.rbThemeLight
            },
        )
        binding.rgTheme.setOnCheckedChangeListener { _, checkedId ->
            val next = when (checkedId) {
                R.id.rbThemeDark -> UiPrefs.THEME_DARK
                R.id.rbThemeSystem -> UiPrefs.THEME_SYSTEM
                else -> UiPrefs.THEME_LIGHT
            }
            if (next == UiPrefs.themeMode(this)) return@setOnCheckedChangeListener
            UiPrefs.setThemeMode(this, next)
            AppCompatDelegate.setDefaultNightMode(UiPrefs.nightModeOf(next))
        }
    }

    // ------------------------------------------------------ v5.47 首页卡片 --

    /**
     * 功能卡：一级只留「标题 + 一句话说明 + 开关」，详细设置与长解释全在三角里。
     *
     * 卡片顺序 = 布局里 [ActivityMainBinding.llCards] 子 View 的物理顺序；
     * v5.48 的自由排序就是重排这些子 View（长按标题行拖动），顺序存在 UiPrefs 里。
     */
    private fun setupFeatureCards() {
        for (c in featureCards) {
            val card = binding.root.findViewById<View>(c.cardId) ?: continue
            val header = binding.root.findViewById<View>(c.headerId) ?: continue
            // 短按 = 展开 / 收起二级详情；长按 = 把这张卡抬起来拖动排序。
            header.setOnClickListener { toggleCard(c.detailId, c.chevronId) }
            header.setOnLongClickListener {
                startCardDrag(card)
                true
            }
            header.setOnTouchListener { _, event -> onCardTouch(event) }
        }
        applySavedCardOrder()
    }

    // ------------------------------------------------------ v5.48 自由排序 --

    /** 一张功能卡的四个 id：外层卡片 / 标题行 / 二级详情 / 三角。列表顺序 = 默认顺序。 */
    private data class FeatureCard(
        val cardId: Int,
        val headerId: Int,
        val detailId: Int,
        val chevronId: Int,
    )

    private val featureCards = listOf(
        FeatureCard(R.id.cardHeadPose, R.id.headerHeadPose, R.id.detailHeadPose, R.id.ivChevHeadPose),
        FeatureCard(R.id.cardTurn, R.id.headerTurn, R.id.detailTurn, R.id.ivChevTurn),
        FeatureCard(R.id.cardTilt, R.id.headerTilt, R.id.detailTilt, R.id.ivChevTilt),
        FeatureCard(R.id.cardBlink, R.id.headerBlink, R.id.detailBlink, R.id.ivChevBlink),
        FeatureCard(R.id.cardMouth, R.id.headerMouth, R.id.detailMouth, R.id.ivChevMouth),
        FeatureCard(R.id.cardGate, R.id.headerGate, R.id.detailGate, R.id.ivChevGate),
        FeatureCard(R.id.cardGuard, R.id.headerGuard, R.id.detailGuard, R.id.ivChevGuard),
        FeatureCard(R.id.cardSwipe, R.id.headerSwipe, R.id.detailSwipe, R.id.ivChevSwipe),
        FeatureCard(R.id.cardTargets, R.id.headerTargets, R.id.detailTargets, R.id.ivChevTargets),
        FeatureCard(R.id.cardGlobal, R.id.headerGlobal, R.id.detailGlobal, R.id.ivChevGlobal),
    )

    /** 手指最后一次落点的屏幕 Y —— 长按回调拿不到坐标，所以在触摸回调里先记下来。 */
    private var lastTouchRawY = 0f

    /** 正在被拖动的卡片；null = 没在拖。 */
    private var dragCard: View? = null
    private var dragStartRawY = 0f

    /** 松手后要落到的槽位（拖动过程中只算不换，见 [endCardDrag] 的注释）。 */
    private var dragTargetIndex = -1

    private fun onCardTouch(event: android.view.MotionEvent): Boolean {
        lastTouchRawY = event.rawY
        val card = dragCard ?: return false
        return when (event.actionMasked) {
            android.view.MotionEvent.ACTION_MOVE -> {
                dragCardTo(event.rawY)
                true
            }

            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                endCardDrag()
                true
            }

            else -> false
        }
    }

    /** 长按标题行：把这张卡「抬起来」（放大 + 阴影 + 一下震动）。 */
    private fun startCardDrag(card: View) {
        if (dragCard != null) return
        dragCard = card
        dragStartRawY = lastTouchRawY
        dragTargetIndex = binding.llCards.indexOfChild(card)
        card.elevation = 8f * resources.displayMetrics.density
        card.scaleX = 1.02f
        card.scaleY = 1.02f
        card.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)

        // v5.49（装机实测抓到的）：纵向一动手势就会被外层 ScrollView 抢走 ——
        // `ScrollView.onInterceptTouchEvent` 超过 touchSlop 就拦截，子 View 只会收到
        // ACTION_CANCEL，于是「长按抬起来了、一拖却变成滚页面」。
        // 拖动期间禁止父级拦截（这个标记会一路传到 ScrollView 与 DrawerLayout）。
        card.parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun dragCardTo(rawY: Float) {
        val card = dragCard ?: return
        val from = binding.llCards.indexOfChild(card)
        if (from < 0) return
        card.translationY = rawY - dragStartRawY
        val loc = IntArray(2)
        binding.llCards.getLocationOnScreen(loc)
        dragTargetIndex = cardIndexAt(card, from, rawY, loc[1])
        previewShift(card, from, dragTargetIndex)
    }

    /**
     * 拖动过程中的「让位预览」：只改 translationY，**一个视图都不重排**。
     *
     * 这里绕开了一个坑（v5.50 装机实测抓到的）：拖动中如果 `removeView` + `addView` 去实时换位，
     * `removeView` 会当场把被拖卡片的触摸**取消**掉 —— `ACTION_CANCEL` 先跑进 [endCardDrag]
     * （那时 `dragOrderChanged` 还是 false，于是永远存不下去），剩下的手势事件落回 ScrollView
     * 变成滚页面。所以换位推迟到松手之后再做一次，拖动期间只用位移做预览。
     */
    private fun previewShift(card: View, from: Int, target: Int) {
        if (target < 0) return
        val space = (card.height + marginsOf(card)).toFloat()
        for (i in 0 until binding.llCards.childCount) {
            val c = binding.llCards.getChildAt(i)
            if (c === card) continue
            c.translationY = when {
                target > from && i in (from + 1)..target -> -space
                target < from && i in target until from -> space
                else -> 0f
            }
        }
    }

    private fun marginsOf(view: View): Int {
        val lp = view.layoutParams as? android.widget.LinearLayout.LayoutParams ?: return 0
        return lp.topMargin + lp.bottomMargin
    }

    /** 手指现在落在哪一格：越过某张卡的中线就换到它那一格。 */
    private fun cardIndexAt(card: View, from: Int, rawY: Float, containerTop: Int): Int {
        var target = from
        for (i in 0 until binding.llCards.childCount) {
            val c = binding.llCards.getChildAt(i)
            if (c === card) continue
            val mid = containerTop + (c.top + c.bottom) / 2f
            if (i < from && rawY < mid) {
                target = i
                break
            }
            if (i > from && rawY > mid) target = i
        }
        return target
    }

    private fun endCardDrag() {
        val card = dragCard ?: return
        val target = dragTargetIndex
        dragCard = null
        dragTargetIndex = -1
        card.translationY = 0f
        card.elevation = 0f
        card.scaleX = 1f
        card.scaleY = 1f
        // 手势结束，把「不许拦截」还给父级，页面恢复可滚动。
        card.parent?.requestDisallowInterceptTouchEvent(false)
        // 先清掉所有预览位移，再做唯一的一次真实换位。
        for (i in 0 until binding.llCards.childCount) {
            binding.llCards.getChildAt(i).translationY = 0f
        }
        val from = binding.llCards.indexOfChild(card)
        if (target in 0 until binding.llCards.childCount && target != from) {
            binding.llCards.removeView(card)
            binding.llCards.addView(card, target)
            saveCardOrder()
            toast(getString(R.string.card_order_saved))
        }
    }

    /** 顺序按**资源名**存进 ui_prefs.xml（不是数字 id，重新构建也不会串位）。 */
    private fun saveCardOrder() {
        val names = (0 until binding.llCards.childCount).map { i ->
            val v = binding.llCards.getChildAt(i)
            runCatching { resources.getResourceEntryName(v.id) }.getOrNull().orEmpty()
        }
        UiPrefs.setCardOrder(this, names.joinToString(","))
    }

    /** 启动时按存下来的顺序重排；对不上（卡片增删过 / 数量变了）就保持布局默认顺序。 */
    private fun applySavedCardOrder() {
        val saved = UiPrefs.cardOrder(this) ?: return
        val names = saved.split(',').filter { it.isNotBlank() }
        val views = (0 until binding.llCards.childCount).map { binding.llCards.getChildAt(it) }
        val byName = views.associateBy {
            runCatching { resources.getResourceEntryName(it.id) }.getOrNull()
        }
        if (names.size != views.size || names.any { byName[it] == null }) return
        binding.llCards.removeAllViews()
        for (name in names) byName[name]?.let { binding.llCards.addView(it) }
    }

    /** 「恢复默认顺序」：清掉存下来的顺序，并按 [featureCards] 的默认顺序重排。 */
    private fun resetCardOrder() {
        UiPrefs.setCardOrder(this, null)
        val byId = (0 until binding.llCards.childCount).associate {
            val v = binding.llCards.getChildAt(it)
            v.id to v
        }
        binding.llCards.removeAllViews()
        for (c in featureCards) byId[c.cardId]?.let { binding.llCards.addView(it) }
        toast(getString(R.string.card_order_reset_done))
    }

    /** 展开 / 收起一张卡的二级详情，顺带把三角转 180°。 */
    private fun toggleCard(detailId: Int, chevronId: Int) {
        val detail = binding.root.findViewById<View>(detailId) ?: return
        val chevron = binding.root.findViewById<View>(chevronId)
        val expand = detail.visibility != View.VISIBLE
        detail.visibility = if (expand) View.VISIBLE else View.GONE
        chevron?.animate()?.rotation(if (expand) 180f else 0f)?.setDuration(160L)?.start()
    }

    // ---------------------------------------------------- v5.47 右侧设置页 --

    /**
     * 设置页（左滑呼出）：权限与状态 → 外观 → 测试与诊断 → 关于。
     *
     * 权限行点一下就去处理对应权限，用的还是原来那几个入口，没有新增任何权限申请路径。
     */
    private fun setupSettingsPanel() {
        binding.rowPermCamera.setOnClickListener {
            if (hasCameraPermission()) {
                toast(getString(R.string.perm_granted))
            } else {
                cameraPermission.launch(Manifest.permission.CAMERA)
            }
        }
        binding.rowPermNotif.setOnClickListener {
            if (hasNotificationPermission()) {
                toast(getString(R.string.perm_granted))
            } else {
                requestNotificationPermissionIfNeeded()
            }
        }
        // Shizuku 那一行复用原来的「没有就打开 / 有就申请」流程。
        binding.rowPermShizuku.setOnClickListener { onHintClicked() }
        // v5.61：无障碍那一行**直接跳系统无障碍页** —— 以前是"静默尝试自己开"，
        // 在没有 adb 授权（别人的手机）上等于什么都没发生，用户完全不知道该去哪。
        binding.rowPermA11y.setOnClickListener { openAccessibilitySettings() }
        binding.rowPermUsage.setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
        }
        binding.rowPermService.setOnClickListener { restartDetectionService() }
        // v5.48：首页卡片顺序随时可以一键回到默认。
        binding.btnResetCardOrder.setOnClickListener { resetCardOrder() }
    }

    private fun openSettingsPanel() {
        if (!binding.drawerLayout.isDrawerOpen(binding.settingsPanel)) {
            binding.drawerLayout.openDrawer(binding.settingsPanel)
        }
    }

    private fun closeSettingsPanel() {
        if (binding.drawerLayout.isDrawerOpen(binding.settingsPanel)) {
            binding.drawerLayout.closeDrawer(binding.settingsPanel)
        }
    }

    /** 返回键：设置页开着就先收设置页，否则照旧（退到后台，服务继续跑）。 */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(binding.settingsPanel)) {
            closeSettingsPanel()
            return
        }
        super.onBackPressed()
    }

    // ------------------------------------------ v5.61 给别人手机用的分步引导 --

    /**
     * 「装上就能用」的那一步：无障碍还没开起来时，把怎么开讲清楚。
     *
     * 为什么需要它：APK 是侧载安装的，Android 13 起系统默认锁住它的无障碍开关
     * （「受限制的设置：出于安全考虑，此设置目前不可用」）。**adb 并不是必须的** ——
     * 用户手动解锁一次、把无障碍打开，这条腿就通了；难的是那个开关藏得深：
     * 小米/红米把它放在「手机管家 → 应用管理 → 应用信息 → 允许受限制的设置」里，
     * 别的品牌一般在「设置 → 应用 → 右上角 ⋮」。所以这里按品牌给路径，并且把
     * 「允许受限制的设置」这个关键词直接写出来（用户也能拿它去搜）。
     */
    private fun renderSetupGuide() {
        // 只有"一条腿都没有"时才打扰用户；无障碍或 Shizuku 任一条通了这个块就消失。
        val show = !SwipeInjector.isReady(this)
        binding.setupGuide.visibility = if (show) View.VISIBLE else View.GONE
        binding.homeSetupBanner.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return

        binding.tvSetupGuideSteps.text = android.text.Html.fromHtml(
            when {
                // Android 13 以下没有"受限制的设置"这一关，少一步。
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> getString(R.string.guide_old_android)
                isXiaomiBrand() -> getString(R.string.guide_xiaomi)
                else -> getString(R.string.guide_other)
            },
            android.text.Html.FROM_HTML_MODE_LEGACY,
        )
        binding.tvSetupGuideNote.text = android.text.Html.fromHtml(
            getString(R.string.guide_note),
            android.text.Html.FROM_HTML_MODE_LEGACY,
        )
    }

    /** 小米/红米/POCO 都把「允许受限制的设置」藏在手机管家里，文案要给对路径。 */
    private fun isXiaomiBrand(): Boolean {
        val maker = Build.MANUFACTURER.orEmpty()
        return maker.contains("xiaomi", ignoreCase = true) ||
            maker.contains("redmi", ignoreCase = true) ||
            maker.contains("poco", ignoreCase = true)
    }

    private fun openAccessibilitySettings() {
        val opened = runCatching {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }.isSuccess
        if (!opened) toast(getString(R.string.guide_button))
    }

    // ------------------------------------------------ v5.47 首页状态与摘要 --

    /** 顶部那颗胶囊：运行中 / 待机 / 未启动。 */
    private fun renderStatusPill() {
        val (textRes, colorRes) = when {
            !GazeCameraService.isRunning() -> R.string.home_status_stopped to R.color.state_idle
            AppStateManager.targetActive -> R.string.home_status_running to R.color.state_ready
            else -> R.string.home_status_standby to R.color.state_cooldown
        }
        binding.tvStatusPill.text = getString(textRes)
        binding.tvStatusPill.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    /** 设置页里的一排权限状态（只读展示，点行才动作）。 */
    private fun renderPermissionRows() {
        setPermState(
            binding.tvPermCameraState,
            getString(if (hasCameraPermission()) R.string.perm_granted else R.string.perm_denied),
            hasCameraPermission(),
        )
        setPermState(
            binding.tvPermNotifState,
            getString(if (hasNotificationPermission()) R.string.perm_granted else R.string.perm_denied),
            hasNotificationPermission(),
        )
        // Shizuku 自己的状态文案就是人话（未安装 / 未运行 / 待授权 / 已授权），直接用。
        val shizuku = ShizukuSwipeDispatcher.statusText(this)
        setPermState(binding.tvPermShizukuState, shizuku, shizuku == "已授权")

        val a11yOn = GazeAccessibilityService.isConnected() || AccessibilityBootstrap.isServiceEnabled(this)
        val a11yText = when {
            GazeAccessibilityService.isConnected() -> getString(R.string.perm_connected)
            AccessibilityBootstrap.isServiceEnabled(this) -> getString(R.string.perm_on)
            else -> getString(R.string.perm_off)
        }
        setPermState(binding.tvPermA11yState, a11yText, a11yOn)

        setPermState(
            binding.tvPermUsageState,
            getString(if (hasUsageAccess()) R.string.perm_allowed else R.string.perm_not_allowed),
            hasUsageAccess(),
        )
        setPermState(
            binding.tvPermServiceState,
            getString(if (GazeCameraService.isRunning()) R.string.perm_running else R.string.perm_stopped),
            GazeCameraService.isRunning(),
        )
    }

    private fun setPermState(view: android.widget.TextView, text: String, ok: Boolean) {
        view.text = text
        view.setTextColor(
            ContextCompat.getColor(this, if (ok) R.color.state_ready else R.color.state_idle),
        )
    }

    /** 卡片右上角的小标签 + 标题前那个状态圆点。 */
    private fun renderCardSummaries() {
        val cfg = GazeRuntime.config
        binding.tvChipBlink.text = getString(R.string.card_blink_chip, cfg.blinkTriggerCount)
        binding.tvChipGate.text = when (cfg.gazeGateMode) {
            GateMode.ENFORCE -> getString(R.string.card_gate_chip_enforce)
            GateMode.OFF -> getString(R.string.card_gate_chip_off)
            else -> getString(R.string.card_gate_chip_observe)
        }
        val targets = AppPrefs.targetPackages(this).size
        binding.tvChipTargets.text = if (cfg.globalPagingEnabled) {
            getString(R.string.card_targets_chip_all)
        } else {
            getString(R.string.card_targets_chip, targets)
        }

        setDot(binding.dotHeadPose, cfg.headPoseEnabled)
        setDot(binding.dotTurn, cfg.horizontalSwipeEnabled)
        setDot(binding.dotTilt, cfg.tiltVolumeEnabled)
        // 眨眼通道没有开关（一直是开着的），所以点永远是绿的。
        setDot(binding.dotBlink, true)
        setDot(binding.dotMouth, cfg.mouthTapEnabled)
        setDot(binding.dotGate, cfg.gazeGateMode != GateMode.OFF)
        setDot(binding.dotGuard, cfg.globalCooldownEnabled || cfg.staticLockEnabled)
        setDot(binding.dotSwipe, cfg.adaptiveSwipeEnabled)
        setDot(binding.dotTargets, cfg.globalPagingEnabled || targets > 0)
        setDot(binding.dotGlobal, cfg.globalPagingEnabled)
    }

    private fun setDot(view: View, on: Boolean) {
        view.backgroundTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, if (on) R.color.state_ready else R.color.state_idle),
        )
    }

    /** 「使用情况访问」是否已允许（前台应用检测靠它）。 */
    @Suppress("DEPRECATION")
    private fun hasUsageAccess(): Boolean = runCatching {
        val ops = getSystemService(AppOpsManager::class.java)
        ops != null &&
            ops.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName,
            ) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

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

        // v5.47：首页胶囊 + 卡片摘要，以及设置页里那一排权限状态。
        renderStatusPill()
        renderPermissionRows()
        renderCardSummaries()
        // v5.61：后端还不可用时给出来自别的手机也能照做的分步引导。
        renderSetupGuide()
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
