package com.example.gazescroll

/**
 * The apps the user can pick as paging targets.
 *
 * IMPORTANT: every package listed here must also appear in the manifest's
 * `<queries>` block. Android 11+ hides other packages by default, so without it
 * `getPackageInfo` throws and the app would look "not installed".
 */
object TargetApps {

    data class Entry(val packageName: String, val label: String)

    val ALL = listOf(
        Entry("com.ss.android.ugc.aweme", "抖音"),
        Entry("com.ss.android.ugc.aweme.lite", "抖音极速版"),
        Entry("com.sina.weibo", "微博"),
        Entry("com.xingin.xhs", "小红书"),
        Entry("tv.danmaku.bili", "B站"),
    )

    /** Selected out of the box. */
    const val DEFAULT_PACKAGE = "com.ss.android.ugc.aweme"

    fun defaultSelection(): Set<String> = setOf(DEFAULT_PACKAGE)
}
