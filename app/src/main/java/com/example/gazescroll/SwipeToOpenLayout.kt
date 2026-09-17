package com.example.gazescroll

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * 首页容器：**在页面任意位置向左滑一下**就去呼出右侧的设置页。
 *
 * 为什么不用 DrawerLayout 自带的边缘手势：它只认「从屏幕最右边那条边往里拖」，
 * 用户在卡片上随手往左一划是没反应的。这里在父容器层面拦一道——
 * 只有「横向位移够大、且明显比纵向更横」的手势才拦下来（纵向照常滚页面），
 * 拦下来之后交给 [onSwipeLeft] 去开抽屉。
 *
 * 手势判定只发生在 onInterceptTouchEvent 里，所以卡片上的开关、单选、
 * 滑块该收到的点击和拖动一个都不会少。
 */
class SwipeToOpenLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /** 识别出一次「向左滑」时回调一次（每次手势最多一次）。 */
    var onSwipeLeft: (() -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    /** 触发距离：56dp 左右，和系统里「甩一下」的手感一致。 */
    private val triggerPx = 56f * resources.displayMetrics.density

    private var downX = 0f
    private var downY = 0f
    private var fired = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                fired = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (!fired) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    // 向左（dx 为负）、够远、而且横向分量是纵向的两倍左右才算「左滑一下」。
                    if (dx < -triggerPx && -dx > touchSlop && abs(dy) < -dx * 0.6f) {
                        fired = true
                        onSwipeLeft?.invoke()
                        return true
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> fired = false
        }
        return false
    }

    /** 拦下来的那次手势，剩下的 MOVE/UP 由自己吃掉，避免又被子 View 当成点击。 */
    override fun onTouchEvent(event: MotionEvent): Boolean = true
}
