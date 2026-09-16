package com.example.gazescroll

/**
 * v5.34 单闭判据的离线回归验证。
 *
 * 直接编译并运行**真实的** WinkDetector.kt（只把 android.util.Log 换成打印到 stdout 的桩），
 * 用 v5.30 ~ v5.33 四轮实机日志里的序列回放：
 *  - 误触发形态（低头反复读低 / 眨眼 / 两只眼一起半闭 / 一次闭太久）→ 必须不触发；
 *  - 真单闭形态（含 v5.33 因为"概率下降慢"被错杀的那批）→ 必须触发。
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

    /** 逐帧喂左眼读数（每帧 90ms），右眼固定。 */
    fun framesL(right: Float, vararg lefts: Float) {
        for (v in lefts) feed(v, right, 90)
    }

    /** 逐帧喂右眼读数（每帧 90ms），左眼固定。 */
    fun framesR(left: Float, vararg rights: Float) {
        for (v in rights) feed(left, v, 90)
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

private fun testSustainedDrift() {
    println("\n[1] v5.30 误触发回放（低头眯眼）：一只眼反复被读低 8 次，每次 1.2 秒")
    val f = Feeder()
    f.feed(0.97f, 0.97f, 1200)
    repeat(8) {
        f.feed(0.03f, 0.97f, 1200)   // 右眼被读低（另一只眼一直睁着）
        f.feed(0.97f, 0.97f, 500)
    }
    check(
        "反复读低最多只触发 2 次（v5.30 是 8 次）",
        f.events.size <= 2,
        "触发了 ${f.events.size} 次",
    )
}

private fun testLingerAfterBothEyesClosed() {
    println("\n[2] v5.30 误触发回放（22:03:48：两只眼先闭 2.68 秒，睁开右眼后左眼还低 1 秒）")
    val f = Feeder()
    f.feed(0.95f, 0.95f, 800)
    f.feed(0.05f, 0.05f, 2680)      // 长闭眼 → duty 被抬起来
    f.feed(0.16f, 0.99f, 90)
    f.feed(0.20f, 0.99f, 300)
    f.feed(0.29f, 0.99f, 700)
    f.feed(0.30f, 0.99f, 500)
    check("长闭眼之后的眼皮残留不触发", f.events.isEmpty(), "触发了 ${f.events.size} 次")
}

private fun testBlinkFlutter() {
    println("\n[3] v5.32 误触发回放（22:25:56：用户眨眼/半闭，两只眼一起落在 0.5~0.65）")
    val f = Feeder()
    f.feed(0.90f, 0.90f, 900)
    f.framesL(right = 0.65f, 0.52f, 0.15f, 0.28f, 0.45f, 0.50f, 0.50f, 0.50f)
    f.feed(0.90f, 0.90f, 600)
    check("两只眼读数差不多时不触发", f.events.isEmpty(), "触发了 ${f.events.size} 次")
}

private fun testBothEyesSemiClosed() {
    println("\n[4] 两只眼一起半闭（眨眼后眼皮没完全抬起来）→ 不触发")
    val f = Feeder()
    f.feed(0.95f, 0.95f, 800)
    f.feed(0.45f, 0.60f, 900)
    f.feed(0.95f, 0.95f, 500)
    check("两只眼一起半闭不触发", f.events.isEmpty(), "触发了 ${f.events.size} 次")
}

private fun testBlinkDoesNotTrigger() {
    println("\n[5] 正常眨眼（两只眼同时闭）不触发")
    val f = Feeder()
    f.feed(0.95f, 0.95f, 1000)
    repeat(3) {
        f.feed(0.03f, 0.02f, 200)
        f.feed(0.95f, 0.95f, 900)
    }
    check("三连眨不触发", f.events.isEmpty(), "触发了 ${f.events.size} 次")
}

private fun testTooLongClosure() {
    println("\n[6] 闭着不松 4 秒：只触发一次（迟到的\"闭太久\"判据由 [2] 的残留场景覆盖）")
    val f = Feeder()
    f.feed(0.95f, 0.95f, 1500)
    f.feed(0.04f, 0.97f, 4000)      // 单眼闭着 4 秒不动
    check("闭 4 秒只触发 1 次", f.events.size == 1, "触发了 ${f.events.size} 次")
}

// ------------------------------------------------------- 必须触发（真单闭形态）--

private fun testSlowDecayRealWink() {
    println("\n[7] v5.33 被错杀的真单闭：概率慢慢下降（1.2 秒才掉到 0.05）")
    val f = Feeder()
    f.feed(0.95f, 0.95f, 1500)
    // 概率缓降但两眼的差距一直很大（0.95 → 0.05，另一只眼 0.95）
    f.framesR(left = 0.95f, 0.90f, 0.78f, 0.66f, 0.55f, 0.45f, 0.36f, 0.25f, 0.14f, 0.06f, 0.05f)
    check("缓降的真单闭也触发", f.events.size == 1, "触发 ${f.events.size} 次")
}

private fun testLoggedSuccesses() {
    println("\n[8] 回放 v5.33 日志里那 6 次真实成功")
    val cases: List<Pair<Float, FloatArray>> = listOf(
        0.95f to floatArrayOf(0.60f, 0.30f, 0.07f, 0.02f, 0.07f, 0.30f, 0.40f),
        0.99f to floatArrayOf(0.60f, 0.35f, 0.15f, 0.06f, 0.15f, 0.35f, 0.45f),
        0.99f to floatArrayOf(0.55f, 0.30f, 0.04f, 0.04f, 0.20f, 0.49f, 0.49f),
        0.99f to floatArrayOf(0.55f, 0.30f, 0.09f, 0.09f, 0.28f, 0.28f, 0.28f),
        0.99f to floatArrayOf(0.60f, 0.35f, 0.06f, 0.06f, 0.16f, 0.40f, 0.45f),
        1.00f to floatArrayOf(0.60f, 0.35f, 0.14f, 0.14f, 0.24f, 0.40f, 0.45f),
    )
    var idx = 0
    for ((other, rights) in cases) {
        idx++
        val f = Feeder()
        f.feed(other, 0.95f, 1200)
        f.framesR(left = other, *rights)
        check("第 $idx 次真实成功触发", f.events.size == 1, "触发 ${f.events.size} 次")
    }
}

private fun testRapidRepeat() {
    println("\n[9] 连续快闭 4 次（用户连点音量）→ 4 次都触发")
    val f = Feeder()
    f.feed(0.97f, 0.97f, 1200)
    repeat(4) {
        f.framesR(left = 0.97f, 0.60f, 0.30f, 0.05f, 0.05f, 0.05f, 0.05f, 0.05f)
        f.feed(0.97f, 0.97f, 500)
    }
    check("4 次都触发", f.events.size == 4, "触发 ${f.events.size} 次")
}

private fun testAlternatingWinks() {
    println("\n[10] 左右眼交替单闭 → 两次都触发")
    val f = Feeder()
    f.feed(0.95f, 0.95f, 900)
    f.framesR(left = 0.95f, 0.70f, 0.30f, 0.05f, 0.05f, 0.05f, 0.05f)
    f.feed(0.95f, 0.95f, 700)
    f.framesL(right = 0.95f, 0.70f, 0.30f, 0.05f, 0.05f, 0.05f, 0.05f)
    check("两次都触发", f.events.size == 2, "触发 ${f.events.size} 次")
}

private fun testOneShotPerClosure() {
    println("\n[11] 一次闭合只触发一次；重新睁大才能再来一次")
    val f = Feeder()
    f.feed(0.95f, 0.96f, 1000)
    f.feed(0.05f, 0.98f, 2000)
    check("一直闭着只触发一次", f.events.size == 1, "触发 ${f.events.size} 次")
    f.feed(0.95f, 0.95f, 1200)
    f.feed(0.05f, 0.98f, 600)
    check("重新睁大后再闭可以再来一次", f.events.size == 2, "触发 ${f.events.size} 次")
}

fun main() {
    println("=== WinkDetector v5.34 离线回放验证（真实代码 + 打印版 Log 桩）===")
    println("--- 这些必须不触发（误触发形态）---")
    testSustainedDrift()
    testLingerAfterBothEyesClosed()
    testBlinkFlutter()
    testBothEyesSemiClosed()
    testBlinkDoesNotTrigger()
    testTooLongClosure()
    println("--- 这些必须触发（真单闭形态，含 v5.33 被错杀的那批）---")
    testSlowDecayRealWink()
    testLoggedSuccesses()
    testRapidRepeat()
    testAlternatingWinks()
    testOneShotPerClosure()
    println("\n=== 结果：${if (failures == 0) "全部通过" else "$failures 项失败"} ===")
    if (failures != 0) throw IllegalStateException("$failures 项失败")
}
