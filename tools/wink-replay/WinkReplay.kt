package com.example.gazescroll

/**
 * v5.31 单闭判据的离线回归验证。
 *
 * 直接编译并运行**真实的** WinkDetector.kt（只把 android.util.Log 换成打印到 stdout 的桩），
 * 把 v5.30 日志里抓到的两类误触发序列按实测帧间隔（90ms）回放进去，看新版判据是否拒绝；
 * 再回放一次「有意单闭」，看是否照常触发。
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
        detector.openAbove = 0.70f
        detector.nearTier = true
    }

    /** 把 (左眼, 右眼) 的读数保持 [ms] 毫秒，每 90ms 喂一帧（实测帧间隔 63~116ms）。 */
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

    fun advance(ms: Long) {
        now += ms
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

private fun testSlowSquintDrift() {
    println("\n[1] v5.30 误触发回放（俯视眯眼：一只眼慢慢读低，22:03:09→16 的形状）")
    val f = Feeder()
    f.feed(0.98f, 0.99f, 1500)      // 眼睛好好地睁着
    for (i in 0 until 30) {         // 2.7 秒里从 0.98 慢慢滑到 0.08（每帧约 -0.03）
        f.feed(0.98f - i * 0.031f, 0.99f, 90)
    }
    f.feed(0.03f, 0.99f, 2000)      // 继续"深闭"两秒
    check("缓慢下滑的单眼低读数不触发", f.events.isEmpty(), "触发了 ${f.events.size} 次: ${f.events.map { it.side.label }}")
}

private fun testLingerAfterBothEyesClosed() {
    println("\n[2] v5.30 误触发回放（22:03:48 那次：两只眼先闭 2.68 秒，左眼睁开后又低 1 秒）")
    val f = Feeder()
    f.feed(0.95f, 0.95f, 800)
    f.feed(0.05f, 0.05f, 2680)      // 两只眼都闭着（眨眼通道按 >900ms 丢弃）
    f.feed(0.16f, 0.99f, 90)        // 右眼睁开，左眼还低
    f.feed(0.20f, 0.99f, 300)
    f.feed(0.29f, 0.99f, 700)       // 到这里 v5.30 正好凑满 1000ms → 误触发
    f.feed(0.30f, 0.99f, 500)
    check("长闭眼之后的眼皮残留不触发", f.events.isEmpty(), "触发了 ${f.events.size} 次")
}

private fun testDeliberateWink() {
    println("\n[3] 有意单闭（0.6 秒档）：睁着 → 一帧合上 → 保持 0.7 秒")
    val f = Feeder(holdMs = 600L)
    f.feed(0.95f, 0.96f, 1500)
    f.feed(0.15f, 0.98f, 90)        // 啪一下合上
    f.feed(0.04f, 0.98f, 700)       // 保持
    f.feed(0.95f, 0.95f, 600)       // 睁开
    val first = f.events.firstOrNull()
    check("触发一次", f.events.size == 1, "触发 ${f.events.size} 次")
    check("方向是左眼", first?.side == WinkSide.LEFT, "实际 ${first?.side}")
    check("保持时长在 600~800ms", (first?.heldMs ?: 0L) in 600L..800L, "实际 ${first?.heldMs}ms")
    check("起手合格（睁得稳）", (first?.openRunMs ?: 0L) >= 600L, "openRun=${first?.openRunMs}")
    check("起手合格（闭得快）", (first?.onsetMs ?: 9999L) <= 350L, "onset=${first?.onsetMs}")
}

private fun testOneStepPerEpisode() {
    println("\n[4] 一直闭着不重复调档（一次单闭只调一组）")
    val f = Feeder(holdMs = 400L)
    f.feed(0.95f, 0.96f, 1000)
    f.feed(0.05f, 0.98f, 3000)      // 连续闭着 3 秒
    check("只触发一次", f.events.size == 1, "触发 ${f.events.size} 次")
    f.feed(0.95f, 0.95f, 700)       // 睁开
    f.feed(0.05f, 0.98f, 500)       // 再闭一次
    check("睁眼再闭可以再来一次", f.events.size == 2, "触发 ${f.events.size} 次")
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

private fun testOtherEyeClosed() {
    println("\n[6] 另一只眼没睁着 → 不触发")
    val f = Feeder()
    f.feed(0.95f, 0.72f, 1500)      // 右眼只有 0.72（回差带附近）
    f.feed(0.05f, 0.60f, 1500)      // 左眼合上，右眼掉到 0.60（没到"明确睁着"）
    check("另一只眼不明确睁着时不触发", f.events.isEmpty(), "触发了 ${f.events.size} 次")
}

private fun testHoldOptions() {
    println("\n[7] 保持时长档位（0.4 秒档：闭 450ms 就应触发）")
    val f = Feeder(holdMs = 400L)
    f.feed(0.95f, 0.95f, 1200)
    f.feed(0.05f, 0.98f, 450)
    check("0.4 秒档能触发", f.events.size == 1, "触发 ${f.events.size} 次")
}

fun main() {
    println("=== WinkDetector v5.31 离线回放验证（真实代码 + 打印版 Log 桩）===")
    testSlowSquintDrift()
    testLingerAfterBothEyesClosed()
    testDeliberateWink()
    testOneStepPerEpisode()
    testBlinkDoesNotTrigger()
    testOtherEyeClosed()
    testHoldOptions()
    println("\n=== 结果：${if (failures == 0) "全部通过" else "$failures 项失败"} ===")
    if (failures != 0) throw IllegalStateException("$failures 项失败")
}
