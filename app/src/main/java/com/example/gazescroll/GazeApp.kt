package com.example.gazescroll

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraXConfig

/**
 * Supplies CameraX configuration before the library initialises.
 *
 * `setAvailableCamerasLimiter` restricts camera enumeration to the front camera.
 * Without it CameraX probes every camera on the device (including the two rear
 * ones on this phone), and that enumeration happens before the first
 * `bindToLifecycle` — so it sits directly on the critical path of "how fast does
 * the camera open after the user taps Douyin".
 *
 * NOTE: `setCameraOpenRetryMaxTimeout` does **not** exist in CameraX 1.3.4 (it was
 * added in a later line). It is deliberately not used here; see the README.
 */
class GazeApp : Application(), CameraXConfig.Provider {

    /**
     * v5.47：界面主题（白色 / 黑夜 / 跟随系统）必须在**任何 Activity 之前**定下来，
     * 否则第一帧会按系统配色画一遍再跳成用户选的配色。存在 [UiPrefs] 里，
     * 与用户的检测设置完全隔离（见 UiPrefs 的注释）。
     */
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(UiPrefs.nightModeOf(UiPrefs.themeMode(this)))
    }

    override fun getCameraXConfig(): CameraXConfig =
        CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
            .setAvailableCamerasLimiter(CameraSelector.DEFAULT_FRONT_CAMERA)
            .build()
}
