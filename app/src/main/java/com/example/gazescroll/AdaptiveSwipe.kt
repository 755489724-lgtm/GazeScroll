package com.example.gazescroll

/**
 * 一次**纵向**滑动的参数。
 *
 * 全部用**相对屏幕高度的比例**表达，不写死像素——这样在任何分辨率、任何 DPI 的机器上
 * 都是同一个手感。
 *
 * @param fromRatio 手指起点的 Y 比例（0 = 屏幕顶，1 = 屏幕底）
 * @param toRatio   手指终点的 Y 比例
 * @param durationMs 整个手势的时长；越长越「柔」，列表跟着手指走的惯性越小
 */
data class VerticalSwipeProfile(
    val name: String,
    val fromRatio: Float,
    val toRatio: Float,
    val durationMs: Long,
) {
    /**
     * 滚动距离占屏幕高度的比例。
     *
     * 不假设 from > to，所以直接取绝对值——这样「上滑」和「下滑」用同一套距离定义。
     */
    val distance: Float get() = kotlin.math.abs(fromRatio - toRatio)

    /**
     * 把「手指向下（内容后退）」方向的路径算出来。
     *
     * [SwipeDirection.UP] 表示手指向上走（内容前进 = 下一个）；[SwipeDirection.DOWN]
     * 则把起点终点对调，因此两种方向的滚动距离完全一致，只是方向相反。
     */
    fun pathFor(direction: SwipeDirection): Pair<Float, Float> = when (direction) {
        SwipeDirection.UP -> fromRatio to toRatio
        SwipeDirection.DOWN -> toRatio to fromRatio
        else -> fromRatio to toRatio // 横向不用这里，防御性返回
    }
}

/**
 * 自适应滑动参数表（v4.5）。
 *
 * ## 为什么需要
 *
 * 抖音是「一屏一个视频」，需要一整屏的强滑动才能切换；而微博 / 小红书是连续列表，
 * 同样幅度的手势会一次滚过去好几屏，用户的原话是「一划就滚很多」。
 * 所以滑动幅度必须跟着**当前前台应用**走。
 *
 * ## 数据来源
 *
 * 用 [AppStateManager.foregroundPackage]——它已经在通过无障碍服务的窗口列表跟踪前台
 * 应用了（这是本机唯一可靠的来源），不需要再申请新的权限。
 *
 * ## 数值是怎么定的
 *
 * - **抖音 / 快手**：39% 屏高、150ms（v5.0 实机逐组标定，见 [SHORT_VIDEO]）。
 *   曾经以为是「整屏 80%」，实机证明那样抖音根本不响应。
 * - **列表类**（微博 / 小红书）：只有 26% 的短距离，但时间长到 420ms，滑起来是
 *   「柔」的，一划大约一屏的三分之一，不再一飞好几屏。
 * - **其他**：40% / 300ms，兼顾「有点力度」和「不至于失控」。
 *
 * 关闭自适应后回到 v4.4 的固定行为（80% / 100ms），这样任何应用出问题都能一键回退。
 */
object AdaptiveSwipe {

    /** 列表类应用的出厂滑动幅度：26% 屏高（v4.5 的默认手感）。 */
    const val DEFAULT_LIST_DISTANCE = 0.26f

    /** 用户可调的列表滑动幅度下限：10% 屏高。 */
    const val MIN_LIST_DISTANCE = 0.10f

    /** 用户可调的列表滑动幅度上限：50% 屏高。 */
    const val MAX_LIST_DISTANCE = 0.50f

    /** 滑块步长：2% 屏高，10%~50% 共 20 档。 */
    const val LIST_DISTANCE_STEP = 0.02f

    /** 列表类应用的手势时长；调幅度不会改它，所以调小只是「滚得更少」而不是更生硬。 */
    const val LIST_DURATION_MS = 420L

    /**
     * 整屏视频类应用（抖音 / 快手…）。
     *
     * ## 为什么是 39% 而不是「整屏 80%」
     *
     * v4.5~v4.9 这里一直是 `0.90 → 0.10`（80% 屏高、130ms），依据是「一屏一个视频就该
     * 划一整屏」。**实机逐组测试证明这个假设是错的**：在抖音里注入 80%/130ms 时视频完全
     * 不动，而日志显示手势确实发出了（`GazeA11y: manual swipe ... ok=true`）、前台也确认
     * 是抖音——所以是**抖音不接受这个手势**，不是我们没发。
     *
     * 用参数化自测逐组试过之后，只有 `0.70 → 0.30`（39% 屏高 / 150ms）能稳定切换视频，
     * 连试 3 次全部成功。其余全部无效：
     *  - 90% / 130ms（0.95→0.05）：无效 —— 说明不是「幅度越大越好」
     *  - 60% / 400ms：无效
     *  - 45% / 350ms、50% / 700ms：无效
     *
     * 也就是说抖音认的是**短促快滑**，幅度太大反而会被它忽略。取屏幕中心对称，起点 0.70
     * 也顺带避开了底部的手势条区域。
     */
    val SHORT_VIDEO = VerticalSwipeProfile(
        name = "短视频（快滑切换）",
        fromRatio = 0.70f,
        toRatio = 0.30f,
        durationMs = 150L,
    )

    /** 微博 / 小红书等连续列表：短距离 + 慢速，柔性滚动。 */
    val LIST_FEED: VerticalSwipeProfile = listProfile(DEFAULT_LIST_DISTANCE)

    /** 默认：其他所有应用。 */
    val DEFAULT = VerticalSwipeProfile(
        name = "默认",
        fromRatio = 0.70f,
        toRatio = 0.30f,
        durationMs = 300L,
    )

    /** 关闭自适应时使用：与 v4.4 的固定参数完全一致。 */
    val FIXED = VerticalSwipeProfile(
        name = "固定（旧行为）",
        fromRatio = 0.80f,
        toRatio = 0.20f,
        durationMs = 100L,
    )

    /**
     * 按用户设定的幅度生成列表档参数。
     *
     * 起止点以屏幕中心（0.5）对称展开：幅度 26% 就是 0.63 → 0.37。用中心对称而不是
     * 从某个固定位置起手，是为了让手势始终落在屏幕中部那块最「干净」的区域——越靠边
     * 越容易被系统的状态栏 / 手势条截走。
     */
    fun listProfile(distance: Float): VerticalSwipeProfile {
        val d = distance.coerceIn(MIN_LIST_DISTANCE, MAX_LIST_DISTANCE)
        val half = d / 2f
        return VerticalSwipeProfile(
            name = "列表（柔性滚动）",
            fromRatio = 0.5f + half,
            toRatio = 0.5f - half,
            durationMs = LIST_DURATION_MS,
        )
    }

    // ------------------------------------------------------- 滑块档位换算 --

    /** 档数：10%~50% 每档 2%，共 20 档。 */
    val LIST_DISTANCE_STEPS: Int =
        Math.round((MAX_LIST_DISTANCE - MIN_LIST_DISTANCE) / LIST_DISTANCE_STEP)

    /** 滑块档位 -> 幅度比例。 */
    fun distanceForStep(step: Int): Float =
        (MIN_LIST_DISTANCE + step.coerceIn(0, LIST_DISTANCE_STEPS) * LIST_DISTANCE_STEP)
            .coerceIn(MIN_LIST_DISTANCE, MAX_LIST_DISTANCE)

    /** 幅度比例 -> 最接近的滑块档位。 */
    fun stepForDistance(distance: Float): Int {
        val clamped = distance.coerceIn(MIN_LIST_DISTANCE, MAX_LIST_DISTANCE)
        return Math.round((clamped - MIN_LIST_DISTANCE) / LIST_DISTANCE_STEP)
            .coerceIn(0, LIST_DISTANCE_STEPS)
    }

    /** 把任意幅度吸附到滑块步长。 */
    fun snapListDistance(distance: Float): Float = distanceForStep(stepForDistance(distance))

    /** 「列表滑动幅度：26% 屏高（默认）」里的那一段描述。 */
    fun describeListDistance(distance: Float): String {
        val percent = Math.round(distance * 100)
        val suffix =
            if (kotlin.math.abs(distance - DEFAULT_LIST_DISTANCE) < 0.005f) "（默认）" else ""
        return "$percent% 屏高$suffix"
    }

    /** 整屏视频类应用：一屏一个内容，必须整屏滑动。 */
    private val SHORT_VIDEO_PACKAGES = setOf(
        "com.ss.android.ugc.aweme",          // 抖音
        "com.ss.android.ugc.aweme.lite",     // 抖音极速版
        "com.ss.android.ugc.live",           // 抖音火山版
        "com.kuaishou.nebula",               // 快手极速版
        "com.smile.gifmaker",                // 快手
        "com.tencent.weishi",                // 微视
    )

    /**
     * 连续列表类应用：滑动幅度要小、要柔。
     *
     * 微博、小红书是用户实际反馈滚太快的地方；知乎、贴吧、豆瓣、B站也是同类信息流，
     * 一并放进来，保持行为一致。
     */
    private val LIST_FEED_PACKAGES = setOf(
        "com.sina.weibo",                    // 微博
        "com.xingin.xhs",                    // 小红书
        "com.zhihu.android",                 // 知乎
        "com.baidu.tieba",                   // 贴吧
        "com.douban.frodo",                  // 豆瓣
        "tv.danmaku.bili",                   // B站
    )

    /**
     * 解析当前应该用哪套纵向参数。
     *
     * @param enabled 用户是否开启自适应；关闭时返回 [FIXED]（旧行为）
     * @param foregroundPackage 当前前台应用包名，null / 未知时用 [DEFAULT]
     * @param listDistance 用户自定的列表类滑动幅度（[GazeConfig.listSwipeDistance]）
     */
    fun profileFor(
        enabled: Boolean,
        foregroundPackage: String?,
        listDistance: Float = DEFAULT_LIST_DISTANCE,
    ): VerticalSwipeProfile {
        if (!enabled) return FIXED
        return when (foregroundPackage) {
            null -> DEFAULT
            in SHORT_VIDEO_PACKAGES -> SHORT_VIDEO
            in LIST_FEED_PACKAGES -> if (listDistance == DEFAULT_LIST_DISTANCE) {
                // 默认值直接用常量，避免每次触发都新建对象。
                LIST_FEED
            } else {
                listProfile(listDistance)
            }

            else -> DEFAULT
        }
    }

    /** 设置页展示用的一句话说明。 */
    fun describe(
        enabled: Boolean,
        foregroundPackage: String?,
        listDistance: Float = DEFAULT_LIST_DISTANCE,
    ): String {
        val profile = profileFor(enabled, foregroundPackage, listDistance)
        val percent = Math.round(profile.distance * 100)
        val where = when {
            !enabled -> "自适应已关闭"
            foregroundPackage == null -> "前台未知"
            else -> TargetApps.ALL.firstOrNull { it.packageName == foregroundPackage }?.label
                ?: foregroundPackage
        }
        return "$where · ${profile.name} · 滑动 $percent% 屏高 / ${profile.durationMs}ms"
    }
}
