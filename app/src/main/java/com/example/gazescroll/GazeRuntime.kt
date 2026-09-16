package com.example.gazescroll

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide blackboard shared by the analyzer thread, the foreground service,
 * the overlay windows and the activity.
 *
 * Writers may be on any thread; listeners are always notified on the main
 * thread, so UI code can update views directly.
 */
object GazeRuntime {

    /** Immutable debug/HUD view of the runtime. */
    data class Snapshot(
        val serviceRunning: Boolean = false,
        /** True while the service is honouring triggers. */
        val enabled: Boolean = false,
        val faceDetected: Boolean = false,
        /** Smoothed, screen-normalised eye Y; 0 = top, 1 = bottom. Gaze debug only. */
        val eyeY: Float? = null,
        /** Un-smoothed eye Y, for comparing against the smoothed value. */
        val rawEyeY: Float? = null,
        /** Legacy gaze state machine. Always IDLE unless gazeModeEnabled. */
        val state: GazeState = GazeState.IDLE,
        /** Completed blinks seen since the service started. */
        val blinkCount: Int = 0,
        /** Swipes fired (blink or gaze). */
        val triggers: Int = 0,
        /** No face for a while: analysis is throttled to 1 fps. */
        val standby: Boolean = false,
        /** Screen is on, so the camera is actually running. */
        val analyzing: Boolean = true,

        // ---- live values, shown on the settings screen so thresholds can be
        //      chosen from real numbers instead of guesswork ----
        /** ML Kit `leftEyeOpenProbability` of the newest frame. */
        val leftEyeOpen: Float? = null,
        /** ML Kit `rightEyeOpenProbability` of the newest frame. */
        val rightEyeOpen: Float? = null,
        /** ML Kit `headEulerAngleX` of the newest frame. */
        val headAngleDeg: Float? = null,
        /** Current head-pose baseline (median of the sliding window). */
        val headBaselineDeg: Float? = null,
        /** Latest raw yaw (`headEulerAngleY`), used by the left/right turn gesture. */
        val headYawDeg: Float? = null,
        /** 累计识别到的扭头次数（左右都算）。 */
        val turnCount: Int = 0,

        // ---- v4.5 张嘴暂停：把原始读数暴露出来，方便用户照着真实数值挑灵敏度 ----

        /** 平滑后的张嘴比例（嘴到鼻底距离 / 脸高）；null 表示还没检测到脸。 */
        val mouthRatio: Float? = null,
        /** 估计出的本人自然闭嘴水平（同单位）。 */
        val mouthBaseline: Float? = null,
        /** 当前是否判定为张嘴状态。 */
        val mouthOpen: Boolean = false,
        /** 累计识别到的张嘴次数。 */
        val mouthOpenCount: Int = 0,
        /** 累计由张嘴触发的「屏幕中央点击」次数。 */
        val mouthTapCount: Int = 0,

        // ---- v5.30 单眼闭眼控音量：设置页要能看到"闭了多久"，才好确认判定有效 ----

        /** 累计由单眼闭眼触发的音量档位数。 */
        val winkSteps: Int = 0,
        /** 左眼当前已保持的单闭时长（毫秒）；0 = 左眼没在单闭。 */
        val winkHeldLeftMs: Long = 0L,
        /** 右眼当前已保持的单闭时长（毫秒）；0 = 右眼没在单闭。 */
        val winkHeldRightMs: Long = 0L,

        /** 当前前台应用对应的纵向滑动配置（人话描述），设置页显示用。 */
        val swipeProfile: String = "",
        /**
         * 全局冷却剩余毫秒数；0 表示现在可以触发。
         *
         * 仅用于设置页显示，方便确认「防误触冷却」确实在拦触发。
         */
        val cooldownRemainMs: Long = 0L,

        val note: String = "未启动",
    )

    private val main = Handler(Looper.getMainLooper())

    /** Live config; the service and analyzer read this on every frame. */
    @Volatile
    var config: GazeConfig = GazeConfig()

    @Volatile
    var snapshot: Snapshot = Snapshot()
        private set

    private val listeners = CopyOnWriteArrayList<(Snapshot) -> Unit>()

    fun addListener(l: (Snapshot) -> Unit) {
        listeners.add(l)
        // Deliver the current value so a freshly attached view is never blank.
        main.post { l(snapshot) }
    }

    fun removeListener(l: (Snapshot) -> Unit) {
        listeners.remove(l)
    }

    /** Atomically derive the next snapshot, then notify every listener. */
    @Synchronized
    fun publish(transform: (Snapshot) -> Snapshot) {
        val next = transform(snapshot)
        snapshot = next
        main.post {
            // Copy so a listener mutating the list cannot break the loop.
            for (l in listeners.toList()) {
                runCatching { l(next) }
            }
        }
    }

    /** Convenience for the service's master on/off state. */
    fun setEnabled(enabled: Boolean) {
        publish { it.copy(enabled = enabled) }
    }
}
