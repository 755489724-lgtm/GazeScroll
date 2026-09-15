package com.example.gazescroll

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.sqrt

/**
 * 手机自身运动监测量（v5.13）。
 *
 * ## 为什么需要它（「急停 / 急刹车不要误触」）
 *
 * 用户的原始需求：
 *
 * > 「允许轻轻的摇晃不触发，但是如果遇到那种急停 —— 比如我走在街上看着手机突然停下来，
 * > 或者地铁上、公交车上急刹车时，我希望不要误触。」
 *
 * 靠摄像头**区分不了**这两种情况：急刹车时身体前倾，头部俯仰角会变化 5~15°，
 * 而且那一下**是单向的**（不是来回抖），所以 [HeadPoseDetector.isShaking] 的路径效率判据
 * 也挡不住它 —— 实测 v5.12 那次晃动误触就是 `tiltUp 8.1°`，效率判据没触发。
 *
 * 但物理上两者有干净的分界：
 *
 *  - **点头**是头绕脖子转 → **手机本身几乎不动**（手持时只有很小的抖动）；
 *  - **急停/急刹**是整个人和手机一起顿 → **手机有明确的大幅直线加速度**。
 *
 * 所以这里读**加速度计**（优先 `TYPE_LINEAR_ACCELERATION`，它已经去掉重力；没有的话
 * 退回 `TYPE_ACCELEROMETER` 并自己用低通估计重力再减掉）。
 *
 * ## 阈值是怎么定的
 *
 *  - 坐着/躺着刷手机：线性加速度基本在 **0~0.5 m/s²**、角速度 **0.05~0.2 rad/s**；
 *  - 走路时手持：加速度峰值约 **1.5~3 m/s²**、角速度约 **0.3~1.0 rad/s**；
 *  - 急刹车 / 突然停下 / 人手左右摇晃：加速度 **2.5~15 m/s²**、角速度 **0.6~10 rad/s**。
 *
 * 所以两条门限是 [LURCH_THRESHOLD] = 2.5 m/s² 与 [ROTATION_THRESHOLD] = 0.6 rad/s，
 * 任一条越线就判定"手机在动"。而且它**失败开放**：传感器不可用时 [isMoving] 恒为 false，
 * 绝不因为读不到传感器就把用户的手势一起废掉。
 *
 * 两个峰值都会打到诊断行（`accel=` / `gyro=`），所以"地铁上、走路时、晃手机时到底读到多少"
 * 可以直接看日志 —— 下一版要调门限有实测数字可依。
 */
class PhoneMotionMonitor(context: Context) : SensorEventListener {

    companion object {
        private const val TAG = "PhoneMotion"

        /** 判定「手机被顿了一下」的线性加速度门限（m/s²）。 */
        private const val LURCH_THRESHOLD = 2.5f

        /**
         * 判定「手机被摇摆」的角速度门限（rad/s，v5.14）。
         *
         * ## 为什么加了陀螺仪
         *
         * v5.13 只用直线加速度，实机复测发现**抓不到用户的"轻晃"**：整场会话里
         * 线性加速度峰值最高只到 2.3 m/s²、`lurch detected` 一行都没有，
         * 而用户明确说「轻轻晃动的误触多得多」。
         *
         * 原因是**手晃手机主要是"转"而不是"平移"** —— 绕手腕转动时，
         * 传感器的直线加速度可以很小，但角速度很大。而这恰恰是我们要区分的东西：
         *
         * ```
         * 点头：头绕脖子转，手机基本不动        → 角速度 ≈ 0
         * 手晃/地铁摇摆/急刹：手机自己在转        → 角速度 0.5~10 rad/s
         * ```
         *
         * 取 0.6 rad/s（≈35°/秒）：手持不动时约 0.05~0.2，走路摆动约 0.3~1.0，
         * 有意摇晃 2~10。所以它能抓住"低频摇摆"这种加速度计看不见的情况。
         * 数值会打进诊断行（`gyro=`）与拒绝日志，下一版要调有实测数字可依。
         */
        private const val ROTATION_THRESHOLD = 0.6f

        /** 判定成立后保持「手机在动」的时长（毫秒）。 */
        private const val LURCH_HOLD_MS = 500L

        /** 峰值统计窗口：诊断行里的 `accel=` / `gyro=` 取这段时间内的最大值。 */
        private const val PEAK_WINDOW_MS = 1000L

        /** 没有线性加速度传感器时，重力低通系数（越小越稳）。 */
        private const val GRAVITY_ALPHA = 0.85f
    }

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private var accelSensor: Sensor? = null
    private var gyroSensor: Sensor? = null

    /** 是否真的拿到了加速度计；false 时所有判定都失败开放。 */
    @Volatile
    var available: Boolean = false
        private set

    /** 是否拿到了陀螺仪（v5.14）。 */
    @Volatile
    var gyroAvailable: Boolean = false
        private set

    /** 最近一帧的线性加速度幅值（m/s²）。 */
    @Volatile
    var magnitude: Float = 0f
        private set

    /** 最近一帧的角速度幅值（rad/s，v5.14）。 */
    @Volatile
    var rotation: Float = 0f
        private set

    /** 是否因为需要自己减重力而退回普通加速度计。 */
    private var subtractGravity = false
    private val gravity = FloatArray(3)

    private var peakValue = 0f
    private var peakAtMs = 0L
    private var gyroPeakValue = 0f
    private var gyroPeakAtMs = 0L
    private var lurchUntilMs = 0L

    fun start() {
        val manager = sensorManager ?: return
        val linear = manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        accelSensor = linear ?: manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        subtractGravity = linear == null
        gyroSensor = manager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        var ok = false
        accelSensor?.let { ok = manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) || ok }
        gyroSensor?.let {
            gyroAvailable = manager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        available = ok
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        available = false
        gyroAvailable = false
    }

    /** 手机此刻是否「正在被顿挫或摇摆」（判定用的就是这个）。 */
    fun isMoving(): Boolean = SystemClock.elapsedRealtime() < lurchUntilMs

    /** 最近 [PEAK_WINDOW_MS] 内的加速度峰值（诊断与门限调参用）。 */
    fun recentPeak(nowMs: Long): Float {
        if (nowMs - peakAtMs > PEAK_WINDOW_MS) return 0f
        return peakValue
    }

    /** 最近 [PEAK_WINDOW_MS] 内的角速度峰值（诊断与门限调参用）。 */
    fun recentRotationPeak(nowMs: Long): Float {
        if (nowMs - gyroPeakAtMs > PEAK_WINDOW_MS) return 0f
        return gyroPeakValue
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = SystemClock.elapsedRealtime()
        val a = event.values
        when (event.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> {
                val value = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
                rotation = value
                if (value > gyroPeakValue || now - gyroPeakAtMs > PEAK_WINDOW_MS) {
                    gyroPeakValue = value
                    gyroPeakAtMs = now
                }
                if (value >= ROTATION_THRESHOLD) markMoving(now, "rotation", value)
            }

            Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_ACCELEROMETER -> {
                val x: Float
                val y: Float
                val z: Float
                if (subtractGravity) {
                    // 低通估计重力，再减掉 —— TYPE_LINEAR_ACCELERATION 缺失时的退路。
                    gravity[0] = GRAVITY_ALPHA * gravity[0] + (1 - GRAVITY_ALPHA) * a[0]
                    gravity[1] = GRAVITY_ALPHA * gravity[1] + (1 - GRAVITY_ALPHA) * a[1]
                    gravity[2] = GRAVITY_ALPHA * gravity[2] + (1 - GRAVITY_ALPHA) * a[2]
                    x = a[0] - gravity[0]
                    y = a[1] - gravity[1]
                    z = a[2] - gravity[2]
                } else {
                    x = a[0]
                    y = a[1]
                    z = a[2]
                }
                val value = sqrt(x * x + y * y + z * z)
                magnitude = value
                if (value > peakValue || now - peakAtMs > PEAK_WINDOW_MS) {
                    peakValue = value
                    peakAtMs = now
                }
                if (value >= LURCH_THRESHOLD) markMoving(now, "accel", value)
            }
        }
    }

    /** 记一次「手机在动」，并把刚触发的那一条打进日志（便于核对门限是否合适）。 */
    private fun markMoving(now: Long, cause: String, value: Float) {
        if (now >= lurchUntilMs) {
            android.util.Log.i(
                TAG,
                "phone motion detected: $cause=${"%.2f".format(value)} " +
                    "-> head triggers suppressed for ${LURCH_HOLD_MS}ms " +
                    "(accel>=${LURCH_THRESHOLD}m/s² or gyro>=${ROTATION_THRESHOLD}rad/s)",
            )
        }
        lurchUntilMs = now + LURCH_HOLD_MS
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
