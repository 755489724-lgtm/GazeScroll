package com.example.gazescroll

import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v5.66：把"闪退"变成"能看懂的屏幕"。
 *
 * ## 为什么要有它
 *
 * 有人把 APK 装到别的手机上，一点开就闪退 —— 而**远程拿不到 logcat**，
 * 只能靠对方口述，等于瞎子摸象。这个类装上全局未捕获异常处理器：
 * 崩溃时把堆栈写进 `files/last-crash.txt`，**并把原因直接显示在屏幕上**，
 * 用户截一张图发过来就够了。
 *
 * ## 它不改变任何检测行为
 *
 * 只在"本来就会崩"的那条路径上多写一个文件、多起一个 Activity。
 */
object CrashReport {

    private const val TAG = "GazeCrash"
    private const val FILE_NAME = "last-crash.txt"

    /** 在 Application.onCreate 里调用一次。 */
    fun install(app: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { handle(app.applicationContext, thread, error) }
            // 交给系统默认处理（保持原来的行为：进程结束、写系统 tombstone）
            if (previous != null) previous.uncaughtException(thread, error)
        }
    }

    private fun handle(app: Context, thread: Thread, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val header = buildString {
            appendLine("时间: $stamp")
            appendLine("机型: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("版本: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("线程: ${thread.name}")
            appendLine("---")
        }
        val full = header + trace

        // 1) 落盘，万一用户还能把文件取出来（adb / 文件管理器）
        runCatching { File(app.filesDir, FILE_NAME).writeText(full) }

        // 2) 直接显示在屏幕上 —— 用户截图就能把原因发出来
        runCatching {
            app.startActivity(
                Intent(app, CrashActivity::class.java)
                    .putExtra(CrashActivity.EXTRA_TEXT, full)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
        }
    }
}
