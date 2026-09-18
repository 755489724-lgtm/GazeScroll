package com.example.gazescroll

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * 「现在该点哪里」的悬浮气泡（v5.62）。
 *
 * ## 它要解决的那个具体问题
 *
 * 免 ADB 开无障碍的关键一步是「应用信息 → 右上角 ⋮ → 允许受限制的设置」。
 * 这个入口**藏在不同品牌的第三层菜单里**（小米在手机管家里，别的在设置 → 应用 → ⋮），
 * 用户在系统设置里来回找的时候已经离开本 App 了，App 内的引导卡片一个字也看不见 ——
 * 于是最常见的结局是「找不到，回去用 adb」。
 *
 * 这个气泡就是在那段时间里把当前该点哪里贴在屏幕上的。
 *
 * ## 为什么是「可拖动 + 可关闭」而不是「不可触摸」
 *
 * 两种做法各有代价：设成 `FLAG_NOT_TOUCHABLE` 就永远不会挡住用户要点的东西，
 * 但它一旦盖住目标控件用户毫无办法；可拖动则可以救回来。考虑到气泡是浮在别人的
 * 设置界面上、我们自己无法预知下面是什么，选可拖动。
 *
 * ## 权限
 *
 * `SYSTEM_ALERT_WINDOW` 是特殊权限，**用户自己就能给**（不需要 adb）：
 * 设置 → 应用 → 特殊应用权限 → 显示在其他应用上层。
 * 拿不到就直接不显示，App 的其余部分完全不受影响 —— 这里所有入口都是 best-effort。
 */
object GuideOverlay {

    private const val TAG = "GuideOverlay"

    /** 气泡与屏幕左右边缘、以及抽屉导航条留出的间距。 */
    private const val EDGE_DP = 16
    private const val BOTTOM_DP = 96

    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var windowManager: WindowManager? = null

    fun canDraw(ctx: Context): Boolean =
        runCatching { Settings.canDrawOverlays(ctx) }.getOrDefault(false)

    /** 跳到「显示在其他应用上层」的授权页。用户自己点一下即可，不需要 adb。 */
    fun requestPermission(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${ctx.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure {
            // 个别 ROM 不认带 package 的写法，退回不带参数的授权总页。
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    fun isShowing(): Boolean = view != null

    /**
     * 显示（或就地更新）气泡。
     *
     * @param text 当前该做哪一步的单行提示（见 `overlay_hint_*` 字符串）
     */
    fun show(ctx: Context, text: String) {
        if (!canDraw(ctx)) return

        val app = ctx.applicationContext
        val wm = app.getSystemService(WindowManager::class.java) ?: return

        val built = buildView(app, text)
        val existing = view
        if (existing != null && windowManager === wm) {
            // 已经挂着：只换文案，避免"闪一下再回来"。
            (existing.findViewWithTag<TextView>(TAG_BODY))?.text = text
            return
        }

        hide()

        val screen = screenSize(app)
        val width = (screen.first - dp(app, EDGE_DP) * 2).coerceAtLeast(dp(app, 200))
        val lp = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_FOCUSABLE：不抢系统设置的输入焦点，但又不像 NOT_TOUCHABLE 那样
            // 完全无法自救 —— 下面的拖动逻辑要靠它。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(app, EDGE_DP)
            // 先放在屏幕中部偏下，等测量完再吸附到底部；直接猜高度会闪。
            y = screen.second / 2
        }

        attachDrag(built, wm, lp, app)

        val ok = runCatching { wm.addView(built, lp) }.isSuccess
        if (!ok) {
            android.util.Log.w(TAG, "addView failed — overlay permission revoked?")
            return
        }
        view = built
        params = lp
        windowManager = wm

        built.post {
            val y = (screen.second - dp(app, BOTTOM_DP) - built.height)
                .coerceAtLeast(dp(app, 24))
            lp.y = y
            runCatching { wm.updateViewLayout(built, lp) }
        }
    }

    fun hide() {
        val v = view ?: return
        view = null
        params = null
        runCatching { windowManager?.removeViewImmediate(v) }
        windowManager = null
    }

    // ------------------------------------------------------------------ view --

    private const val TAG_BODY = "guideOverlayBody"

    private fun buildView(ctx: Context, text: String): View {
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_bg)
            setPadding(
                dp(ctx, 14), dp(ctx, 10), dp(ctx, 8), dp(ctx, 10),
            )
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(ctx).apply {
                this.text = ctx.getString(R.string.overlay_title)
                setTextColor(ContextCompat.getColor(ctx, R.color.accent))
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f,
                )
            },
        )
        header.addView(
            TextView(ctx).apply {
                this.text = "✕"
                setTextColor(ContextCompat.getColor(ctx, R.color.on_surface_dim))
                textSize = 15f
                setPadding(dp(ctx, 10), dp(ctx, 2), dp(ctx, 6), dp(ctx, 2))
                contentDescription = ctx.getString(R.string.overlay_close)
                setOnClickListener { hide() }
            },
        )

        val body = TextView(ctx).apply {
            tag = TAG_BODY
            this.text = text
            setTextColor(ContextCompat.getColor(ctx, R.color.on_surface))
            textSize = 13f
            setLineSpacing(0f, 1.35f)
            setPadding(0, dp(ctx, 6), 0, 0)
        }

        val hint = TextView(ctx).apply {
            this.text = ctx.getString(R.string.overlay_drag_hint)
            setTextColor(ContextCompat.getColor(ctx, R.color.on_surface_dim))
            textSize = 11f
            setPadding(0, dp(ctx, 4), 0, 0)
        }

        root.addView(header)
        root.addView(body)
        root.addView(hint)
        return root
    }

    /**
     * 按住气泡空白处拖动。
     *
     * 只有没被 ✕ 吃掉的触摸才会落到这里（子 View 先处理），所以拖动和关闭不打架。
     */
    private fun attachDrag(
        target: View,
        wm: WindowManager,
        lp: WindowManager.LayoutParams,
        ctx: Context,
    ) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0

        target.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    startX = lp.x
                    startY = lp.y
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val screen = screenSize(ctx)
                    lp.x = (startX + (ev.rawX - downRawX).toInt())
                        .coerceIn(-dp(ctx, 40), screen.first - dp(ctx, 40))
                    lp.y = (startY + (ev.rawY - downRawY).toInt())
                        .coerceIn(-dp(ctx, 40), screen.second - dp(ctx, 40))
                    runCatching { wm.updateViewLayout(target, lp) }
                    true
                }

                else -> false
            }
        }
    }

    // ----------------------------------------------------------------- utils --

    private fun screenSize(ctx: Context): Pair<Int, Int> {
        val wm = ctx.getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && wm != null) {
            val b = wm.currentWindowMetrics.bounds
            return b.width() to b.height()
        }
        val dm = ctx.resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    private fun dp(ctx: Context, value: Int): Int =
        (value * ctx.resources.displayMetrics.density).toInt()
}
