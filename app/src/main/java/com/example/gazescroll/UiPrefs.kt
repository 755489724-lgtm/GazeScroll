package com.example.gazescroll

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * 界面自己的偏好，存在**独立的一个文件**里（`ui_prefs.xml`）。
 *
 * 为什么单独开一个文件，而不是塞进 `AppPrefs`（`gaze_scroll_prefs.xml`）：
 * v5.46 之前出过一次严重事故——有人手改 `gaze_scroll_prefs.xml`，同名键写重了，
 * XML 解析失败，SharedPreferences 当成空配置，用户所有设置被清空（见 HANDOVER §7.1）。
 * 界面偏好和功能配置分开存以后，**改界面永远碰不到用户的检测设置**，
 * 这一类事故在结构上就不可能再发生。
 *
 * 这里只放「跟检测行为完全无关」的东西：目前只有主题。
 */
object UiPrefs {

    private const val FILE = "ui_prefs"
    private const val KEY_THEME = "themeMode"
    private const val KEY_CARD_ORDER = "cardOrder"

    const val THEME_LIGHT = "light"
    const val THEME_DARK = "dark"
    const val THEME_SYSTEM = "system"

    /** 主题三档，默认「白色」（用户 v5.47 点名要的默认值）。 */
    fun themeMode(ctx: Context): String =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_THEME, THEME_LIGHT)
            ?: THEME_LIGHT

    fun setThemeMode(ctx: Context, mode: String) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME, mode)
            .apply()
    }

    /** 把存下来的三档换算成 AppCompat 的 night mode 常量。 */
    fun nightModeOf(mode: String): Int = when (mode) {
        THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
        THEME_SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        else -> AppCompatDelegate.MODE_NIGHT_NO
    }

    /**
     * 首页卡片的顺序（v5.48）：存的是**资源名**（`cardHeadPose,cardTurn,…`）而不是数字 id，
     * 这样重新构建、改布局也不会让顺序串位。null = 从没用过自定义顺序（用布局里的默认顺序）。
     */
    fun cardOrder(ctx: Context): String? =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY_CARD_ORDER, null)

    fun setCardOrder(ctx: Context, order: String?) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .apply { if (order == null) remove(KEY_CARD_ORDER) else putString(KEY_CARD_ORDER, order) }
            .apply()
    }
}
