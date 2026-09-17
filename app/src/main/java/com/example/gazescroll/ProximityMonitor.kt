package com.example.gazescroll

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * v5.40：**近距离传感器 + 环境光**，给"前置摄像头被手盖住"提供一个与画面无关的硬信号。
 *
 * 为什么需要它：v5.39 的判据是「没有人脸 且 画面平均亮度 < 32」，
 * 实机第一次采集就整场没认出来 —— 18:33:32 打开采集后 4 分钟里始终 `probe=idle`，
 * 而日志里 18:37:01 之后人脸连续消失 50 秒都没进入"已就绪"。原因很直白：
 * **前置摄像头的自动曝光/增益会把被手掌盖住的画面提亮**，所以"盖住 = 画面黑"这条不成立。
 *
 * 近距离传感器是另一条完全独立的物理通道：手掌贴到手机顶部时它直接翻成"近"，
 * 与曝光、光照、画面内容都无关。这台机器（小米 13 / fuxi）实测有
 * `android.sensor.proximity`（XiaoMi V1.6）+ 环境光 ALS。
 *
 * 它只在「注视数据采集」打开时注册（[setEnabled]），关掉即注销，
 * 所以不开测试功能时逐帧/整机开销都是零。传感器缺失时 [available] 为 false，
 * 上层自动退回"只看画面"的老判据。
 */
class ProximityMonitor(context: Context) : SensorEventListener {

    private val manager: SensorManager? =
        context.applicationContext.getSystemService(SensorManager::class.java)

    private val proximitySensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    private val lightSensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_LIGHT)

    /** 传感器存在且注册成功。 */
    @Volatile
    var available: Boolean = false
        private set

    /** 当前是否"被挡住"（读数小于量程 = 有东西贴着）。 */
    @Volatile
    var near: Boolean = false
        private set

    /** 近距离传感器的量程（通常 5.0 cm）；0 表示厂家没给。 */
    @Volatile
    var maxRange: Float = 0f
        private set

    /** 最近一次原始读数，仅用于日志核对。 */
    @Volatile
    var lastRaw: Float = -1f
        private set

    /** 环境光是否可用（诊断用，不参与判据）。 */
    @Volatile
    var lightAvailable: Boolean = false
        private set

    /** 最近一次环境光读数（lux）；-1 = 没有。 */
    @Volatile
    var lux: Float = -1f
        private set

    private var registered = false

    /** 幂等：start() 可以每帧调用。 */
    fun start() {
        if (registered) return
        val m = manager ?: return
        registered = true
        proximitySensor?.let { sensor ->
            maxRange = sensor.maximumRange
            available = m.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
        lightSensor?.let { sensor ->
            lightAvailable = m.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { manager?.unregisterListener(this) }
        available = false
        lightAvailable = false
        near = false
    }

    /** 采集开关的跟随：打开就注册、关掉就注销。 */
    fun setEnabled(enabled: Boolean) {
        if (enabled) start() else stop()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_PROXIMITY -> {
                val value = event.values.getOrNull(0) ?: return
                lastRaw = value
                // 量程未知时退化成"读数是否为 0"，免得永远判不出"近"。
                near = if (maxRange > 0f) value < maxRange else value == 0f
            }

            Sensor.TYPE_LIGHT -> lux = event.values.getOrNull(0) ?: -1f
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** 诊断行用的一小段。 */
    fun stateLine(): String =
        if (!available) {
            "prox=-"
        } else {
            "prox=${if (near) "NEAR" else "far"}(${lastRaw})"
        }
}
