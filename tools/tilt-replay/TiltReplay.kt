package com.example.gazescroll

/**
 * v5.38 歪头（roll）判据的离线回归验证。
 *
 * 直接编译并运行**真实的** TiltDetector.kt（只把 android.util.Log 换成打印到 stdout 的桩）。
 * Feeder 会像服务那样维护"上一次动作 + 2 秒"的禁判窗口，所以两秒规则也一起被验证。
 *
 * 跑法见同目录 run.ps1。
 */
private class Feeder(
    threshold: Float = TiltDetector.DEFAULT_THRESHOLD_DEG,
    holdMs: Long = TiltDetector.DEFAULT_HOLD_MS,
) {
    val events = ArrayList<TiltEvent>()
    val detector = TiltDetector { events.add(it) }
    var now = 0L

    /** 上一次动作的时刻（服务里是 lastActionAtMs）。 */
    private var lastActionAtMs = 0L

    /** 服务里那条全局规则：动作的起手必须晚于"上一次动作 + 2 秒"。 */
    var actionGapMs = 2000L

    init {
        detector.thresholdDeg = threshold
        detector.holdMs = holdMs
        detector.nearTier = true
        detector.gapStartAfterMs = 0L
    }

    /** 把滚转角保持 [ms] 毫秒，每 ≤90ms 喂一帧（实测帧间隔 63~116ms）。 */
    fun feed(roll: Float?, ms: Long, step: Long = 90L) {
        var remaining = ms
        while (true) {
            detector.gapStartAfterMs =
                if (lastActionAtMs == 0L) 0L else lastActionAtMs + actionGapMs
            val before = events.size
            detector.onRoll(roll, now)
            if (events.size > before) lastActionAtMs = now
            if (remaining <= 0L) break
            val s = minOf(step, remaining)
            now += s
            remaining -= s
            if (remaining == 0L) {
                detector.gapStartAfterMs =
                    if (lastActionAtMs == 0L) 0L else lastActionAtMs + actionGapMs
                val b2 = events.size
                detector.onRoll(roll, now)
                if (events.size > b2) lastActionAtMs = now
                break
            }
        }
    }

    /** 逐帧喂一串滚转角（每个值 2 帧，因为 [feed] 是"保持 ms 毫秒"的语义）。 */
    fun frames(vararg rolls: Float, step: Long = 90L) {
        for (r in rolls) feed(r, step, step)
    }

    /** 精确喂**一帧**并前进一个帧间隔（用来测量启动阶段的行为）。 */
    fun tick(roll: Float?) {
        detector.gapStartAfterMs =
            if (lastActionAtMs == 0L) 0L else lastActionAtMs + actionGapMs
        val before = events.size
        detector.onRoll(roll, now)
        if (events.size > before) lastActionAtMs = now
        now += 90L
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
    println("\n[1] 基本触发（v5.36 默认更灵敏：阈值 13°/保持 0.3 秒）：歪到 +14° 保持 0.4 秒")
    val f = Feeder()
    f.feed(0f, 1200)
    f.feed(14f, 400)
    check("触发一次", f.events.size == 1, "触发 ${f.events.size} 次")
    check("方向是 +（右）", f.events.firstOrNull()?.side == TiltSide.RIGHT, "实际 ${f.events.firstOrNull()?.side}")
}

private fun testNegativeTilt() {
    println("\n[2] 反方向：歪到 −14° → 左歪头")
    val f = Feeder()
    f.feed(0f, 1200)
    f.feed(-14f, 400)
    check("触发一次", f.events.size == 1, "触发 ${f.events.size} 次")
    check("方向是 −（左）", f.events.firstOrNull()?.side == TiltSide.LEFT, "实际 ${f.events.firstOrNull()?.side}")
}

private fun testOffsetBaseline() {
    println("\n[3] 本人平时就歪着头（基准线 −12°）：歪到 −26°（相对 14°）应触发")
    val f = Feeder()
    f.feed(-12f, 3000)
    f.feed(-26f, 400)
    check("触发一次", f.events.size == 1, "触发 ${f.events.size} 次")
    check(
        "基准线学到了 −12° 附近",
        kotlin.math.abs((f.detector.baselineDeg ?: 0f) + 12f) <= 2f,
        "baseline=${f.detector.baselineDeg}",
    )
}

private fun testHoldOptions() {
    println("\n[4] 保持 0.8 秒档：歪 14° 只坚持 0.4 秒不触发，坚持 0.9 秒才触发")
    val short = Feeder(holdMs = 800L)
    short.feed(0f, 1200)
    short.feed(14f, 400)
    short.feed(0f, 500)
    check("0.4 秒不够 → 不触发", short.events.isEmpty(), "触发了 ${short.events.size} 次")

    val long = Feeder(holdMs = 800L)
    long.feed(0f, 1200)
    long.feed(14f, 900)
    check("0.9 秒 → 触发", long.events.size == 1, "触发 ${long.events.size} 次")
}

// ------------------------------------------- 回正脖子 / 两秒规则（v5.36 重点）----

private fun testReturnToNeutralDoesNotFire() {
    println("\n[5] 歪着不动停一会再回正：回正不能触发（基准线在歪着时不更新）")
    val f = Feeder()
    f.feed(0f, 1500)
    f.feed(20f, 3000)               // 歪 20° 停 3 秒（0.3s 就触发；歪着期间不更新基准线）
    val firedWhileTilted = f.events.size
    f.feed(0f, 1500)                // 回正（一段快速运动）
    check("歪着时只触发一次", firedWhileTilted == 1, "触发了 $firedWhileTilted 次")
    check("回正没有触发（尤其不能反方向触发）", f.events.size == 1, "触发 ${f.events.size} 次")
    f.feed(0f, 4200)                // 回到中位停稳（顺便把两秒走满）
    check(
        "自愈：静止后倾斜角回到 0 附近",
        kotlin.math.abs(f.detector.tiltDeg ?: 99f) <= 2f,
        "tilt=${f.detector.tiltDeg}",
    )
    f.feed(-14f, 400)               // 自愈之后正常歪一次（左/升），方向必须正确
    check("停稳后重新歪照常触发", f.events.size == 2, "触发 ${f.events.size} 次")
}

private fun testResetKeepsBaseline() {
    println("\n[16] 服务 reset（遮挡/换应用）**不能丢掉头姿基准线** —— v5.36 那次事故的根源")
    val f = Feeder()
    f.feed(0f, 2000)
    val baseBefore = f.detector.baselineDeg ?: 0f
    f.detector.reset()              // 服务在遮挡/重绑时会这么调
    f.feed(0f, 300)
    val baseAfter = f.detector.baselineDeg ?: 99f
    check("reset 后基准线仍然是 0 附近（没被清掉）", kotlin.math.abs(baseAfter) <= 2f, "base=$baseAfter")
    check("reset 前后基准线一致", kotlin.math.abs(baseAfter - baseBefore) <= 2f, "$baseBefore -> $baseAfter")
    // 正常一次歪头（+14）应当照常触发，且**方向正确**（+ → 右 → 默认降）
    f.feed(14f, 400)
    check("reset 后的正常歪头照常触发", f.events.size == 1, "触发 ${f.events.size} 次")
    check("方向是 +（右/降）", f.events.firstOrNull()?.side == TiltSide.RIGHT, "实际 ${f.events.firstOrNull()?.side}")
    // 回正不能反方向触发
    f.feed(0f, 1500)
    check("回正没有触发反方向", f.events.size == 1, "触发 ${f.events.size} 次")
    check(
        "回正后倾斜角回到 0 附近",
        kotlin.math.abs(f.detector.tiltDeg ?: 99f) <= 3f,
        "tilt=${f.detector.tiltDeg}",
    )
}

private fun testFastSwingDoesNotFire() {
    println("\n[17] 回正/甩头（快速运动，中途经过中位）→ 不触发")
    val f = Feeder()
    f.feed(0f, 1500)
    f.feed(20f, 400)                // 正常触发一次（右/降）
    check("第一次触发", f.events.size == 1, "触发 ${f.events.size} 次")
    // 快速甩到另一边：一帧 0、一帧 -20（模拟 11fps 下"运动中途经过中位"）
    f.feed(0f, 90)
    f.feed(-20f, 900)
    check("运动中甩到另一边不触发", f.events.size == 1, "触发 ${f.events.size} 次")
    // 停稳 + 等满两秒之后，重新歪才算
    f.feed(0f, 2200)
    f.feed(-20f, 400)
    check("停稳等满两秒后重新歪 → 触发", f.events.size == 2, "触发 ${f.events.size} 次")
}

private fun testOvershootOnReturn() {
    println("\n[6] 回正时甩到另一边（+20° → −20°）：两秒内不许触发，等满两秒后重新歪才触发")
    val f = Feeder()
    f.feed(0f, 1500)
    f.feed(20f, 500)                // 触发（t≈1.5s）
    check("第一次触发", f.events.size == 1, "触发 ${f.events.size} 次")
    f.feed(-20f, 900)               // 回正时甩到另一边（距上次动作 ~0.2~1.1s，在 2 秒内）
    check("甩到另一边没触发（在 2 秒内）", f.events.size == 1, "触发 ${f.events.size} 次")
    f.feed(0f, 1100)                // 回到中位并把两秒走满
    f.feed(-20f, 500)               // 两秒后重新歪
    check("两秒后重新歪 → 触发", f.events.size == 2, "触发 ${f.events.size} 次")
}

private fun testNoLeadTime() {
    println("\n[7] 不能有提前量：起手卡在 1.99 秒（下一个动作在 2.1 秒）")
    val f = Feeder()
    f.feed(0f, 1500)

    // 先制造一次动作（歪头触发）
    f.feed(20f, 400)
    check("基准动作触发", f.events.size == 1, "触发 ${f.events.size} 次")

    // 回到中位，等到"距上次动作 1.9 秒"时开始歪，并一直歪过 2 秒
    f.feed(0f, 1400)                // 距上次动作约 1.8~2.0s（含保持时间）
    f.feed(20f, 1200)               // 起手落在两秒之内 → 整段作废，即使坚持超过两秒也不触发
    check("起手早于两秒 → 即使坚持过两秒也不触发", f.events.size == 1, "触发 ${f.events.size} 次")
}

private fun testGapBlocksRepeatedTilt() {
    println("\n[8] 两秒内连续歪两次：第二次必须等满两秒（回中位并重新歪）")
    val f = Feeder()
    f.feed(0f, 1500)
    f.feed(20f, 400)                // 第一次触发
    f.feed(0f, 500)                 // 回中位
    f.feed(20f, 400)                // 距第一次约 0.9s → 起手太早，不算
    check("第二次（在 2 秒内）不触发", f.events.size == 1, "触发 ${f.events.size} 次")
    f.feed(0f, 1500)                // 等过两秒
    f.feed(20f, 400)                // 第三次（2 秒后起手）
    check("等满两秒后触发", f.events.size == 2, "触发 ${f.events.size} 次")
}

// ------------------------------------------------------------- 必须不触发 ----

private fun testTooShort() {
    println("\n[9] 抖一下头：歪 14° 只 150ms 就回正 → 不触发")
    val f = Feeder()
    f.feed(0f, 1200)
    f.feed(14f, 150)
    f.feed(0f, 1500)
    check("不触发", f.events.isEmpty(), "触发了 ${f.events.size} 次")
}

private fun testBelowThreshold() {
    println("\n[10] 角度不够：歪 8°（< 13°）保持 3 秒 → 不触发")
    val f = Feeder()
    f.feed(0f, 1200)
    f.feed(8f, 3000)
    check("不触发", f.events.isEmpty(), "触发了 ${f.events.size} 次")
}

private fun testSingleFrameSpike() {
    println("\n[11] 单帧尖峰：一帧 25° 再立刻回正 → 不触发")
    val f = Feeder()
    f.feed(0f, 1200)
    f.frames(25f, 0f, 0f, 0f, 0f)
    f.feed(0f, 1500)
    check("不触发", f.events.isEmpty(), "触发了 ${f.events.size} 次")
}

private fun testNotEnoughSamples() {
    println("\n[12] 刚有脸（基准线还不到 8 帧）时歪头 → 不判定（不触发）")
    val f = Feeder()
    repeat(3) { f.tick(0f) }
    repeat(4) { f.tick(20f) }
    check("不触发", f.events.isEmpty(), "触发了 ${f.events.size} 次")
}

private fun testInsaneTilt() {
    println("\n[13] 读数太歪（70°，例如整个人躺下）→ 不触发且重置")
    val f = Feeder()
    f.feed(0f, 1200)
    f.feed(70f, 2000)
    check("不触发", f.events.isEmpty(), "触发了 ${f.events.size} 次")
}

private fun testFaceLostResets() {
    println("\n[14] 歪到一半丢脸（null）→ 不触发")
    val f = Feeder()
    f.feed(0f, 1200)
    f.feed(20f, 200)
    f.feed(null, 400)
    f.feed(20f, 200)
    check("不触发", f.events.isEmpty(), "触发了 ${f.events.size} 次")
}

private fun testOneShotPerEpisode() {
    println("\n[15] 一直歪着只触发一次（不会一直调音量）")
    val f = Feeder()
    f.feed(0f, 1500)
    f.feed(20f, 4000)
    check("只触发一次", f.events.size == 1, "触发 ${f.events.size} 次")
}

fun main() {
    println("=== TiltDetector v5.38 离线回放验证（真实代码 + 打印版 Log 桩）===")
    println("--- 必须触发 ---")
    testBasicTilt()
    testNegativeTilt()
    testOffsetBaseline()
    testHoldOptions()
    println("--- 回正脖子 / 两秒规则（v5.36-v5.37 重点）---")
    testReturnToNeutralDoesNotFire()
    testResetKeepsBaseline()
    testFastSwingDoesNotFire()
    testOvershootOnReturn()
    testNoLeadTime()
    testGapBlocksRepeatedTilt()
    println("--- 必须不触发 ---")
    testTooShort()
    testBelowThreshold()
    testSingleFrameSpike()
    testNotEnoughSamples()
    testInsaneTilt()
    testFaceLostResets()
    testOneShotPerEpisode()
    println("\n=== 结果：${if (failures == 0) "全部通过" else "$failures 项失败"} ===")
    if (failures != 0) throw IllegalStateException("$failures 项失败")
}
