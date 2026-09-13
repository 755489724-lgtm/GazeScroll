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

            append("眨眼累计 ").append(s.blinkCount)
            append("    已触发 #").append(s.triggers)
            append("    人脸=").append(if (s.faceDetected) "有" else "无")
            append("    分析").append(if (s.analyzing) "中" else "已暂停")
            if (s.standby) append("（省电 1fps）")
        }
        binding.tvLiveValues.text = text
    }

    private fun fmt(v: Float?): String = v?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "--"

    // ----------------------------------------------------------------- settings --

    private fun setupSettingsUi() {
        val cfg = GazeRuntime.config

        binding.rgBlinkCount.check(
            when (cfg.blinkTriggerCount) {
                1 -> R.id.rbBlink1
                3 -> R.id.rbBlink3
                else -> R.id.rbBlink2
            }
        )
        binding.rgBlinkCount.setOnCheckedChangeListener { _, checkedId ->
            val count = when (checkedId) {
                R.id.rbBlink1 -> 1
                R.id.rbBlink3 -> 3
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
