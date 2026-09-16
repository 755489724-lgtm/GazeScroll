package com.example.gazescroll

/**
 * v5.33 单闭判据的离线回归验证。
 *
 * 直接编译并运行**真实的** WinkDetector.kt（只把 android.util.Log 换成打印到 stdout 的桩），
 * 用 v5.30 / v5.31 / v5.32 三轮实机日志里的序列回放：
 *  - v5.30 的「低头时一只眼被慢慢读低」→ 必须不触发
 *  - v5.32 的「眨眼 / 两只眼一起半闭」→ 必须不触发
 *  - 有意单闭（含 v5.31 被错杀的那批、v5.32 成功的那批）→ 必须触发
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

private fun testBlinkFlutter() {
    println("\n[3] v5.32 误触发回放（22:25:56：用户眨眼/半闭，两只眼一起落在 0.5~0.65）")
    val f = Feeder()
    f.feed(0.90f, 0.90f, 900)
    // 左眼 0.52 起手 → 一帧深到 0.15（onset 很快）→ 又抬回 0.50；右眼全程 0.65
    f.framesL(right = 0.65f, 0.52f, 0.15f, 0.28f, 0.45f, 0.50f, 0.50f, 0.50f)
    f.feed(0.90f, 0.90f, 600)
    check("两只眼读数差不多时不触发", f.events.isEmpty(), "触发了 ${f.events.size} 次")
}

private fun testBothEyesSemiClosed() {
    println("\n[4] 两只眼一起半闭（眨眼后眼皮没完全抬起来）→ 不触发")
    val f = Feeder()
    f.feed(0.95f, 0.95f, 800)
    // 左 0.45 / 右 0.60：都低于 0.65、都在阈值附近，差只有 0.15
    f.feed(0.45f, 0.60f, 900)
    f.feed(0.95f, 0.95f, 500)
    check("两只眼一起半闭不触发", f.events.isEmpty(), "触发了 ${f.events.size} 次")
}

private fun testSlowClosure() {
    println("\n[5] 合眼太慢（0.62→0.28 用 630ms）→ 不触发")
    val f = Feeder()
    f.feed(0.95f, 0.99f, 800)
    f.framesL(right = 0.99f, 0.62f, 0.57f, 0.52f, 0.48f, 0.44f, 0.40f, 0.36f, 0.32f, 0.28f)
    f.feed(0.05f, 0.99f, 1500)
    check("慢慢眯下去不触发", f.events.isEmpty(), "触发了 ${f.events.size} 次")
}

private fun testBlinkDoesNotTrigger() {
    println("\n[6] 正常眨眼（两只眼同时闭）不触发")
    val f = Feeder()
    f.feed(0.95f, 0.95f, 1000)
    repeat(3) {
        f.feed(0.03f, 0.02f, 200)
        f.feed(0.95f, 0.95f, 900)
    }
    check("三连眨不触发", f.events.isEmpty(), "触发了 ${f.events.size} 次")
}

// ------------------------------------------------------- 必须触发（真单闭形态）--

private fun testLoggedFalseThenTrue() {
    println("\n[7] 回放 v5.32 那批真实触发（22:26:12 / :17 / :25 / :27 四条日志）")
    val cases: List<Triple<String, Float, FloatArray>> = listOf(
        // 闭的那只 / 另一只 / 变化过程
        Triple("22:26:12 右眼 0.30/0.77", 0.77f, floatArrayOf(0.60f, 0.30f, 0.03f, 0.05f, 0.30f, 0.44f, 0.44f)),
        Triple("22:26:17 右眼 0.13/0.95", 0.95f, floatArrayOf(0.70f, 0.30f, 0.13f, 0.05f, 0.13f, 0.13f, 0.13f)),
        Triple("22:26:25 右眼 0.43/0.95", 0.95f, floatArrayOf(0.62f, 0.43f, 0.10f, 0.03f, 0.10f, 0.43f, 0.43f)),
        Triple("22:26:27 右眼 0.24/0.98", 0.98f, floatArrayOf(0.62f, 0.40f, 0.24f, 0.21f, 0.24f, 0.24f, 0.24f)),
    )
    for ((name, left, rights) in cases) {
        val f = Feeder()
        f.feed(left, 0.90f, 900)
        f.framesR(left = left, *rights)
        check("$name 触发", f.events.size == 1, "触发 ${f.events.size} 次")
    }
}

private fun testV531RejectsNowFire() {
    println("\n[8] v5.31 被错杀的那批（另一只眼只有 0.59 / 0.65）→ 现在应当触发")
    val f = Feeder(holdMs = 600L)
    f.feed(0.59f, 0.95f, 900)          // 另一只眼（右）只有 0.59：没闭着，但也不是"很开"
    f.framesL(right = 0.59f, 0.50f, 0.12f, 0.04f, 0.04f, 0.04f, 0.04f, 0.04f, 0.04f)
    check("另一只眼 0.59 也触发", f.events.size == 1, "触发 ${f.events.size} 次")

    val g = Feeder(holdMs = 600L)
    g.feed(0.95f, 0.65f, 900)
    g.framesL(right = 0.65f, 0.50f, 0.10f, 0.03f, 0.03f, 0.03f, 0.03f, 0.03f, 0.03f)
    check("另一只眼 0.65 也触发", g.events.size == 1, "触发 ${g.events.size} 次")
}

private fun testAlternatingWinks() {
    println("\n[9] 左右眼交替单闭（v5.32 的 bug：另一只眼的时间戳不更新 → 一律判「合眼太慢」）")
    val f = Feeder()
    f.feed(0.95f, 0.95f, 900)
    f.framesR(left = 0.95f, 0.70f, 0.30f, 0.05f, 0.05f, 0.05f, 0.05f)   // 右眼单闭
    f.feed(0.95f, 0.95f, 700)
    f.framesL(right = 0.95f, 0.70f, 0.30f, 0.05f, 0.05f, 0.05f, 0.05f)   // 紧接着左眼单闭
    check("两次都触发", f.events.size == 2, "触发 ${f.events.size} 次")
}

private fun testReplayOfLoggedSuccess() {
    println("\n[10] 回放 22:19:36 那次真实成功（held=616ms onset=278ms min=0.06 other=0.91）")
    val f = Feeder(holdMs = 600L)
    f.feed(0.91f, 0.95f, 1200)
    f.framesR(left = 0.91f, 0.60f, 0.30f, 0.06f, 0.06f, 0.06f, 0.06f, 0.06f, 0.06f)
    check("触发一次", f.events.size == 1, "触发 ${f.events.size} 次")
    check(
        "onset ≤500ms",
        (f.events.firstOrNull()?.onsetMs ?: 9999L) <= 500L,
        "onset=${f.events.firstOrNull()?.onsetMs}",
    )
}

private fun testOneShotPerEpisode() {
    println("\n[11] 一次单闭只调一次；重新睁大才能再来一次")
    val f = Feeder()
    f.feed(0.95f, 0.96f, 1000)
    f.feed(0.05f, 0.98f, 3000)
    check("一直闭着只触发一次", f.events.size == 1, "触发 ${f.events.size} 次")
    f.feed(0.95f, 0.95f, 700)
    f.feed(0.05f, 0.98f, 600)
    check("睁大后再闭可以再来一次", f.events.size == 2, "触发 ${f.events.size} 次")
}

fun main() {
    println("=== WinkDetector v5.33 离线回放验证（真实代码 + 打印版 Log 桩）===")
    println("--- 这些必须不触发（误触发形态）---")
    testSlowSquintDrift()
    testLingerAfterBothEyesClosed()
    testBlinkFlutter()
    testBothEyesSemiClosed()
    testSlowClosure()
    testBlinkDoesNotTrigger()
    println("--- 这些必须触发（真单闭形态）---")
    testLoggedFalseThenTrue()
    testV531RejectsNowFire()
    testAlternatingWinks()
    testReplayOfLoggedSuccess()
    testOneShotPerEpisode()
    println("\n=== 结果：${if (failures == 0) "全部通过" else "$failures 项失败"} ===")
    if (failures != 0) throw IllegalStateException("$failures 项失败")
}
