package com.example.gazescroll

/**
 * v5.39「注视数据采集」状态机的离线回归验证。
 *
 * 直接编译并运行**真实的** [GazeProbeRecorder]（它本身没有任何 Android 依赖，落盘和
 * 打日志都是注入进来的 lambda），用合成帧回放整套协议：
 *
 *   盖 3 秒 → 露脸开始录 → 盖 1~3 秒分段 → 盖 ≥3 秒结束
 *
 * 重点验证两件容易写错的事：
 *  1. **只判"人脸消失"是不够的** —— 录制中"转头看别处"（没脸但画面是亮的）绝不能被
 *     当成盖住，否则采集脚本里"眼睛离开屏幕"的段落会把整场录制提前结束；
 *  2. 停止之后必须重新"露脸 + 盖 3 秒"才能开始第二次，不能自己接着录。
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

/** 一次采集会话的落盘/日志接收器 + 帧发生器。 */
private class Rig {
    val logs = ArrayList<String>()
    val lines = ArrayList<String>()
    var filesOpened = 0
    var filesClosed = 0
    var lastName = ""

    val recorder = GazeProbeRecorder(
        log = { logs.add(it) },
        openFile = { name ->
            filesOpened++
            lastName = name
        },
        write = { lines.add(it) },
        closeFile = { filesClosed++ },
        appTag = "replay",
    )

    var now = 0L
    private var wall = 1_700_000_000_000L

    /** 喂一帧并前进 [stepMs]（实测帧间隔 63~116ms，默认 66ms ≈ 15fps）。 */
    fun frame(
        face: Boolean,
        dark: Boolean = false,
        luma: Float? = null,
        stepMs: Long = 66L,
        texture: Float? = null,
        proxNear: Boolean = false,
        proxAvailable: Boolean = false,
        lux: Float? = null,
    ) {
        val effective = when {
            luma != null -> luma
            dark -> 8f
            else -> 95f
        }
        // 默认纹理跟随亮度：暗画面 = 2（被盖住），亮画面 = 20（正常场景有边缘）。
        val effectiveTexture = texture ?: if (effective < 32f) 2f else 20f
        recorder.onFrame(
            ProbeSample(
                wallMs = wall,
                faceDetected = face,
                standby = false,
                frameWidth = 480,
                frameHeight = 640,
                luma = effective,
                texture = effectiveTexture,
                proximityNear = proxNear,
                proximityAvailable = proxAvailable,
                lux = lux,
                boxLeft = if (face) 0.30f else -1f,
                boxTop = if (face) 0.25f else -1f,
                boxRight = if (face) 0.70f else -1f,
                boxBottom = if (face) 0.75f else -1f,
                faceRatio = if (face) 0.50f else null,
                eulerX = if (face) 4f else null,
                eulerY = if (face) -2f else null,
                eulerZ = if (face) 1f else null,
                eyeLeftX = if (face) 0.42f else null,
                eyeLeftY = if (face) 0.38f else null,
                eyeRightX = if (face) 0.58f else null,
                eyeRightY = if (face) 0.38f else null,
                noseX = if (face) 0.50f else null,
                noseY = if (face) 0.50f else null,
                mouthX = if (face) 0.50f else null,
                mouthY = if (face) 0.62f else null,
                eyeOpenLeft = if (face) 0.95f else 0f,
                eyeOpenRight = if (face) 0.94f else 0f,
                mouthOpenRatio = if (face) 0.21f else null,
                chinRatio = if (face) 0.33f else null,
                noseNormY = if (face) 0.50f else null,
                chinNormY = if (face) 0.74f else null,
                eyeNormY = if (face) 0.26f else null,
                noseRelEye = if (face) 0.24f else null,
                chinRelEye = if (face) 0.48f else null,
                trackingId = if (face) 7 else null,
            ),
            now,
        )
        now += stepMs
        wall += stepMs
    }

    /** 保持 [ms] 毫秒的同一状态。 */
    fun hold(
        face: Boolean,
        ms: Long,
        dark: Boolean = false,
        luma: Float? = null,
        stepMs: Long = 66L,
        texture: Float? = null,
        proxNear: Boolean = false,
        proxAvailable: Boolean = false,
        lux: Float? = null,
    ) {
        var remaining = ms
        while (remaining > 0) {
            frame(face, dark, luma, stepMs, texture, proxNear, proxAvailable, lux)
            remaining -= stepMs
        }
    }

    /** CSV 数据行（非 `#` 开头）的列数是否都等于表头列数。 */    fun dataRowColumnIssue(): String {
        val header = lines.firstOrNull { it.startsWith("idx,") } ?: return "没有表头"
        val expected = header.split(",").size
        for (line in lines) {
            if (line.startsWith("#") || line.startsWith("idx,")) continue
            val n = line.split(",").size
            if (n != expected) return "列数 $n != $expected : $line"
        }
        return ""
    }

    /** 只推进时钟、不喂帧（模拟"帧流被打断"，例如通知栏把相机挤掉了）。 */
    fun idle(ms: Long) {
        now += ms
    }

    fun hasLog(fragment: String): Boolean = logs.any { it.contains(fragment) }

    fun hasLine(fragment: String): Boolean = lines.any { it.contains(fragment) }
}

// ------------------------------------------------------------------ 用例 --

private fun testNoFaceMeansNoStart() {
    println("\n[1] 还没见过脸时，盖住 5 秒也不算开始")
    val r = Rig()
    r.hold(false, 5000, dark = true)
    check("没有 armed", r.recorder.state == ProbeState.IDLE, "state=${r.recorder.state}")
    check("没有开文件", r.filesOpened == 0)
}

private fun testArmRequiresThreeSeconds() {
    println("\n[2] 盖住 2 秒不够、3 秒以上才算「已就绪」")
    val a = Rig()
    a.hold(true, 1000)
    a.hold(false, 2500, dark = true)
    check("2.5 秒还没 armed", a.recorder.state == ProbeState.IDLE, "state=${a.recorder.state}")

    val b = Rig()
    b.hold(true, 1000)
    b.hold(false, 3200, dark = true)
    check("3.2 秒 armed", b.recorder.state == ProbeState.ARMED, "state=${b.recorder.state}")
    check("打了 ARMED 日志", b.hasLog("ARMED"))
}

private fun testStartOnFaceReturn() {
    println("\n[3] 已就绪后露出脸 → 立刻开始录制，写出表头与数据行")
    val r = Rig()
    r.hold(true, 1000)
    r.hold(false, 3200, dark = true)
    r.hold(true, 1000)
    check("录制中", r.recorder.state == ProbeState.RECORDING, "state=${r.recorder.state}")
    check("开了 1 个文件", r.filesOpened == 1)
    check("第 1 段", r.recorder.phase == 1, "phase=${r.recorder.phase}")
    check("文件名像 probe-*.csv", r.lastName.startsWith("probe-") && r.lastName.endsWith(".csv"), r.lastName)
    check("有表头", r.hasLine("idx,wall,elapsed"))
    check("有 # 元信息", r.hasLine("# cover rule"), "表头里没有盖住规则说明")
    check("列数一致", r.dataRowColumnIssue().isEmpty(), r.dataRowColumnIssue())
    val rows = r.recorder.rows
    check("帧数 ≈ 1000/66", rows in 14..16, "rows=$rows")
}

private fun testPhaseSeparator() {
    println("\n[4] 录制中盖 2 秒 = 分段，录制不中断")
    val r = Rig()
    r.hold(true, 1000)
    r.hold(false, 3200, dark = true)
    r.hold(true, 1000)
    val before = r.recorder.rows
    r.hold(false, 2000, dark = true)
    r.hold(true, 500)
    check("仍在录制", r.recorder.state == ProbeState.RECORDING, "state=${r.recorder.state}")
    check("第 2 段", r.recorder.phase == 2, "phase=${r.recorder.phase}")
    check("文件没被关", r.filesClosed == 0)
    check("帧数在涨", r.recorder.rows > before, "${r.recorder.rows} vs $before")
    check("有 PHASE 日志", r.hasLog("PHASE 2"))
    check("CSV 里有 # PHASE 2", r.hasLine("# PHASE 2"))
}

private fun testShortCoverIgnored() {
    println("\n[5] 录制中只盖 0.5 秒（手晃一下）= 不分段")
    val r = Rig()
    r.hold(true, 1000)
    r.hold(false, 3200, dark = true)
    r.hold(true, 1000)
    r.hold(false, 500, dark = true)
    r.hold(true, 500)
    check("还是第 1 段", r.recorder.phase == 1, "phase=${r.recorder.phase}")
    check("仍在录制", r.recorder.state == ProbeState.RECORDING)
}

private fun testStopOnLongCover() {
    println("\n[6] 录制中盖 6.5 秒 = 结束，写 SUMMARY 并关文件")
    val r = Rig()
    r.hold(true, 1000)
    r.hold(false, 3200, dark = true)
    r.hold(true, 2000)
    r.hold(false, 6500, dark = true)
    check("回到 IDLE", r.recorder.state == ProbeState.IDLE, "state=${r.recorder.state}")
    check("关了文件", r.filesClosed == 1, "closed=${r.filesClosed}")
    check("有 SUMMARY", r.hasLine("# SUMMARY"))
    check("每段都有汇总", r.hasLine("# PHASE 1 rows="))
    check("打了 REC STOP", r.hasLog("REC STOP"))
    check("lastSummary 有内容", r.recorder.lastSummary.isNotEmpty())
}

private fun testLookingAwayDoesNotStop() {
    println("\n[7] 录制中转头看别处 6 秒（没脸但画面亮）→ 既不结束也不分段 ★关键")
    val r = Rig()
    r.hold(true, 1000)
    r.hold(false, 3200, dark = true)
    r.hold(true, 1000)
    // 画面是亮的（luma=95）但没有人脸：人还在，只是把头转开了。
    r.hold(false, 6000, dark = false)
    check("仍在录制", r.recorder.state == ProbeState.RECORDING, "state=${r.recorder.state}")
    check("仍然第 1 段", r.recorder.phase == 1, "phase=${r.recorder.phase}")
    check("文件没被关", r.filesClosed == 0)
    r.hold(true, 1000)
    check("回到画面照样继续录", r.recorder.state == ProbeState.RECORDING)
}

private fun testSecondSessionNeedsNewCover() {
    println("\n[8] 结束之后：只露脸不盖摄像头 → 不会自己接着录；重新盖 3 秒才开始第二次")
    val r = Rig()
    r.hold(true, 1000)
    r.hold(false, 3200, dark = true)
    r.hold(true, 1000)
    r.hold(false, 6500, dark = true)
    check("第一次已结束", r.recorder.state == ProbeState.IDLE)
    r.hold(true, 3000)
    check("只露脸不会开始", r.recorder.state == ProbeState.IDLE && r.filesOpened == 1, "state=${r.recorder.state}")
    r.hold(false, 3200, dark = true)
    check("盖 3 秒 → armed", r.recorder.state == ProbeState.ARMED, "state=${r.recorder.state}")
    r.hold(true, 500)
    check("第二次开始录制", r.recorder.state == ProbeState.RECORDING && r.filesOpened == 2)
    check("段号从 1 重新数", r.recorder.phase == 1)
}

private fun testLumaUnavailableFallsBackToFaceLoss() {
    println("\n[9] 「盖住」的三条信号（v5.40）：近距离传感器 / 纹理 / 亮度 各自独立成立")
    val r = Rig()
    check(
        "没脸 + 亮度未知 + 纹理未知 + 没有近距离传感器 → 退化成算盖住",
        r.recorder.isCovered(ProbeSample(0, false, false, 480, 640, luma = null, texture = null)),
    )
    check(
        "有脸 → 永远不算盖住",
        !r.recorder.isCovered(ProbeSample(0, true, false, 480, 640, luma = 5f, texture = 1f)),
    )
    check(
        "没脸 + 画面亮 + 有纹理（转头看别处）→ 不算盖住",
        !r.recorder.isCovered(ProbeSample(0, false, false, 480, 640, luma = 95f, texture = 20f)),
    )
    check(
        "没脸 + 亮度低 → 算盖住（v5.39 原判据）",
        r.recorder.isCovered(ProbeSample(0, false, false, 480, 640, luma = 5f, texture = 20f)),
    )
    check(
        "没脸 + 自动曝光把画面提亮了、但纹理低 → 算盖住 ★v5.40 的核心修复",
        r.recorder.isCovered(ProbeSample(0, false, false, 480, 640, luma = 88f, texture = 1.5f)),
    )
    check(
        "没脸 + 画面又亮又有纹理，但近距离传感器 NEAR + 环境光也暗 → 算盖住 ★硬信号",
        r.recorder.isCovered(
            ProbeSample(
                0, false, false, 480, 640, luma = 88f, texture = 20f,
                proximityNear = true, proximityAvailable = true, lux = 8f,
            ),
        ),
    )
    check(
        "近距离传感器可用但读数是 far、画面也正常 → 不算盖住",
        !r.recorder.isCovered(
            ProbeSample(
                0, false, false, 480, 640, luma = 88f, texture = 20f,
                proximityNear = false, proximityAvailable = true, lux = 22f,
            ),
        ),
    )
    check(
        "★近距离传感器闩锁在 NEAR、但房间是亮的（真实场景 18:47:24）→ 不算盖住",
        !r.recorder.isCovered(
            ProbeSample(
                0, false, false, 480, 640, luma = 88f, texture = 20f,
                proximityNear = true, proximityAvailable = true, lux = 22f,
            ),
        ),
    )
    check(
        "近距离传感器 NEAR 且环境光读不到（没有 ALS 的机型）→ 仍然算盖住",
        r.recorder.isCovered(
            ProbeSample(
                0, false, false, 480, 640, luma = 88f, texture = 20f,
                proximityNear = true, proximityAvailable = true, lux = null,
            ),
        ),
    )
    check(
        "盖住时纹理掉到 4.5（实测值）也算盖住",
        r.recorder.isCovered(ProbeSample(0, false, false, 480, 640, luma = 80f, texture = 4.5f)),
    )
    check(
        "没盖住时纹理 8.2（实测地板值）不算盖住",
        !r.recorder.isCovered(ProbeSample(0, false, false, 480, 640, luma = 99f, texture = 8.2f)),
    )
    check(
        "coverReason 能说出是哪一条救的",
        r.recorder.coverReason(
            ProbeSample(
                0, false, false, 480, 640, luma = 88f, texture = 20f,
                proximityNear = true, proximityAvailable = true, lux = 8f,
            ),
        ) == "prox+lux",
    )
}

private fun testV539FailureCaseIsNowCovered() {
    println("\n[13] 回放 v5.39 那次真实失败：盖住时人脸消失 50 秒、画面被自动曝光提亮")
    val r = Rig()
    // 自动曝光把被盖住的画面提到 88，纹理 1.5，近距离传感器 NEAR，环境光掉到 8。
    r.hold(true, 1500, proxAvailable = true, lux = 22f)
    check("先见到脸", r.recorder.state == ProbeState.IDLE)
    r.hold(
        false, 4000, luma = 88f, texture = 1.5f,
        proxNear = true, proxAvailable = true, lux = 8f,
    )
    check(
        "现在能进入已就绪（v5.39 时这里一直是 idle）",
        r.recorder.state == ProbeState.ARMED,
        "state=${r.recorder.state}",
    )
    r.hold(true, 1000, texture = 20f, proxAvailable = true, lux = 30f)
    check("露脸即开始录制", r.recorder.state == ProbeState.RECORDING && r.filesOpened == 1)
    check("打了 cover-probe 诊断行", r.hasLog("cover-probe"))
    check(
        "cover-probe 行里有三个信号与命中原因",
        r.logs.any {
            it.contains("cover-probe") && it.contains("luma=") && it.contains("tex=") &&
                it.contains("prox=") && it.contains("why=")
        },
    )
}

private fun testGapAndBlinkMarkers() {
    println("\n[15] 帧流中断 ≥1.5 秒 = 也记一个分段边界（v5.42 兜底）")
    val r = Rig()
    r.hold(true, 1000, proxAvailable = true, lux = 22f)
    r.hold(false, 4000, proxNear = true, proxAvailable = true, lux = 8f)
    r.hold(true, 1500, proxAvailable = true, lux = 22f)
    check("录起来了", r.recorder.state == ProbeState.RECORDING)
    check("第 1 段", r.recorder.phase == 1, "phase=${r.recorder.phase}")

    // 模拟"手掌压到屏幕把通知栏拉下来"：8 秒一帧都没有。
    r.idle(8000)
    r.hold(true, 1000, proxAvailable = true, lux = 30f)
    check(
        "帧流断了 8 秒 → 记了一个分段边界（真实第三轮就是这里丢了 9 秒）",
        r.recorder.phase == 2,
        "phase=${r.recorder.phase}",
    )
    check("CSV 里写了原因", r.hasLine("frame gap"), )
    check("日志里写了原因", r.hasLog("frame gap"))

    // 短的帧间隔（正常 15fps）不能触发。
    val r2 = Rig()
    r2.hold(true, 1000)
    r2.hold(false, 4000, dark = true)
    r2.hold(true, 3000)
    val before = r2.recorder.phase
    r2.idle(300)
    r2.hold(true, 1000)
    check("只断 300ms 不算边界", r2.recorder.phase == before, "phase=${r2.recorder.phase}")
}

private fun testBlinkMarker() {
    println("\n[16] 录制中眨出一次翻页 = 换下一段（不用碰手机）")
    val r = Rig()
    r.hold(true, 1000, proxAvailable = true, lux = 22f)
    r.hold(false, 4000, proxNear = true, proxAvailable = true, lux = 8f)
    r.hold(true, 1500, proxAvailable = true, lux = 22f)
    check("录起来了", r.recorder.state == ProbeState.RECORDING)
    val rowsBefore = r.recorder.rows
    r.recorder.onMarker("blink:2", r.now)
    check("眨一次翻页 → 第 2 段", r.recorder.phase == 2, "phase=${r.recorder.phase}")
    check("不额外写数据行", r.recorder.rows == rowsBefore)
    check("日志里写了 marker", r.hasLog("marker blink:2"))

    // 不在录制中时，标记不算数。
    val r2 = Rig()
    r2.hold(true, 1000)
    r2.recorder.onMarker("blink:2", r2.now)
    check("待机时的眨眼不算分段", r2.recorder.phase == 0, "phase=${r2.recorder.phase}")
}

private fun testStopCoverThreshold() {
    println("\n[14] 分段 / 结束的分界线是 6 秒（v5.41：用户按'1 秒'盖的实际是 2.1~2.3 秒）")
    val r = Rig()
    r.hold(true, 1000, proxAvailable = true, lux = 22f)
    r.hold(false, 4000, proxNear = true, proxAvailable = true, lux = 8f)
    r.hold(true, 1500, proxAvailable = true, lux = 22f)
    check("录起来了", r.recorder.state == ProbeState.RECORDING, "state=${r.recorder.state}")

    // 用户"盖 1 秒"的实测时长 2.2 秒 → 必须只算分段，不能结束。
    r.hold(false, 2200, proxNear = true, proxAvailable = true, lux = 8f)
    r.hold(true, 800, proxAvailable = true, lux = 22f)
    check(
        "盖 2.2 秒 = 分段，录制继续（v5.40 时会被 3 秒界线以外的情况误伤）",
        r.recorder.state == ProbeState.RECORDING && r.recorder.phase == 2,
        "state=${r.recorder.state} phase=${r.recorder.phase}",
    )

    // 用户"盖 3 秒"的实测时长 3.04 秒 → 在 v5.40 里会结束录制，现在必须仍然只是分段。
    r.hold(false, 3040, proxNear = true, proxAvailable = true, lux = 8f)
    r.hold(true, 800, proxAvailable = true, lux = 22f)
    check(
        "盖 3.04 秒 = 仍然只是分段（这正是第一轮把整场录制止住的那一下）",
        r.recorder.state == ProbeState.RECORDING && r.recorder.phase == 3,
        "state=${r.recorder.state} phase=${r.recorder.phase}",
    )

    r.hold(false, 6500, proxNear = true, proxAvailable = true, lux = 8f)
    check("盖 6.5 秒 = 结束", r.recorder.state == ProbeState.IDLE, "state=${r.recorder.state}")
    check("关了文件", r.filesClosed == 1)
}

private fun testDisableMidRecording() {
    println("\n[10] 录制中关掉开关 → 立刻收尾（写 SUMMARY + 关文件），重复关闭不重复收尾")
    val r = Rig()
    r.hold(true, 1000)
    r.hold(false, 3200, dark = true)
    r.hold(true, 2000)
    r.recorder.disable(r.now)
    check("状态 OFF", r.recorder.state == ProbeState.OFF)
    check("关了文件", r.filesClosed == 1)
    check("有 SUMMARY", r.hasLine("# SUMMARY"))
    check("打了 probe OFF", r.hasLog("probe OFF"))
    val summaries = r.lines.count { it.startsWith("# SUMMARY") }
    r.recorder.disable(r.now)
    check(
        "重复 disable 幂等",
        r.filesClosed == 1 && r.lines.count { it.startsWith("# SUMMARY") } == summaries,
    )
    r.hold(true, 600)
    check(
        "重新喂帧后从待机重新开始（不会自动接上刚才那次）",
        r.recorder.state == ProbeState.IDLE && r.filesOpened == 1,
        "state=${r.recorder.state} opened=${r.filesOpened}",
    )
}

private fun testMaxSessionGuard() {
    println("\n[11] 5 分钟保险：一直录也会自己结束")
    val r = Rig()
    r.hold(true, 1000, stepMs = 1000L)
    r.hold(false, 4000, dark = true, stepMs = 1000L)
    r.hold(true, 400_000, stepMs = 1000L)
    check("自动结束", r.recorder.state == ProbeState.IDLE, "state=${r.recorder.state}")
    check("SUMMARY 里写了 max", r.hasLine("stop=max"), "没有 max 原因")
    check("关了文件", r.filesClosed == 1)
}

private fun testStateLine() {
    println("\n[12] 诊断行字段（GazeDiag 里用）")
    val r = Rig()
    check("关着时 probe=off", r.recorder.stateLine() == "probe=off", r.recorder.stateLine())
    r.hold(true, 1000)
    check("待机时可见有没有见过脸", r.recorder.stateLine().startsWith("probe=idle sawFace="), r.recorder.stateLine())
    r.hold(false, 3200, dark = true)
    check("armed", r.recorder.stateLine() == "probe=armed", r.recorder.stateLine())
    r.hold(true, 1000)
    check("录制中带段号与帧数", r.recorder.stateLine().startsWith("probe=rec ph=1 rows="), r.recorder.stateLine())
}

// ------------------------------------------------ v5.43 注视门（GazeGate） --

private class GateRig {
    val gate = GazeGate()
    var now = 0L

    /** 喂 [ms] 毫秒的同一状态，返回结束时刻。 */
    fun feed(eyeOpen: Float?, ms: Long, step: Long = 66L) {
        var remaining = ms
        while (remaining > 0) {
            gate.onFrame(eyeOpen, eyeOpen, now)
            now += step
            remaining -= step
        }
    }

    fun decide(
        channel: GateChannel = GateChannel.BLINK,
        face: Boolean = true,
        ratio: Float? = 0.35f,
        yaw: Float? = 0f,
        roll: Float? = 0f,
    ) = gate.decide(channel, face, ratio, yaw, roll)
}

private fun testGazeGate() {
    println("\n[17] 注视门（v5.43）：判据逐条验证")
    val g = GateRig()

    // ① 脸不在画面 → 拦。
    check(
        "没脸 → no-face",
        g.decide(face = false).reason == "no-face",
        "${g.decide(face = false).reason}",
    )
    // ② 脸太小（手机放桌上了）→ 拦。
    check(
        "脸太小 → too-far",
        g.decide(ratio = 0.15f).reason == "too-far",
        "${g.decide(ratio = 0.15f).reason}",
    )
    // ③ 头转开 → 拦；但扭头通道豁免。
    check(
        "偏航 14° → head-turned",
        g.decide(yaw = 14f).reason == "head-turned",
        "${g.decide(yaw = 14f).reason}",
    )
    check(
        "偏航 14° + 扭头通道 → 放行（扭头本身就是偏航）",
        g.decide(channel = GateChannel.TURN, yaw = 14f).allowed,
    )
    // ④ 躺下 / 侧脸 → 拦；但歪头通道豁免。
    check(
        "滚转 25° → head-tilted",
        g.decide(roll = 25f).reason == "head-tilted",
        "${g.decide(roll = 25f).reason}",
    )
    check(
        "滚转 25° + 歪头通道 → 放行（歪头本身就是滚转）",
        g.decide(channel = GateChannel.TILT, roll = 25f).allowed,
    )
    // ⑤ 读数缺失 → 一律放开（fail-open）。
    check("没有基准线（yaw=null）→ 放行", g.decide(yaw = null).allowed)
    check("没有 faceRatio → 放行", g.decide(ratio = null).allowed)

    // ⑥ 睁眼占比：50cm 盯着屏幕 → 放行。
    val eyes = GateRig()
    eyes.feed(1.0f, 2000)
    check(
        "50cm 一直睁着眼 → 放行（duty≈1.0）",
        eyes.decide().allowed,
        "duty=${eyes.gate.eyeOpenDuty()}",
    )

    // ⑦ 眼睛离开屏幕（持续低头）→ 拦。
    val away = GateRig()
    away.feed(1.0f, 1000)
    away.feed(0.01f, 2000)
    check(
        "眼睛离开屏幕 2 秒 → eyes-away",
        away.decide().reason == "eyes-away",
        "reason=${away.decide().reason} duty=${away.gate.eyeOpenDuty()}",
    )

    // ⑧ 眨眼不能把自己拦死：一次 200ms 的眨眼，占比仍然很高。
    val blink = GateRig()
    blink.feed(1.0f, 2000)
    blink.feed(0.01f, 200)   // 眨眼
    blink.feed(1.0f, 400)
    check(
        "刚眨过眼 → 仍然放行（占比≈0.9）",
        blink.decide().allowed,
        "duty=${blink.gate.eyeOpenDuty()}",
    )

    // ⑨ 近距离档跳过"睁眼占比"（实测 30cm 下这项分不开）。
    val near = GateRig()
    near.feed(0.05f, 3000)
    check(
        "30cm（faceRatio 0.52）一直读低 → 仍然放行",
        near.decide(ratio = 0.52f).allowed,
        "reason=${near.decide(ratio = 0.52f).reason}",
    )
    check(
        "同一个读数放到 50cm（0.35）→ 拦",
        near.decide(ratio = 0.35f).reason == "eyes-away",
        "${near.decide(ratio = 0.35f).reason}",
    )

    // ⑩ 丢脸再回来：旧的"睁着"样本不能留着。
    val lost = GateRig()
    lost.feed(1.0f, 2000)
    lost.gate.onFrame(null, null, lost.now)   // 没脸
    check(
        "丢脸清空窗口 → 占比未知、不拦",
        lost.gate.eyeOpenDuty() == null && lost.decide().allowed,
        "duty=${lost.gate.eyeOpenDuty()}",
    )
}

fun main() {
    println("=== GazeProbeRecorder v5.39 离线回放验证（真实代码，无 Android 依赖）===")
    testNoFaceMeansNoStart()
    testArmRequiresThreeSeconds()
    testStartOnFaceReturn()
    testPhaseSeparator()
    testShortCoverIgnored()
    testStopOnLongCover()
    testLookingAwayDoesNotStop()
    testSecondSessionNeedsNewCover()
    testLumaUnavailableFallsBackToFaceLoss()
    testDisableMidRecording()
    testMaxSessionGuard()
    testStateLine()
    testV539FailureCaseIsNowCovered()
    testStopCoverThreshold()
    testGapAndBlinkMarkers()
    testBlinkMarker()
    testGazeGate()
    println("\n=== 结果：${if (failures == 0) "全部通过" else "$failures 项失败"} ===")
    if (failures != 0) throw IllegalStateException("$failures 项失败")
}
