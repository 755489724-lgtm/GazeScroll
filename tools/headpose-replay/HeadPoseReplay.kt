package com.example.gazescroll

/**
 * v5.44 离线回归：近距离档的「突然性窗口」由 300ms 放宽到 450ms（仰头失灵那次）。
 *
 * 编译的是**真实的** `HeadPoseDetector.kt`（只把 `android.util.Log` 换成打印桩）。
 * 回放的形状来自 2026-09-17 19:16 那次实测：
 *
 * ```
 * ignored slow lean: rise 429ms > 300ms          ← 一次真实仰头被整段作废
 * tiltUp candidate rejected: pitch=6.0° speed=0.0569°/ms ... reason=slow-rise
 * ```
 *
 * 全场这种"起手太慢"共 6 次：**336 / 429 / 429 / 563 / 684 / 839 ms**。
 * 所以本回放验证两件事：
 *   1. 336ms 与 429ms 的仰头**现在要能触发**（v5.44 想修的）；
 *   2. 563ms 以上的慢晃**仍然被挡**（窗口不能被放宽到没意义）。
 *
 * 跑法见同目录 run.ps1。
 */
private var failures = 0

private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) {
        println("  PASS  $name")
    } else {
        failures++
        println("  FAIL  $name  $detail")
    }
}

private class Feeder(private val faceRatioValue: Float) {
    val events = ArrayList<HeadEvent>()
    val detector = HeadPoseDetector { events.add(it) }
    var now = 0L

    init {
        detector.thresholdDeg = 6f
        detector.motionWindowMs = 500L
        detector.holdMs = 150L
        detector.turnEnabled = false
        // 关掉静止锁定：这次只想隔离"突然性窗口"这一条判据（静止锁定会再把阈值乘 1.5）。
        detector.staticLockEnabled = false
        detector.phoneMoving = false
        detector.eyeDip = false
        detector.cooldownMs = 0L
        detector.faceRatio = faceRatioValue
    }

    fun frame(pitch: Float, stepMs: Long = 90L) {
        detector.faceRatio = faceRatioValue
        detector.onHeadPose(pitch, 0f, now)
        now += stepMs
    }

    fun hold(pitch: Float, ms: Long, stepMs: Long = 90L) {
        var remaining = ms
        while (remaining > 0) {
            frame(pitch, stepMs)
            remaining -= stepMs
        }
    }

    /**
     * 建立"低头看手机"的静止基准：喂一段稳定的 [deg] 俯仰，让基准线（45 帧中位数）落在那里。
     * 近档的 `lookingDown` 要求基准线 ≥ 4°。
     */
    fun settle(deg: Float, ms: Long = 5000L) {
        hold(deg, ms)
    }

    /**
     * 做一次"仰头"：[riseMs] = 从 onset（0.4×阈值）涨到阈值所用的时间。
     *
     * **形状按实测那次抄**（19:16:23）：用户是先慢慢起手（onset 爬到阈值附近用了 429ms），
     * 然后**一帧快速越过阈值**（`speed=0.0569°/ms`，远超速度门 0.0240），再保持住。
     * 这一点很关键：如果回放成"全程匀速慢爬"，越阈值那一帧的速度只有 ~0.006°/ms，
     * 会被**后面的速度门**挡掉 —— 那验证的就不是"突然性窗口"这一条了。
     */
    fun lookUp(riseMs: Long, baseDeg: Float, threshold: Float, stepMs: Long = 90L) {
        val onset = 0.4f * threshold + 0.1f
        val justBelow = threshold - 0.4f
        frame(baseDeg + onset, stepMs)                       // onset 帧
        val slowMs = (riseMs - stepMs).coerceAtLeast(stepMs)
        val steps = (slowMs / stepMs).toInt().coerceAtLeast(1)
        for (i in 1..steps) {                                 // 慢慢爬到阈值下沿（起手时长的主要部分）
            val t = i.toFloat() / steps
            frame(baseDeg + onset + (justBelow - onset) * t, stepMs)
        }
        frame(baseDeg + threshold + 3f, stepMs)               // 一帧快速越过阈值（强候选）
        hold(baseDeg + threshold + 3f, 400L, stepMs)          // 保持住
    }

    /** 点头（向下），形状同上：慢起手 + 一帧快越阈值 + 保持。 */
    fun nodDown(riseMs: Long, baseDeg: Float, threshold: Float, stepMs: Long = 90L) {
        val onset = 0.4f * threshold + 0.1f
        val justBelow = threshold - 0.3f
        frame(baseDeg - onset, stepMs)
        val slowMs = (riseMs - stepMs).coerceAtLeast(stepMs)
        val steps = (slowMs / stepMs).toInt().coerceAtLeast(1)
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            frame(baseDeg - (onset + (justBelow - onset) * t), stepMs)
        }
        frame(baseDeg - threshold - 3f, stepMs)
        hold(baseDeg - threshold - 3f, 400L, stepMs)
    }

    /** 全程匀速的慢晃（没有"快越阈值"那一帧）—— 用来验证慢动作仍被挡住。 */
    fun slowRamp(fromSigned: Float, toSigned: Float, ms: Long, baseDeg: Float, stepMs: Long = 90L) {
        frame(baseDeg + fromSigned, stepMs)
        val steps = (ms / stepMs).toInt().coerceAtLeast(1)
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            frame(baseDeg + fromSigned + (toSigned - fromSigned) * t, stepMs)
        }
        hold(baseDeg + toSigned, 400L, stepMs)
    }

    fun tiltUps(): Int = events.count { it is HeadEvent.TiltUp }
    fun nods(): Int = events.count { it is HeadEvent.NodDown }
    fun turns(): Int = events.count { it is HeadEvent.TurnLeft || it is HeadEvent.TurnRight }

    /** v5.60：只动偏航轴（俯仰固定 0），用来隔离「扭头窗口」。 */
    fun frameYaw(yaw: Float, stepMs: Long = 90L) {
        detector.faceRatio = faceRatioValue
        detector.onHeadPose(0f, yaw, now)
        now += stepMs
    }

    /** v5.60：扭头（向右），形状同仰头：慢起手 + 一帧快越阈值 + 保持。 */
    fun turnRight(riseMs: Long, threshold: Float, stepMs: Long = 90L) {
        val onset = 0.4f * threshold + 0.1f
        val justBelow = threshold - 0.5f
        frameYaw(onset, stepMs)
        val slowMs = (riseMs - stepMs).coerceAtLeast(stepMs)
        val steps = (slowMs / stepMs).toInt().coerceAtLeast(1)
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            frameYaw(onset + (justBelow - onset) * t, stepMs)
        }
        frameYaw(threshold + 3f, stepMs)
        holdYaw(threshold + 3f, 400L, stepMs)
    }

    fun holdYaw(yaw: Float, ms: Long, stepMs: Long = 90L) {
        var remaining = ms
        while (remaining > 0) {
            frameYaw(yaw, stepMs)
            remaining -= stepMs
        }
    }
}

private const val NEAR_RATIO = 0.55f
private const val FAR_RATIO = 0.35f

private fun testNearLookUpRises() {
    println("\n[1] 近距离仰头（实测 6 次起手时长逐条回放）")
    // 近档 + 低头：仰头阈值 = 6.0° × 0.63 = 3.8°
    for ((rise, expect) in listOf(336L to true, 429L to true, 429L to true)) {
        val f = Feeder(NEAR_RATIO)
        f.settle(8f)
        f.lookUp(rise, baseDeg = 8f, threshold = 3.8f)
        check(
            "起手 ${rise}ms → ${if (expect) "应触发" else "应被挡"}",
            f.tiltUps() == if (expect) 1 else 0,
            "实际触发 ${f.tiltUps()} 次",
        )
    }
    for (rise in listOf(563L, 684L, 839L)) {
        val f = Feeder(NEAR_RATIO)
        f.settle(8f)
        f.lookUp(rise, baseDeg = 8f, threshold = 3.8f)
        check(
            "起手 ${rise}ms → 仍然应被挡（慢晃）",
            f.tiltUps() == 0,
            "实际触发 ${f.tiltUps()} 次",
        )
    }
}

private fun testFarDistanceUnchanged() {
    println("\n[2] 远距离不受影响（窗口仍是用户设的 500ms，阈值 6.0°）")
    val fast = Feeder(FAR_RATIO)
    fast.settle(2f)
    fast.lookUp(400L, baseDeg = 2f, threshold = 6f)
    check("远档起手 400ms → 触发", fast.tiltUps() == 1, "实际 ${fast.tiltUps()} 次")

    val slow = Feeder(FAR_RATIO)
    slow.settle(2f)
    slow.lookUp(700L, baseDeg = 2f, threshold = 6f)
    check("远档起手 700ms → 被挡（>500ms）", slow.tiltUps() == 0, "实际 ${slow.tiltUps()} 次")
}

private fun testNearNodUnchanged() {
    println("\n[3] 近档点头（这次没改的那条通道）行为不变：快速点头触发、慢晃不触发")
    val fast = Feeder(NEAR_RATIO)
    fast.settle(8f)
    fast.nodDown(200L, baseDeg = 8f, threshold = 2.5f)   // 近距离俯视点头阈值仍是 2.5°
    check("近档点头 200ms → 触发（A 方案没做，阈值仍是 2.5°）", fast.nods() == 1, "实际 ${fast.nods()} 次")

    val slowNod = Feeder(NEAR_RATIO)
    slowNod.settle(8f)
    // 边缘（刚过 2.5° 阈值、够不上"强候选"）+ 全程匀速慢爬 → 速度门挡住。
    // 注意：强候选（≥阈值+2°）按 v5.27 的设计**故意**免速度门，所以这里必须用边缘幅度。
    slowNod.slowRamp(fromSigned = 1.0f, toSigned = -3.0f, ms = 700L, baseDeg = 8f)
    check("近档边缘 + 匀速慢晃 700ms → 不触发", slowNod.nods() == 0, "实际 ${slowNod.nods()} 次")
}

fun main() {
    println("=== HeadPoseDetector v5.44 离线回放（真实代码 + 打印版 Log 桩）===")
    testNearLookUpRises()
    testFarDistanceUnchanged()
    testNearNodUnchanged()
    testSplitPitchThreshold()
    testMotionWindowAffectsTurn()
    println("\n=== 结果：${if (failures == 0) "全部通过" else "$failures 项失败"} ===")
    if (failures != 0) throw IllegalStateException("$failures 项失败")
}

/**
 * v5.60 新增：**仰头阈值与点头阈值拆开**之后的行为。
 *
 * 近档 + 低头时仰头的实际阈值 = 仰头设定值 × 0.63（[HeadPoseDetector] 的 NEAR_LOOKUP_BOOST），
 * 点头 = 点头设定值 × 0.42。所以：
 *   · 仰头跟着点头走（只设 thresholdDeg，thresholdUpDeg 保持 0）→ 4° 的仰头就触发；
 *   · 仰头单独设成 12°（阈值 7.56°）→ 同样的 4° 仰头被挡，而 9° 的仰头仍能触发；
 *   · 点头那条通道**完全不受仰头设定影响**（仍是 2.5° 档）。
 */
private fun testSplitPitchThreshold() {
    println("\n[4] v5.60 仰头独立阈值（点头 6° 不变，只把仰头调大）")

    val follow = Feeder(NEAR_RATIO)
    follow.settle(8f)
    follow.lookUp(300L, baseDeg = 8f, threshold = 4.0f)
    check(
        "仰头跟随点头(6° → 实际 3.8°)：4° 仰头 → 触发",
        follow.tiltUps() == 1,
        "实际 ${follow.tiltUps()} 次",
    )

    val split = Feeder(NEAR_RATIO)
    split.detector.thresholdUpDeg = 12f
    split.settle(8f)
    split.lookUp(300L, baseDeg = 8f, threshold = 4.0f)
    check(
        "仰头独立设成 12°（实际 7.6°）：同样的 4° 仰头 → 被挡",
        split.tiltUps() == 0,
        "实际 ${split.tiltUps()} 次",
    )

    val big = Feeder(NEAR_RATIO)
    big.detector.thresholdUpDeg = 12f
    big.settle(8f)
    big.lookUp(300L, baseDeg = 8f, threshold = 9.0f)
    check(
        "仰头 12° 时 9° 仰头 → 仍能触发（不是把通道关掉）",
        big.tiltUps() == 1,
        "实际 ${big.tiltUps()} 次",
    )

    val nod = Feeder(NEAR_RATIO)
    nod.detector.thresholdUpDeg = 12f
    nod.settle(8f)
    nod.nodDown(200L, baseDeg = 8f, threshold = 2.5f)
    check(
        "仰头改成 12° 后，点头仍是 2.5° 档 → 触发",
        nod.nods() == 1,
        "实际 ${nod.nods()} 次",
    )
}

/**
 * v5.60 新增：**「触发速度」滑块同时管两条轴**，但默认档必须与 v5.51 一字不差。
 *
 * v5.51 的实际值是：俯仰远档 500ms（用户可设）、扭头远档 900ms（写死）；
 * 所以滑块设成 v 时扭头 = v × 1.8 —— 默认 500 → 900，与旧版完全一致；
 * 收到 150 时扭头跟着变成 270ms，慢扭头就该被挡。
 */
private fun testMotionWindowAffectsTurn() {
    println("\n[5] v5.60 触发速度对扭头同样生效（默认档与 v5.51 一致）")

    check("比例核对：500ms → 900ms（与 v5.51 的写死值一致）", HeadPoseDetector.turnWindowMsFor(500L) == 900L)
    check("比例核对：150ms → 270ms", HeadPoseDetector.turnWindowMsFor(150L) == 270L)
    check("比例核对：1500ms → 2700ms", HeadPoseDetector.turnWindowMsFor(1500L) == 2700L)

    val def = Feeder(FAR_RATIO)
    def.detector.turnEnabled = true
    def.detector.turnThresholdDeg = 28f
    def.detector.turnMotionWindowMs = HeadPoseDetector.turnWindowMsFor(def.detector.motionWindowMs)
    def.settle(2f)
    def.turnRight(500L, threshold = 28f)
    check(
        "默认 500/900ms：远档 500ms 起手的扭头 → 触发",
        def.turns() == 1,
        "实际 ${def.turns()} 次",
    )

    val tight = Feeder(FAR_RATIO)
    tight.detector.turnEnabled = true
    tight.detector.turnThresholdDeg = 28f
    tight.detector.motionWindowMs = 150L
    tight.detector.turnMotionWindowMs = HeadPoseDetector.turnWindowMsFor(150L)
    tight.settle(2f)
    tight.turnRight(500L, threshold = 28f)
    check(
        "收紧到 150/270ms：同样的扭头 → 被挡",
        tight.turns() == 0,
        "实际 ${tight.turns()} 次",
    )
}
