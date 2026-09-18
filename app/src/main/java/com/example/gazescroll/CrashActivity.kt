package com.example.gazescroll

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * v5.66：崩溃现场页 —— 只干一件事：把原因清楚地摆在屏幕上，方便截图/复制发出来。
 *
 * 它是被 [CrashReport] 在崩溃时拉起来的，也可以手动拉起看上一次的记录：
 * `adb shell am start -n com.example.gazescroll/.CrashActivity`
 */
class CrashActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TEXT = "crashText"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crash)

        val text = intent?.getStringExtra(EXTRA_TEXT)
            ?: readLastCrash()
            ?: getString(R.string.crash_none)

        findViewById<TextView>(R.id.tvCrashBody).text = text
        findViewById<TextView>(R.id.tvCrashVersion).text =
            getString(R.string.about_version, BuildConfig.VERSION_NAME) +
                "  ·  " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL +
                "  ·  Android " + android.os.Build.VERSION.RELEASE

        findViewById<TextView>(R.id.btnCrashCopy).setOnClickListener {
            val cm = getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("gazescroll-crash", text))
            Toast.makeText(this, getString(R.string.crash_copied), Toast.LENGTH_SHORT).show()
        }
        findViewById<TextView>(R.id.btnCrashClose).setOnClickListener { finish() }
    }

    private fun readLastCrash(): String? =
        runCatching { java.io.File(filesDir, "last-crash.txt").takeIf { it.exists() }?.readText() }
            .getOrNull()
}
