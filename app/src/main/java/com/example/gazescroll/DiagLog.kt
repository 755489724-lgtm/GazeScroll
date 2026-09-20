package com.example.gazescroll

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * v5.67：把诊断日志写进**文件**，让它不再被 logcat 刷屏冲掉。
 *
 * ## 为什么需要它（2026-09-20 实测）
 *
 * App 自己的诊断日志一直是 `Log.i(...)` 写在 logcat 里的，看起来没问题，
 * 实际上**一条都留不住**：
 *
 *  - ML Kit 的 `FaceDetector` 每分析一帧就打一批
 *    `D/ThickFaceDetector: Unknown landmark type: N`（**只在检测到人脸时打**）；
 *  - 实测「有人脸」时 4 秒 960 行 → 16 秒 4416 行，即 **240~380 行/秒**；
 *  - 而小米 13 的 logcat `main` 环形缓冲只有 **2 MiB**（`logcat -g` 实测）
 *    → 整块缓冲**约 2 秒就被冲干净**。
 *
 * 后果：`GazeSelfCheck` 自检行、`AppState` 前台判定、`GazeDiag` 触发记录
 * **一律活不过 2 秒**，`adb logcat` 里基本什么都看不到。
 * 这正是历史上「远程拿不到 logcat」「下拉状态栏才好」这类问题查不动的原因
 * —— **不是日志没写，是写完立刻被挤掉。**
 *
 * ## 为什么不去关掉那批刷屏日志
 *
 * 试过，两条路都堵死（详见 CHANGELOG v5.67）：
 *  - `Log.setLoggable` 被 Android 10+ 的**非 SDK 接口拦截**挡掉（真机拿到异常）；
 *  - `FaceDetectorOptions` **没有日志开关**（查过 AAR）。
 *
 * 所以换一个思路：**不去跟 logcat 抢地方，直接写到 logcat 管不着的地方。**
 *
 * ## 行为约定（很重要）
 *
 *  - **只记录，不参与任何判定**：本类不读也不写任何判定状态，所有调用点都在
 *    「已经算完、已经打完 log」之后，纯粹是一次 append。
 *  - **写不进去也不能影响主流程**：全部 `runCatching` 包住，任何异常都吞掉。
 *  - **不常驻内存、不自己起线程**：调用方在哪个线程就哪个线程写，
 *    不做缓冲、不做异步队列，免得引入新的时序问题。
 *  - **有上限**：单文件 [MAX_BYTES]，超过就整体挪成 `.1`（老的 `.1` 丢掉），
 *    所以最多占 [MAX_BYTES] × 2。绝不会把手机存储写满。
 *
 * ## 文件在哪、怎么拿出来
 *
 * `Android/data/com.example.gazescroll/files/diag/`
 * （`getExternalFilesDir`，卸载 App 会一起删掉，不会污染用户相册/文档）。
 *
 *   adb pull /sdcard/Android/data/com.example.gazescroll/files/diag/diag.log
 *
 * 这是**调试辅助功能**：关掉之后一个字节都不写。
 */
object DiagLog {

    private const val TAG = "GazeDiagLog"

    /** 目录名（在 `getExternalFilesDir` 下）。 */
    private const val DIR_NAME = "diag"

    /** 主文件名；轮转后老内容挪到 `diag.log.1`。 */
    private const val FILE_NAME = "diag.log"

    /** 单文件上限。4 MiB × 2 份 = 最多 8 MiB。 */
    private const val MAX_BYTES = 4L * 1024L * 1024L

    /**
     * 开关镜像。
     *
     * `null` = 还没从设置里读过。调用点每帧都在跑（GazeDiag 每秒一行），
     * 所以这里缓存一份，不为每一行去读一次 SharedPreferences。
     * 设置页改动时由 [setEnabled] 立刻刷新。
     */
    private val enabled = AtomicReference<Boolean?>(null)

    /** 距离上次检查文件大小过了多久（毫秒）—— 每次 append 都 `length()` 没必要。 */
    private val lastSizeCheckMs = AtomicLong(0L)

    /** 供设置页显示用：当前文件字节数，0 表示还没有文件。 */
    fun currentBytes(ctx: Context): Long = runCatching { file(ctx)?.length() ?: 0L }.getOrDefault(0L)

    /** 供设置页显示用：日志文件路径（拿不到外部存储时返回 null）。 */
    fun currentPath(ctx: Context): String? = runCatching { file(ctx)?.absolutePath }.getOrNull()

    /** 设置页改开关时调用，同时刷新缓存。 */
    fun setEnabled(ctx: Context, on: Boolean) {
        enabled.set(on)
        AppPrefs.setDiagLogEnabled(ctx, on)
    }

    /** 强制从设置里重读一次（服务启动时调用）。 */
    fun refreshEnabled(ctx: Context) {
        enabled.set(AppPrefs.isDiagLogEnabled(ctx))
    }

    /** 清空日志（设置页的「清空」按钮）。 */
    fun clear(ctx: Context) {
        runCatching {
            val f = file(ctx) ?: return
            f.delete()
            rotatedFile(ctx)?.delete()
        }.onFailure { Log.w(TAG, "clear failed", it) }
    }

    /**
     * 追加一行。
     *
     * @param category 短的分类名，用来在文件里区分来源（如 `selfcheck` / `state` / `trigger`）。
     *   用**英文短词**，因为这个文件是要贴给下一个会话看的，ASCII 最不容易出乱码。
     * @param message 正文，一般就是原来打给 logcat 的那一行。
     */
    fun append(ctx: Context?, category: String, message: String) {
        if (ctx == null) return
        runCatching {
            // 第一次调用时才去读设置，之后走缓存。
            val on = enabled.get() ?: AppPrefs.isDiagLogEnabled(ctx).also { enabled.set(it) }
            if (!on) return

            val f = file(ctx) ?: return
            rotateIfNeeded(ctx, f)

            val line = buildString {
                append(timeStamp())
                append(' ')
                append(category)
                append(' ')
                append(message)
                append('\n')
            }
            f.appendText(line)
        }.onFailure {
            // 写日志失败绝不能影响主流程。打一次 logcat 就算了。
            Log.w(TAG, "append failed: ${it.message}")
        }
    }

    // ------------------------------------------------------------------ 内部 --

    /**
     * 时间戳用「墙钟 + 开机以来的毫秒数」两段。
     *
     * 墙钟是给人看的；`elapsedRealtime` 是为了跨进程/跨重启对齐事件顺序
     * （历史排查里吃过「只看墙钟分不清先后」的亏）。
     */
    private fun timeStamp(): String {
        val wall = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        return "$wall/${SystemClock.elapsedRealtime()}"
    }

    private fun dir(ctx: Context): File? =
        ctx.getExternalFilesDir(null)?.let { File(it, DIR_NAME) }

    private fun file(ctx: Context): File? {
        val d = dir(ctx) ?: return null
        if (!d.exists() && !d.mkdirs()) return null
        return File(d, FILE_NAME)
    }

    private fun rotatedFile(ctx: Context): File? = file(ctx)?.let { File(it.parentFile, "$FILE_NAME.1") }

    /**
     * 超过上限就把当前文件整体挪成 `.1`（老的 `.1` 直接丢掉），然后从空文件重新写。
     *
     * 之所以要节流检查：`length()` 是一次 stat 系统调用，逐帧调用没必要。
     * 每 [SIZE_CHECK_INTERVAL_MS] 查一次就够了 —— 反正溢出的那点内容
     * 会在下一次检查时一起轮转掉。
     */
    private fun rotateIfNeeded(ctx: Context, f: File) {
        val now = SystemClock.elapsedRealtime()
        val last = lastSizeCheckMs.get()
        if (now - last < SIZE_CHECK_INTERVAL_MS) return
        if (!lastSizeCheckMs.compareAndSet(last, now)) return
        if (!f.exists() || f.length() < MAX_BYTES) return
        runCatching {
            val old = rotatedFile(ctx) ?: return
            if (old.exists()) old.delete()
            f.renameTo(old)
        }.onFailure { Log.w(TAG, "rotate failed", it) }
    }

    /** 文件大小检查间隔。 */
    private const val SIZE_CHECK_INTERVAL_MS = 30_000L
}
