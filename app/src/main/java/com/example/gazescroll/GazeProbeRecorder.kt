package com.example.gazescroll

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v5.39「注视数据采集」的采集状态（**测试功能，默认关闭**）。
 *
 * 这一版**不做任何判定、不改任何现有行为**：它只是把「用户盯着屏幕」这个判据所需要的
 * 全部原始几何量按帧录下来，好让下一版能拿真实数据（而不是猜）去定阈值。
 *
 * 为什么需要它：要判「眼睛有没有盯着屏幕」，ML Kit 的人脸检测**没有虹膜**，只有
 * 人脸框 + 10 个关键点（双眼、鼻底、嘴角、双耳、双颊）+ 三个欧拉角。所以能用的判据
 * 只能是「头是否朝着手机 + 脸在画面里的位置/比例」这一类几何量（用户提的"鼻子辅助定位"
 * 正好落在这里：鼻底在脸框内的相对位置对转头最敏感）。到底哪几个量能把「看屏幕」和
 * 「看别处」分开，必须先用实测数据验证 —— 这就是本类存在的原因。
 *
 * ## 采集协议（用户口述的规则，阈值全部按"3 秒"这一条界线）
 *
 *  - **开始**：空闲状态下，用整只手盖住前置摄像头 **≥3 秒**（画面变黑且没有人脸）→
 *    进入「已就绪」；把手拿开、露出脸的那一刻开始录制。
 *  - **分段**：录制中盖住 **1~3 秒** → 记一个分段标记（用户按段落做不同姿势，靠它切分）。
 *  - **结束**：录制中盖住 **≥3 秒** → 结束本次采集，把汇总写进 CSV 并打一条日志。
 *  - 5 分钟保险：单次录制超过 [MAX_SESSION_MS] 自动结束。
 *
 * ## 为什么"盖住"要同时看亮度、纹理和近距离传感器（v5.39 → v5.40 的实机教训）
 *
 * 只判"人脸消失"会把**转头看别处**误判成盖住（采集里就有"眼睛离开屏幕"的段落，
 * 一旦被判成结束，整场数据就废了）。
 *
 * v5.39 的判据是「没有人脸 且 画面平均亮度 < 32」，**实机第一次采集就整场失败**：
 * 18:33:32 打开采集，随后 4 分钟一直是 `probe=idle`，而 18:37:01 之后人脸连续消失
 * 50 秒都没进入"已就绪"。原因很直白 —— **前置摄像头的自动曝光会把被手掌盖住的画面提亮**，
 * "盖住 = 画面黑"根本不成立。
 *
 * v5.40 改成**三条互相独立的信号并联**（任意一条成立就算盖住，全部读不到才退化成只看人脸）：
 *
 *  1. [ProximityMonitor] 的**近距离传感器**：手掌贴到手机顶部时直接翻成"近"。
 *     与曝光、光照、画面内容都无关，是最硬的一条；
 *  2. **纹理量** `texture`：抽样网格上相邻采样点的平均亮度差。手掌贴镜头是完全离焦的、
 *     没有边缘 —— 自动曝光只能把它整体提亮，**变不出边缘**；
 *  3. **亮度** `luma < [COVER_LUMA_MAX]`：AEC 没来得及补偿时仍然有效（保留原判据）。
 *
 * 三个值逐帧写进 CSV，并在人脸消失期间每秒打一行 `cover-probe`，所以阈值可以直接用
 * 实测数据复核 —— 这也是这一次能一轮定位问题的原因。
 *
 * ## 它是纯逻辑
 *
 * 本文件不引用任何 Android API（落盘与打日志由调用方注入），所以可以用
 * `tools/probe-replay/run.ps1` 离线回放验证 —— 与 [TiltDetector] 同一套做法。
 */
enum class ProbeState(val label: String) {
    /** 开关关着。 */
    OFF("off"),

    /** 开着，在等人盖住摄像头。 */
    IDLE("idle"),

    /** 已经盖够 3 秒，等露脸开始录。 */
    ARMED("armed"),

    /** 正在录制。 */
    RECORDING("rec"),
}

/**
 * 一帧的全部原始读数（不含任何判定）。
 *
 * 坐标分两套，两套都录：
 *  - `frameWidth/frameHeight` 是把相机缓冲摆正之后的尺寸，关键点按它归一化到 0..1；
 *  - `boxL/boxT/boxR/boxB` 是人脸框在同一坐标系里的位置，也是 0..1。
 *
 * 这样"关键点在画面里在哪"和"关键点在脸框里在哪"离线都能算，不用二次采集。
 * 缺失一律用 null（落盘写 `-1`），**不要**当成 0。
 */
data class ProbeSample(
    /** 墙上时钟（毫秒），用于和 logcat 的其他 tag 对齐。 */
    val wallMs: Long,
    val faceDetected: Boolean,
    val standby: Boolean,
    val frameWidth: Int,
    val frameHeight: Int,
    /** 画面平均亮度 0..255；null = 读不到（此时"盖住"看纹理与近距离传感器）。 */
    val luma: Float? = null,
    /**
     * 画面纹理量（v5.40）：抽样网格上相邻采样点的平均亮度差（0..255）。
     * 手掌贴镜头 = 完全离焦 = 没有边缘；"人走开了、画面里是房间" = 到处是边缘。
     */
    val texture: Float? = null,
    /** 近距离传感器是否"近"（v5.40）：手掌贴住手机顶部时为 true。 */
    val proximityNear: Boolean = false,
    /** 近距离传感器是否可用（v5.40）；不可用时这一条不参与判据。 */
    val proximityAvailable: Boolean = false,
    /** 环境光（lux，v5.40）；-1 = 没有。只诊断，不参与判据。 */
    val lux: Float? = null,
    val boxLeft: Float = -1f,
    val boxTop: Float = -1f,
    val boxRight: Float = -1f,
    val boxBottom: Float = -1f,
    val faceRatio: Float? = null,
    val eulerX: Float? = null,
    val eulerY: Float? = null,
    val eulerZ: Float? = null,
    val eyeLeftX: Float? = null,
    val eyeLeftY: Float? = null,
    val eyeRightX: Float? = null,
    val eyeRightY: Float? = null,
    val noseX: Float? = null,
    val noseY: Float? = null,
    val mouthX: Float? = null,
    val mouthY: Float? = null,
    val earLeftX: Float? = null,
    val earLeftY: Float? = null,
    val earRightX: Float? = null,
    val earRightY: Float? = null,
    val cheekLeftX: Float? = null,
    val cheekLeftY: Float? = null,
    val cheekRightX: Float? = null,
    val cheekRightY: Float? = null,
    val eyeOpenLeft: Float? = null,
    val eyeOpenRight: Float? = null,
    /** 张嘴比例（嘴到鼻底 / 脸框高），v4.5 起已有的量。 */
    val mouthOpenRatio: Float? = null,
    /** 俯视几何比例（下巴到眼睛 / 脸框高），v5.6 起已有的量。 */
    val chinRatio: Float? = null,
    /** 鼻底在脸框内的归一化 Y。 */
    val noseNormY: Float? = null,
    val chinNormY: Float? = null,
    val eyeNormY: Float? = null,
    /** 鼻底相对眼睛的归一化距离（平移无关）。 */
    val noseRelEye: Float? = null,
    val chinRelEye: Float? = null,
    val trackingId: Int? = null,
)

/**
 * 采集器：状态机 + CSV 格式化 + 汇总统计。
 *
 * 落盘与打日志通过构造函数注入（[log] / [openFile] / [write] / [closeFile]），
 * 所以这个类本身没有任何 Android 依赖，可以离线回放。
 */
class GazeProbeRecorder(
    /** 一行里程碑日志（调用方打 I/GazeProbe）。 */
    private val log: (String) -> Unit,
    /** 开一个采集文件（参数是文件名）。调用方负责建目录 / 建文件。 */
    private val openFile: (String) -> Unit,
    /** 写一行内容（CSV 数据行或 `#` 注释行）。调用方负责加换行。 */
    private val write: (String) -> Unit,
    /** 收尾（关闭文件句柄）。 */
    private val closeFile: () -> Unit,
    /** 写进 CSV 头部的版本标记，例如 `GazeScroll v5.39 (89)`。 */
    private val appTag: String = "",
) {

    companion object {
        /** 盖住摄像头 ≥ 这么久 → 开始（空闲时）/ 结束（录制中）。用户口述的"3 秒"。 */
        const val START_COVER_MS = 3000L

        /** 录制中盖住 ≥ 这个时长就结束（与开始用同一条界线）。 */
        const val STOP_COVER_MS = 3000L

        /** 录制中盖住 1~3 秒算一次分段标记；不足 1 秒的手一晃忽略。 */
        const val MIN_PHASE_COVER_MS = 1000L

        /** 单次录制的保险上限：5 分钟。 */
        const val MAX_SESSION_MS = 300_000L

        /**
         * 「画面被盖住」的亮度上限（0..255 的平均亮度）。
         *
         * v5.39 只有这一条（32），实机证明**不够**：前置摄像头自动曝光会把被盖住的画面
         * 提亮到 32 以上，于是整场采集一次都没认出"盖住"。v5.40 起它降级为三条并联信号
         * 里的一条（AEC 没来得及补偿时仍然有效），主判据交给近距离传感器与纹理量。
         */
        const val COVER_LUMA_MAX = 32f

        /**
         * 「画面被盖住」的纹理上限（v5.40）：抽样网格上相邻采样点的平均亮度差。
         *
         * 手掌/手指贴住镜头时画面完全离焦，相邻采样点几乎一样（个位数以下）；
         * 正常画面（人脸、房间、哪怕是暗房间的噪点）都在它之上。
         * CSV 里逐帧记了 `tex`、`cover-probe` 行每秒记一次，实测后可以再调。
         */
        const val COVER_TEXTURE_MAX = 4f

        /** 人脸消失期间 `cover-probe` 诊断行的最小间隔。 */
        const val COVER_LOG_INTERVAL_MS = 1000L

        /** CSV 的列顺序。改这里就必须同步改 [formatRow]，两边永远一起动。 */
        val COLUMNS = listOf(
            "idx", "wall", "elapsed", "phase", "face", "cover", "luma", "tex", "prox", "lux",
            "standby", "fw", "fh",
            "boxL", "boxT", "boxR", "boxB", "faceRatio",
            "eX", "eY", "eZ",
            "eyeLx", "eyeLy", "eyeRx", "eyeRy", "noseX", "noseY", "mouthX", "mouthY",
            "earLx", "earLy", "earRx", "earRy", "cheekLx", "cheekLy", "cheekRx", "cheekRy",
            "openL", "openR", "mouthRatio", "chinRatio",
            "noseNormY", "chinNormY", "eyeNormY", "noseRelEye", "chinRelEye", "track",
        )

        /** 浮点缺省写成 -1（CSV 里不留空，省得离线解析还要处理空字段）。 */
        private fun f(v: Float?, digits: Int = 4): String =
            if (v == null || v.isNaN()) "-1" else String.format(Locale.US, "%.${digits}f", v)

        /** 近距离传感器读数的文字形式，诊断行用。 */
        private fun proximityLabel(sample: ProbeSample): String = when {
            !sample.proximityAvailable -> "-"
            sample.proximityNear -> "NEAR"
            else -> "far"
        }
    }

    /** 当前状态。 */
    var state: ProbeState = ProbeState.OFF
        private set

    /** 当前是第几段（从 1 开始；0 = 还没开始）。 */
    var phase: Int = 0
        private set

    /** 本次已录制的帧数。 */
    var rows: Int = 0
        private set

    /** 本次已录制时长（毫秒）。 */
    var durationMs: Long = 0L
        private set

    /** 当前文件名（空 = 没在录）。 */
    var fileName: String = ""
        private set

    /** 会话序号（在本次开关周期里第几次录制）。 */
    var sessionIndex: Int = 0
        private set

    /** 最近一次的收尾摘要（设置页显示用）。 */
    var lastSummary: String = ""
        private set

    /** 空闲状态下是否已经见过人脸：必须先见到脸，盖住才算"准备开始"。 */
    private var sawFace = false

    /** 连续"被盖住"的起始时刻；0 = 当前没被盖住。 */
    private var coverStartMs = 0L

    /** 人脸连续消失的起始时刻（v5.40，诊断行用；与人脸/盖住的判定无关）。 */
    private var noFaceSinceMs = 0L

    /** 上一次 `cover-probe` 诊断行的时刻（v5.40）。 */
    private var lastCoverLogAtMs = 0L

    private var sessionStartMs = 0L
    private var sessionStartWallMs = 0L
    private var faceRows = 0
    private var rowsInPhase = 0
    private var facesInPhase = 0
    private val phaseRows = mutableListOf<Int>()
    private val phaseFaces = mutableListOf<Int>()

    private val wallFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val nameFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val stampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun isCovered(sample: ProbeSample): Boolean {
        if (sample.faceDetected) return false
        // ① 近距离传感器（最硬的一条，与画面无关）。
        if (sample.proximityAvailable && sample.proximityNear) return true
        // ② 纹理量：手掌贴镜头 = 完全离焦 = 没有边缘，自动曝光变不出边缘来。
        val texture = sample.texture
        if (texture != null && texture < COVER_TEXTURE_MAX) return true
        // ③ 亮度（v5.39 的原判据，AEC 没来得及补偿时仍然有效）。
        val luma = sample.luma
        if (luma != null && luma < COVER_LUMA_MAX) return true
        // 三条全都读不到（老机型没有近距离传感器、画面也读不出来）→ 退化成只看人脸消失。
        return !sample.proximityAvailable && texture == null && luma == null
    }

    /** 开关被关掉（或服务停止）时调用：把进行中的一次录制收尾。 */
    fun disable(nowMs: Long) {
        if (state == ProbeState.OFF) return
        if (state == ProbeState.RECORDING) finishSession(nowMs, "probe turned off")
        state = ProbeState.OFF
        sawFace = false
        coverStartMs = 0L
        noFaceSinceMs = 0L
        lastCoverLogAtMs = 0L
        log("probe OFF${if (lastSummary.isEmpty()) "" else " — $lastSummary"}")
    }

    /**
     * 每帧调用一次（只有开关打开时调用方才会喂帧；关掉时调 [disable]）。
     *
     * @param nowMs `SystemClock.elapsedRealtime()`，所有时长都按它算。
     */
    fun onFrame(sample: ProbeSample, nowMs: Long) {
        if (state == ProbeState.OFF) {
            state = ProbeState.IDLE
            sawFace = false
            coverStartMs = 0L
            log(
                "probe ON — 整只手盖住前置摄像头 ${START_COVER_MS / 1000} 秒 = 开始采集；" +
                    "录制中盖 1~${STOP_COVER_MS / 1000} 秒 = 分段，盖 ≥${STOP_COVER_MS / 1000} 秒 = 结束",
            )
        }

        val covered = isCovered(sample)

        // v5.40：人脸消失期间每秒打一行三个"盖住"信号的真实读数 ——
        // v5.39 整场没认出"盖住"时，就是因为当时只记亮度、而亮度被自动曝光带走了。
        if (sample.faceDetected) {
            noFaceSinceMs = 0L
        } else {
            if (noFaceSinceMs == 0L) noFaceSinceMs = nowMs
            if (nowMs - lastCoverLogAtMs >= COVER_LOG_INTERVAL_MS) {
                lastCoverLogAtMs = nowMs
                log(
                    "cover-probe faceLostFor=${nowMs - noFaceSinceMs}ms" +
                        " luma=${f(sample.luma, 1)} tex=${f(sample.texture, 1)}" +
                        " prox=${proximityLabel(sample)} lux=${f(sample.lux, 0)}" +
                        " covered=${if (covered) 1 else 0} state=${state.label}",
                )
            }
        }
        when (state) {
            ProbeState.OFF -> Unit

            ProbeState.IDLE -> {
                if (!covered) {
                    // 只要正常看到人脸就算"见过脸"；脸丢失但画面是亮的（看别处）不算盖住。
                    if (sample.faceDetected) sawFace = true
                    coverStartMs = 0L
                } else if (sawFace) {
                    if (coverStartMs == 0L) coverStartMs = nowMs
                    val held = nowMs - coverStartMs
                    if (held >= START_COVER_MS) {
                        state = ProbeState.ARMED
                        log(
                            "ARMED: camera covered ${held}ms (luma=${f(sample.luma, 1)}" +
                                " tex=${f(sample.texture, 1)} prox=${proximityLabel(sample)})" +
                                " — 露出脸就开始录制",
                        )
                    }
                }
            }

            ProbeState.ARMED -> {
                if (sample.faceDetected) startSession(sample, nowMs)
            }

            ProbeState.RECORDING -> recordFrame(sample, nowMs, covered)
        }
    }

    private fun startSession(sample: ProbeSample, nowMs: Long) {
        sessionIndex++
        sessionStartMs = nowMs
        sessionStartWallMs = sample.wallMs
        // 关键：把"盖住"的计时清掉。它是**开始录制那一次**盖住留下的，不清的话
        // 第一帧就会拿它去算"刚结束了一段遮挡"，于是段号一开录就变成 2
        // （v5.39 离线回放抓出来的 bug）。
        coverStartMs = 0L
        // 新一次录制里，第一次"盖住"要能立刻打日志（不要被上一次的限流挡住）。
        lastCoverLogAtMs = 0L
        fileName = "probe-" + nameFormat.format(Date(sample.wallMs)) + ".csv"
        phase = 1
        rows = 0
        faceRows = 0
        durationMs = 0L
        rowsInPhase = 0
        facesInPhase = 0
        phaseRows.clear()
        phaseFaces.clear()

        openFile(fileName)
        write("# GazeScroll gaze probe — v5.39 注视数据采集（只记录，不判定）")
        if (appTag.isNotEmpty()) write("# app=$appTag")
        write("# session=${sessionIndex} start=${stampFormat.format(Date(sample.wallMs))}")
        write(
            "# cover rule (v5.40): no face AND (proximity NEAR OR texture < ${f(COVER_TEXTURE_MAX, 1)}" +
                " OR luma < ${f(COVER_LUMA_MAX, 1)}) ; " +
                "start >= ${START_COVER_MS}ms, phase cover ${MIN_PHASE_COVER_MS}..${STOP_COVER_MS}ms, " +
                "stop >= ${STOP_COVER_MS}ms",
        )
        write("# x,y are normalised to the upright frame (0..1); missing = -1")
        write(COLUMNS.joinToString(","))

        state = ProbeState.RECORDING
        log("REC START #$sessionIndex file=$fileName")
    }

    private fun recordFrame(sample: ProbeSample, nowMs: Long, covered: Boolean) {
        var stopReason: String? = null

        if (covered) {
            if (coverStartMs == 0L) coverStartMs = nowMs
            val held = nowMs - coverStartMs
            if (held >= STOP_COVER_MS) stopReason = "covered ${held}ms"
        } else if (coverStartMs != 0L) {
            val held = nowMs - coverStartMs
            coverStartMs = 0L
            if (held >= MIN_PHASE_COVER_MS) {
                phaseRows.add(rowsInPhase)
                phaseFaces.add(facesInPhase)
                phase++
                rowsInPhase = 0
                facesInPhase = 0
                write("# PHASE $phase at elapsed=${nowMs - sessionStartMs}ms (cover ${held}ms, rows=${rows})")
                log("PHASE $phase at ${"%.1f".format(Locale.US, (nowMs - sessionStartMs) / 1000f)}s (cover ${held}ms, rows=$rows)")
            }
        }

        rows++
        rowsInPhase++
        if (sample.faceDetected) {
            faceRows++
            facesInPhase++
        }
        durationMs = nowMs - sessionStartMs
        write(formatRow(sample, rows, phase, durationMs, covered))

        if (stopReason == null && durationMs >= MAX_SESSION_MS) stopReason = "max ${MAX_SESSION_MS}ms"
        if (stopReason != null) {
            finishSession(nowMs, stopReason)
            state = ProbeState.IDLE
            sawFace = false
            coverStartMs = 0L
        }
    }

    private fun finishSession(nowMs: Long, reason: String) {
        if (state != ProbeState.RECORDING || sessionStartMs == 0L) return
        phaseRows.add(rowsInPhase)
        phaseFaces.add(facesInPhase)
        durationMs = (nowMs - sessionStartMs).coerceAtLeast(0L)
        write(
            "# SUMMARY session=$sessionIndex rows=$rows faces=$faceRows " +
                "durationMs=$durationMs phases=$phase stop=$reason",
        )
        for (i in phaseRows.indices) {
            write("# PHASE ${i + 1} rows=${phaseRows[i]} faces=${phaseFaces[i]}")
        }
        closeFile()

        val summary = "session #$sessionIndex: rows=$rows faces=$faceRows " +
            "duration=${"%.1f".format(Locale.US, durationMs / 1000f)}s phases=$phase " +
            "file=$fileName ($reason)"
        lastSummary = summary
        log("REC STOP $summary")

        sessionStartMs = 0L
        phase = 0
        rows = 0
        faceRows = 0
    }

    /** 一帧 → 一行 CSV。列顺序必须与 [COLUMNS] 完全一致。 */
    private fun formatRow(
        sample: ProbeSample,
        idx: Int,
        phase: Int,
        elapsedMs: Long,
        covered: Boolean,
    ): String = listOf(
        idx.toString(),
        wallFormat.format(Date(sample.wallMs)),
        elapsedMs.toString(),
        phase.toString(),
        if (sample.faceDetected) "1" else "0",
        if (covered) "1" else "0",
        f(sample.luma, 1),
        f(sample.texture, 2),
        if (sample.proximityAvailable) {
            if (sample.proximityNear) "1" else "0"
        } else {
            "-1"
        },
        f(sample.lux, 1),
        if (sample.standby) "1" else "0",
        sample.frameWidth.toString(),
        sample.frameHeight.toString(),
        f(sample.boxLeft),
        f(sample.boxTop),
        f(sample.boxRight),
        f(sample.boxBottom),
        f(sample.faceRatio),
        f(sample.eulerX, 2),
        f(sample.eulerY, 2),
        f(sample.eulerZ, 2),
        f(sample.eyeLeftX),
        f(sample.eyeLeftY),
        f(sample.eyeRightX),
        f(sample.eyeRightY),
        f(sample.noseX),
        f(sample.noseY),
        f(sample.mouthX),
        f(sample.mouthY),
        f(sample.earLeftX),
        f(sample.earLeftY),
        f(sample.earRightX),
        f(sample.earRightY),
        f(sample.cheekLeftX),
        f(sample.cheekLeftY),
        f(sample.cheekRightX),
        f(sample.cheekRightY),
        f(sample.eyeOpenLeft, 3),
        f(sample.eyeOpenRight, 3),
        f(sample.mouthOpenRatio, 4),
        f(sample.chinRatio, 4),
        f(sample.noseNormY, 4),
        f(sample.chinNormY, 4),
        f(sample.eyeNormY, 4),
        f(sample.noseRelEye, 4),
        f(sample.chinRelEye, 4),
        sample.trackingId?.toString() ?: "-1",
    ).joinToString(",")

    /** 诊断行里的一小段（GazeDiag）。 */
    fun stateLine(): String = when (state) {
        ProbeState.OFF -> "probe=off"
        ProbeState.IDLE -> "probe=idle sawFace=$sawFace"
        ProbeState.ARMED -> "probe=armed"
        ProbeState.RECORDING -> "probe=rec ph=$phase rows=$rows faces=$faceRows ${(durationMs / 1000f)}s"
    }
}
