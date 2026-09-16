package com.example.gazescroll

/**
 * v5.35 歪头（roll）判据的离线回归验证。
 *
 * 直接编译并运行**真实的** TiltDetector.kt（只把 android.util.Log 换成打印到 stdout 的桩），
 * 覆盖：触发 / 保持不够不触发 / 角度不够不触发 / 抖一下不触发 / 一次歪头只调一次 /
 * 回到中位才能再来 / 本人头姿偏移由基准线自动吸收 / 读数太歪时不动。
 *
 * 跑法见同目录 run.ps1。
 */
private class Feeder(threshold: Float = 18f, holdMs: Long = 500L) {
    val events = ArrayList<TiltEvent>()
    val detector = TiltDetector { events.add(it) }
    private var now = 0L

    init {
        detector.thresholdDeg = threshold
        detector.holdMs = holdMs
        detector.nearTier = true
    }

    /** 把滚转角保持 [ms] 毫秒，每 ≤90ms 喂一帧（实测帧间隔 63~116ms）。 */
    fun feed(roll: Float?, ms: Long, step: Long = 90L) {
        var remaining = ms
        while (true) {
            detector.onRoll(roll, now)
            if (remaining <= 0L) break
            val s = minOf(step, remaining)
            now += s
            remaining -= s
            if (remaining == 0L) {
                detector.onRoll(roll, now)
                break
            }
        }
    }

    /** 逐帧喂一串滚转角。 */
    fun frames(vararg rolls: Float, step: Long = 90L) {
        for (r in rolls) feed(r, step, step)
    }
}

private var failures = 0

private fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) {
        println("  PASS  $name")
    } else {
        failures++
        println("  FAIL  $name  $detail")
    }
}

// ------------------------------------------------------------- 必须触发 ----

private fun testBasicTilt() {
    println("\n[1] 基本触发：头正着 → 歪到 +20° 保持 0.6 秒（阈值 18°/保持 0.5 秒）")
    val f = Feeder()
    f.feed(0f, 1200)
    f.feed(20f, 600)
    check("触发一次", f.events.size == 1, "触发 ${f.events.size} 次")
    check("方向是 +（右）", f.events.firstOrNull()?.side == TiltSide.RIGHT, "实际 ${f.events.firstOrNull()?.side}")
    check("峰值 ≥ 20°", (f.events.firstOrNull()?.peakDeg ?: 0f) >= 20f, "峰值 ${f.events.firstOrNull()?.peakDeg}")
}

private fun testNegativeTilt() {
    println("\n[2] 反方向：歪到 -20° → 左歪头")
    val f = Feeder()
    f.feed(0f, 1200)
    f.feed(-20f, 600)
    check("触发一次", f.events.size == 1, "触发 ${f.events.size} 次")
    check("方向是 −（左）", f.events.firstOrNull()?.side == TiltSide.LEFT, "实际 ${f.events.firstOrNull()?.side}")
}

private fun testHoldOption1000() {
    println("\n[3] 保持 1.0 秒档：歪 20° 只坚持 0.6 秒不触发，坚持 1.1 秒才触发")
    val short = Feeder(holdMs = 1000L)
    short.feed(0f, 1200)
    short.feed(20f, 600)
    short.feed(0f, 500)
    check("0.6 秒不够 → 不触发", short.events.isEmpty(), "触发了 ${short.events.size} 次")

    val long = Feeder(holdMs = 1000L)
    long.feed(0f, 1200)
    long.feed(20f, 1100)
    check("1.1 秒 → 触发", long.events.size == 1, "触发 ${long.events.size} 次")
}

private fun testOffsetBaseline() {
    println("\n[4] 本人平时就歪着头（基准线 −12°）：歪到 −32°（相对 20°）应触发")
    val f = Feeder()
    f.feed(-12f, 3000)      // 自然头姿
    f.feed(-32f, 600)       // 相对基准线歪 20°
    check("触发一次", f.events.size == 1, "触发 ${f.events.size} 次")
    check(
        "基准线学到了 −12° 附近",
        kotlin.math.abs((f.detector.baselineDeg ?: 0f) + 12f) <= 2f,
        "baseline=${f.detector.baselineDeg}",
    )
}

private fun testRearmNeedsNeutral() {
    println("\n[5] 需要回正才能再来一次：歪着不动只触发一次，回正后再歪 → 第二次")
    val f = Feeder()
    f.feed(0f, 1200)
    f.feed(20f, 3000)       // 一直歪着
    check("一直歪着只触发一次", f.events.size == 1, "触发 ${f.events.size} 次")
    f.feed(0f, 800)         // 回正
    f.feed(20f, 600)
    check("回正后再歪 → 第二次", f.events.size == 2, "触发 ${f.events.size} 次")
}

// ------------------------------------------------------------- 必须不触发 ----

private fun testTooShort() {
    println("\n[6] 抖一下头：歪 20° 只 200ms 就回正 → 不触发")
    val f = Feeder()
    f.feed(0f, 1200)
    f.feed(20f, 200)
    f.feed(0f, 1500)
    check("不触发", f.events.isEmpty(), "触发了 ${f.events.size} 次")
}

private fun testBelowThreshold() {
    println("\n[7] 角度不够：歪 10°（< 18°）保持 3 秒 → 不触发")
    val f = Feeder()
    f.feed(0f, 1200)
    f.feed(10f, 3000)
    check("不触发", f.events.isEmpty(), "触发了 ${f.events.size} 次")
}

private fun testSingleFrameSpike() {
    println("\n[8] 单帧尖峰：一帧 30° 再立刻回正 → 不触发")
    val f = Feeder()
    f.feed(0f, 1200)
    f.frames(30f, 0f, 0f, 0f, 0f)
    f.feed(0f, 1500)
    check("不触发", f.events.isEmpty(), "触发了 ${f.events.size} 次")
}

private fun testNotEnoughSamples() {
    println("\n[9] 刚有脸（基准线还没攒够）时歪头 → 不触发")
    val f = Feeder()
    f.frames(0f, 0f, 0f, 20f, 20f, 20f, 20f, 20f)   // 只有 3 帧基准就歪
    check("不触发", f.events.isEmpty(), "触发了 ${f.events.size} 次")
}

private fun testInsaneTilt() {
    println("\n[10] 读数太歪（70°，例如整个人躺下）→ 不触发且重置")
    val f = Feeder()
    f.feed(0f, 1200)
    f.feed(70f, 2000)
    check("不触发", f.events.isEmpty(), "触发了 ${f.events.size} 次")
}

private fun testFaceLostResets() {
    println("\n[11] 歪到一半丢脸（null）→ 不触发")
    val f = Feeder()
    f.feed(0f, 1200)
    f.feed(20f, 300)
    f.feed(null, 400)       // 丢脸
    f.feed(20f, 300)
    check("不触发", f.events.isEmpty(), "触发了 ${f.events.size} 次")
}

fun main() {
    println("=== TiltDetector v5.35 离线回放验证（真实代码 + 打印版 Log 桩）===")
    println("--- 必须触发 ---")
    testBasicTilt()
    testNegativeTilt()
    testHoldOption1000()
    testOffsetBaseline()
    testRearmNeedsNeutral()
    println("--- 必须不触发 ---")
    testTooShort()
    testBelowThreshold()
    testSingleFrameSpike()
    testNotEnoughSamples()
    testInsaneTilt()
    testFaceLostResets()
    println("\n=== 结果：${if (failures == 0) "全部通过" else "$failures 项失败"} ===")
    if (failures != 0) throw IllegalStateException("$failures 项失败")
}
