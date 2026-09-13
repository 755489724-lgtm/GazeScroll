package com.example.gazescroll

import android.app.Application
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

    override fun getCameraXConfig(): CameraXConfig =
        CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
            .setAvailableCamerasLimiter(CameraSelector.DEFAULT_FRONT_CAMERA)
            .build()
}
