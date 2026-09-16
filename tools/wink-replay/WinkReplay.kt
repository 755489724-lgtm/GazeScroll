package com.example.gazescroll

/**
 * v5.32 单闭判据的离线回归验证。
 *
 * 直接编译并运行**真实的** WinkDetector.kt（只把 android.util.Log 换成打印到 stdout 的桩），
 * 用 v5.30/v5.31 两轮实机日志里的序列回放：
 *  - v5.30 的「低头时一只眼被慢慢读低」必须**不触发**；
 *  - v5.31 被错杀的那批**真单闭**（起手读数落在 0.55~0.70、另一只眼 0.65、合眼 300~430ms）
 *    必须**触发**。
 *
 * 跑法见同目录 run.ps1。
 */
private class Feeder(holdMs: Long = WinkDetector.DEFAULT_HOLD_MS) {
    val events = ArrayList<WinkEvent>()
    val detector = WinkDetector { events.add(it) }
    private var now = 0L
    private val stepMs = 90L

    init {
        detector.holdMs = holdMs
        detector.closedBelow = 0.55f
        detector.nearTier = true
    }

    /** 把 (左眼, 右眼) 的读数保持 [ms] 毫秒，每 ≤90ms 喂一帧（实测帧间隔 63~116ms）。 */
    fun feed(left: Float, right: Float, ms: Long) {
        var remaining = ms
        while (true) {
            detector.onEyeProbabilities(left, right, now)
            if (remaining <= 0L) break
            val step = minOf(stepMs, remaining)
            now += step
            remaining -= step
            if (remaining == 0L) {
                detector.onEyeProbabilities(left, right, now)
                break
            }
        }
    }

    /** 逐帧喂一串左眼读数（每帧 90ms），右眼固定 [right]。 */
    fun frames(right: Float, vararg lefts: Float) {
        for (v in lefts) feed(v, right, 90)
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

// ------------------------------------------------------- 必须不触发（误触发形态）--

private fun testSlowSquintDrift() {
    println("\n[1] v5.30 误触发回放（俯视眯眼：一只眼在 2.7 秒里慢慢滑到 0.08）")
    val f = Feeder()
    f.feed(0.98f, 0.99f, 1500)
    for (i in 0 until 30) f.feed(0.98f - i * 0.031f, 0.99f, 90)
    f.feed(0.03f, 0.99f, 2000)
    check("缓慢下滑不触发", f.events.isEmpty(), "触发了 ${f.events.size} 次")
}

private fun testLingerAfterBothEyesClosed() {
    println("\n[2] v5.30 误触发回放（22:03:48：两只眼先闭 2.68 秒，睁开右眼后左眼还低 1 秒）")
    val f = Feeder()
    f.feed(0.95f, 0.95f, 800)
    f.feed(0.05f, 0.05f, 2680)
    f.feed(0.16f, 0.99f, 90)
    f.feed(0.20f, 0.99f, 300)
    f.feed(0.29f, 0.99f, 700)
    f.feed(0.30f, 0.99f, 500)
    check("长闭眼之后的眼皮残留不触发", f.events.isEmpty(), "触发了 ${f.events.size} 次")
}

private fun testSlowClosure() {
    println("\n[3] 合眼太慢（0.62→0.28 用了 630ms）→ 不触发")
    val f = Feeder()
    f.feed(0.95f, 0.99f, 800)
    f.frames(right = 0.99f, 0.62f, 0.57f, 0.52f, 0.48f, 0.44f, 0.40f, 0.36f, 0.32f, 0.28f)
    f.feed(0.05f, 0.99f, 1500)
    check("慢慢眯下去不触发", f.events.isEmpty(), "触发了 ${f.events.size} 次")
}

private fun testBlinkDoesNotTrigger() {
    println("\n[4] 正常眨眼（两只眼同时闭）不触发")
    val f = Feeder()
    f.feed(0.95f, 0.95f, 1000)
    repeat(3) {
        f.feed(0.03f, 0.02f, 200)
        f.feed(0.95f, 0.95f, 900)
    }
    check("三连眨不触发", f.events.isEmpty(), "触发了 ${f.events.size} 次")
}

// ------------------------------------------------------- 必须触发（真单闭形态）--

private fun testWinkFromBandReading() {
    println("\n[5] v5.31 被错杀的真单闭：起手读数只有 0.62（回差带里），一帧合到底")
    val f = Feeder()
    f.feed(0.62f, 0.99f, 900)
    f.feed(0.10f, 0.99f, 90)
    f.feed(0.03f, 0.99f, 500)
    check("起手读数在回差带里也触发", f.events.size == 1, "触发 ${f.events.size} 次")
}

private fun testWinkWithOtherEyeAt065() {
    println("\n[6] v5.31 被错杀的真单闭：另一只眼只读到 0.65（旧门槛是 >0.70）")
    val f = Feeder()
    f.feed(0.95f, 0.65f, 900)
    f.feed(0.12f, 0.65f, 90)
    f.feed(0.03f, 0.65f, 500)
    check("另一只眼 0.65（没闭着）也触发", f.events.size == 1, "触发 ${f.events.size} 次")
}

private fun testOnsetJustUnderLimit() {
    println("\n[7] 合眼 360ms（实测 22:18:01 那次是 432ms）→ 应当触发")
    val f = Feeder(holdMs = 600L)
    f.feed(0.95f, 0.99f, 900)
    f.frames(right = 0.99f, 0.62f, 0.54f, 0.50f, 0.45f, 0.28f)
    f.feed(0.03f, 0.99f, 500)
    check("onset 360ms 触发", f.events.size == 1, "触发 ${f.events.size} 次")
}

private fun testReplayOfLoggedSuccess() {
    println("\n[8] 回放 22:19:36 那次真实成功（held=616ms onset=278ms min=0.06 other=0.91）")
    val f = Feeder(holdMs = 600L)
    f.feed(0.91f, 0.95f, 1200)
    f.frames(right = 0.91f, 0.60f, 0.30f, 0.06f)
    f.feed(0.06f, 0.91f, 900)
    check("触发一次", f.events.size == 1, "触发 ${f.events.size} 次")
    check(
        "onset ≤500ms",
        (f.events.firstOrNull()?.onsetMs ?: 9999L) <= 500L,
        "onset=${f.events.firstOrNull()?.onsetMs}",
    )
    check(
        "保持 600~900ms",
        (f.events.firstOrNull()?.heldMs ?: 0L) in 600L..900L,
        "held=${f.events.firstOrNull()?.heldMs}",
    )
}

private fun testOneShotPerEpisode() {
    println("\n[9] 一次单闭只调一次；重新睁大才能再来一次")
    val f = Feeder(holdMs = 400L)
    f.feed(0.95f, 0.96f, 1000)
    f.feed(0.05f, 0.98f, 3000)
    check("一直闭着只触发一次", f.events.size == 1, "触发 ${f.events.size} 次")
    f.feed(0.95f, 0.95f, 700)
    f.feed(0.05f, 0.98f, 600)
    check("睁大后再闭可以再来一次", f.events.size == 2, "触发 ${f.events.size} 次")
}

private fun testHoldOptions() {
    println("\n[10] 保持时长档位：默认 0.4 秒闭 450ms 触发；1.0 秒档闭 450ms 不触发")
    val fast = Feeder()
    fast.feed(0.95f, 0.95f, 1000)
    fast.feed(0.05f, 0.98f, 450)
    check("0.4 秒档能触发", fast.events.size == 1, "触发 ${fast.events.size} 次")

    val slow = Feeder(holdMs = 1000L)
    slow.feed(0.95f, 0.95f, 1000)
    slow.feed(0.05f, 0.98f, 450)
    slow.feed(0.95f, 0.95f, 300)
    check("1.0 秒档闭 450ms 不触发", slow.events.isEmpty(), "触发了 ${slow.events.size} 次")
}

fun main() {
    println("=== WinkDetector v5.32 离线回放验证（真实代码 + 打印版 Log 桩）===")
    println("--- 这些必须不触发（v5.30 的误触发形态）---")
    testSlowSquintDrift()
    testLingerAfterBothEyesClosed()
    testSlowClosure()
    testBlinkDoesNotTrigger()
    println("--- 这些必须触发（v5.31 被错杀的真单闭形态）---")
    testWinkFromBandReading()
    testWinkWithOtherEyeAt065()
    testOnsetJustUnderLimit()
    testReplayOfLoggedSuccess()
    testOneShotPerEpisode()
    testHoldOptions()
    println("\n=== 结果：${if (failures == 0) "全部通过" else "$failures 项失败"} ===")
    if (failures != 0) throw IllegalStateException("$failures 项失败")
}
